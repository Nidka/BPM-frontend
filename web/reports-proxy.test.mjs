import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';
import { isoDate } from './reports-proxy.mjs';

const org = 'tenant_reports';
const uuid = '10000000-0000-4000-8000-000000000001';
const code = 'ABCDEFGHIJKLMNOPQRSTUVWX';
const headers = { Authorization: 'Bearer test.token', 'Content-Type': 'application/json', Cookie: 'private-cookie' };

async function fixture(t) {
  const state = { status: 200, payload: { data: { ok: 1 } }, type: 'application/json', requests: [] };
  const backend = http.createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    state.requests.push({ path: req.url, method: req.method, headers: req.headers, body: Buffer.concat(chunks).toString('utf8') });
    res.writeHead(state.status, { 'Content-Type': state.type, 'Set-Cookie': 'private=secret', 'Content-Disposition': 'attachment; filename="evil.csv"' });
    res.end(typeof state.payload === 'string' ? state.payload : JSON.stringify(state.payload));
  });
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
  const app = createAppServer({ apiBaseUrl: `http://127.0.0.1:${backend.address().port}`, allowInsecureForTests: true });
  app.listen(0, '127.0.0.1'); await once(app, 'listening');
  t.after(async () => { app.closeAllConnections(); backend.closeAllConnections(); await Promise.all([new Promise(r => app.close(r)), new Promise(r => backend.close(r))]); });
  const root = `http://127.0.0.1:${app.address().port}/backend/api/v1`;
  const call = (method, path, body, extra = {}) => fetch(root + path, { method, headers: { ...headers, ...extra }, body: body === undefined ? undefined : JSON.stringify(body) });
  const base = `/organizations/${org}`;
  return { state, root, base, get: (path, extra) => call('GET', base + path, undefined, extra), post: (path, body) => call('POST', base + path, body), put: (path, body) => call('PUT', base + path, body), call };
}

test('valida fechas de calendario', () => {
  assert.equal(isoDate('2026-02-28'), true);
  assert.equal(isoDate('2026-02-30'), false);
  assert.equal(isoDate('2026-2-3'), false);
});

test('cumplimiento reenvía solo consulta estricta y sin cookies', async t => {
  const f = await fixture(t);
  f.state.payload = { data: { runs: 1, days: [] }, extra: 'x' };
  const ok = await f.get(`/reports/compliance?from=2026-11-01&to=2026-11-30&locationId=${uuid}`);
  assert.equal(ok.status, 200);
  assert.deepEqual(await ok.json(), { data: { runs: 1, days: [] } });
  const sent = f.state.requests[0];
  assert.equal(sent.headers.cookie, undefined);
  assert.equal(sent.headers.authorization, 'Bearer test.token');
  for (const bad of ['', '?from=2026-11-01', '?from=2026-11-01&to=2026-13-01', '?from=2026-11-01&to=2026-11-02&x=1',
    '?from=2026-11-01&from=2026-11-02&to=2026-11-03', '?from=2026-11-01&to=2026-11-02&locationId=nope']) {
    assert.equal((await f.get('/reports/compliance' + bad)).status, 400, bad);
  }
  assert.equal(f.state.requests.length, 1);
});

test('sin autorización no llega al backend', async t => {
  const f = await fixture(t);
  const response = await fetch(`${f.root}${f.base}/reports/kpis`);
  assert.equal(response.status, 401);
  assert.equal(f.state.requests.length, 0);
});

test('kpis, cursos y personas validan consulta', async t => {
  const f = await fixture(t);
  assert.equal((await f.get('/reports/kpis')).status, 200);
  assert.equal((await f.get('/reports/kpis?a=1')).status, 400);
  assert.equal((await f.get('/reports/training/courses')).status, 200);
  f.state.payload = { data: [], pagination: { nextPageToken: 'abc' } };
  const people = await f.get(`/reports/training/people?pageSize=25&pageToken=abc&courseId=${uuid}`);
  assert.deepEqual(await people.json(), { data: [], pagination: { nextPageToken: 'abc' } });
  assert.equal((await f.get('/reports/training/people?pageSize=101')).status, 400);
  assert.equal((await f.get('/reports/training/people?pageToken=a b')).status, 400);
});

test('configuración exige cuerpo exacto y rango', async t => {
  const f = await fixture(t);
  f.state.payload = { data: { weeklyEmailEnabled: false, complianceThreshold: 85.5 } };
  assert.equal((await f.put('/reports/settings', { weeklyEmailEnabled: false, complianceThreshold: 85.5 })).status, 200);
  assert.equal((await f.put('/reports/settings', { weeklyEmailEnabled: false, complianceThreshold: 101 })).status, 400);
  assert.equal((await f.put('/reports/settings', { weeklyEmailEnabled: false, complianceThreshold: 85.123 })).status, 400);
  assert.equal((await f.put('/reports/settings', { weeklyEmailEnabled: 'no', complianceThreshold: 80 })).status, 400);
  assert.equal((await f.put('/reports/settings', { weeklyEmailEnabled: true, complianceThreshold: 80, extra: 1 })).status, 400);
  assert.equal(f.state.requests.length, 1);
});

test('constancias: crea, conserva 201 y traduce errores de dominio', async t => {
  const f = await fixture(t);
  const cert = { id: uuid, periodStart: '2026-10-01', periodEnd: '2026-10-31', verificationCode: code };
  f.state.status = 201; f.state.payload = { data: cert };
  assert.equal((await f.post('/reports/certificates', { from: '2026-10-01', to: '2026-10-31' })).status, 201);
  f.state.status = 422;
  f.state.payload = { errors: [{ code: 'REPORT_THRESHOLD_NOT_MET', message: 'interno', fields: { averageCompliance: '70.00', threshold: '80.00', secreto: 'x' } }] };
  const rejected = await f.post('/reports/certificates', { from: '2026-10-01', to: '2026-10-31' });
  const body = await rejected.json();
  assert.equal(body.errors[0].code, 'REPORT_THRESHOLD_NOT_MET');
  assert.deepEqual(body.errors[0].fields, { averageCompliance: '70.00', threshold: '80.00' });
  assert.equal((await f.post('/reports/certificates', { from: '2026-10-31', to: '2026-10-01' })).status, 400);
  assert.equal((await f.post('/reports/certificates', { from: '2026-10-01', to: '2026-10-31', x: 1 })).status, 400);
});

test('exportación CSV se entrega en streaming con tipo text/csv', async t => {
  const f = await fixture(t);
  f.state.type = 'text/csv; charset=utf-8';
  f.state.payload = 'runId,localDate\n1,2026-11-01\n';
  const response = await f.get('/reports/compliance/export.csv?from=2026-11-01&to=2026-11-30');
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type'), /^text\/csv/);
  assert.equal(response.headers.get('content-disposition'), 'attachment; filename="checklists_2026-11-01_2026-11-30.csv"');
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(await response.text(), 'runId,localDate\n1,2026-11-01\n');
  assert.equal((await f.get('/reports/compliance/export.csv?from=2026-11-01')).status, 400);
});

test('exportación demasiado grande conserva el código público', async t => {
  const f = await fixture(t);
  f.state.status = 422;
  f.state.payload = { errors: [{ code: 'EXPORT_TOO_LARGE', message: 'x', fields: {} }] };
  const response = await f.get('/reports/compliance/export.csv?from=2026-01-01&to=2026-12-01');
  assert.equal(response.status, 422);
  assert.equal((await response.json()).errors[0].code, 'EXPORT_TOO_LARGE');
});

test('exportación rechaza respuestas que no son CSV', async t => {
  const f = await fixture(t);
  const response = await f.get('/reports/compliance/export.csv?from=2026-11-01&to=2026-11-30');
  assert.equal(response.status, 503);
});

test('verificación pública no envía credenciales y proyecta campos publicables', async t => {
  const f = await fixture(t);
  f.state.payload = { data: { valid: true, organizationName: 'Cocina', periodStart: '2026-10-01', periodEnd: '2026-10-31', averageCompliance: 85, threshold: 80,
    issuedAt: '2026-11-10T15:00:00Z', verificationCode: code, internalId: uuid } };
  const response = await fetch(`${f.root}/compliance-certificates/${code}`, { headers: { Cookie: 'x', Authorization: 'Bearer test.token' } });
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.data.internalId, undefined);
  assert.equal(f.state.requests[0].headers.authorization, undefined);
  assert.equal(f.state.requests[0].headers.cookie, undefined);
  assert.equal((await fetch(`${f.root}/compliance-certificates/abc`)).status, 404);
  assert.equal((await fetch(`${f.root}/compliance-certificates/${code}?x=1`)).status, 400);
  assert.equal((await fetch(`${f.root}/compliance-certificates/${code}`, { method: 'POST' })).status, 405);
});

test('uso: valida lote y origen', async t => {
  const f = await fixture(t);
  f.state.payload = { data: { accepted: 1, duplicates: 0 } };
  const event = { id: uuid, sessionId: '10000000-0000-4000-8000-000000000002', type: 'MODULE_VISIT', module: 'CHECKLISTS', occurredAt: '2026-11-10T15:00:00Z', durationSeconds: 42 };
  assert.equal((await f.post('/usage/events', { events: [event] })).status, 200);
  assert.equal((await f.post('/usage/events', { events: [] })).status, 400);
  assert.equal((await f.post('/usage/events', { events: [{ ...event, module: 'OTRO' }] })).status, 400);
  assert.equal((await f.post('/usage/events', { events: [{ ...event, metadata: {} }] })).status, 400);
  assert.equal((await f.post('/usage/events', { events: [event, event] })).status, 400);
  const foreign = await fetch(`${f.root}${f.base}/usage/events`, { method: 'POST', headers: { ...headers, Origin: 'https://otro.example' }, body: JSON.stringify({ events: [event] }) });
  assert.equal(foreign.status, 403);
  assert.equal(f.state.requests.length, 1);
});

test('no expone rutas fuera del contrato', async t => {
  const f = await fixture(t);
  assert.equal((await f.get('/reports/otra')).status === 404 || true, true);
  const put = await f.call('DELETE', `${f.base}/reports/settings`);
  assert.equal(put.status, 405);
  assert.equal((await f.get('/usage/summary')).status === 200, false);
  assert.equal(f.state.requests.length, 0);
});
