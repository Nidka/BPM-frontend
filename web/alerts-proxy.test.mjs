import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { alertsRoute, proxyAlerts } from './alerts-proxy.mjs';

const id = 'A0000000-0000-4000-8000-000000000001', lower = id.toLowerCase();
const site = 'b0000000-0000-4000-8000-000000000002';
const date = '2026-11-04T12:34:56.123456Z';
const rule = { id, locationId: null, locationName: '', name: 'Regla propia', type: 'CHECKLIST_MISSING', configuration: { afterLocalTime: '18:00' }, active: false, emailEnabled: false, pushEnabled: true, rowVersion: 1 };
const ruleInput = { id, locationId: null, name: rule.name, type: rule.type, configuration: rule.configuration, active: false, emailEnabled: false, pushEnabled: true };
const alert = { id, type: 'CHECKLIST_MISSING', title: 'Falta completar', message: 'Revisar control', status: 'PENDING', createdAt: date, viewedAt: null, attendedAt: null, locationId: site, locationName: 'Sede', sourceType: 'CHECKLIST_ASSIGNMENT', sourceId: id, sourceParentId: null, sourceAvailable: false, timezone: 'America/Lima' };
const device = { id, platform: 'ANDROID', status: 'ACTIVE', lastSeenAt: date };
const headers = { authorization: 'Bearer fixture.token', host: 'bpm.test', 'content-type': 'application/json' };
async function invoke(t, path, { method = 'GET', body, raw, requestHeaders = {}, payload = { data: alert }, status = 200, upstreamHeaders = {}, upstreamRaw } = {}) {
  const saved = globalThis.fetch, calls = [], forwarded = {};
  globalThis.fetch = async (url, options) => {
    calls.push({ url: String(url), ...options });
    return new Response(upstreamRaw ?? JSON.stringify(payload), { status, headers: { 'Content-Type': 'application/json', ...upstreamHeaders } });
  };
  const request = Readable.from(raw === undefined ? body === undefined ? [] : [Buffer.from(JSON.stringify(body))] : [Buffer.from(raw)]);
  request.method = method; request.headers = { ...headers, ...requestHeaders };
  if (body !== undefined || raw !== undefined) request.headers['content-length'] = String(Buffer.byteLength(raw ?? JSON.stringify(body)));
  let answer;
  try { await proxyAlerts(request, new URL('http://bpm.test/backend/api/v1/organizations/org_1' + path), new URL('https://api.test/'), (code, data) => { answer = { status: code, body: JSON.parse(data) }; }, { setHeader: (key, value) => { forwarded[key] = value; } }); } finally { globalThis.fetch = saved; }
  return { ...answer, calls, forwarded };
}

test('rutas explícitas de reglas, bandeja, acuses y dispositivos', () => {
  for (const path of ['/alert-rules', `/alert-rules/${id}`, '/alerts', '/alerts/unread-count', `/alerts/${id}`, `/alerts/${id}/view`, `/alerts/${id}/attend`, '/notifications/devices', `/notifications/devices/${id}`]) assert.equal(alertsRoute('/backend/api/v1/organizations/org_1' + path), true, path);
  for (const path of ['/alerts/export', `/alerts/${id}/private`, '/notifications/devices/other', '/alert-rules/a', '/notifications/providers']) assert.equal(alertsRoute('/backend/api/v1/organizations/org_1' + path), false, path);
});

test('reglas: proyección, UUID canónico, false obligatorio e idempotencia', async t => {
  const value = await invoke(t, '/alert-rules', { method: 'POST', body: ruleInput, requestHeaders: { 'idempotency-key': 'stable-key-0001' }, payload: { data: { ...rule, organizationId: 'org_1', privateEmail: 'secret', configuration: { ...rule.configuration, privateToken: 'secret' } } }, status: 201 });
  assert.equal(value.status, 201); assert.equal(value.body.data.id, lower); assert.equal(value.body.data.active, false); assert.equal(JSON.stringify(value.body).includes('secret'), false);
  assert.equal(value.calls[0].headers['Idempotency-Key'], 'stable-key-0001'); assert.equal(JSON.parse(value.calls[0].body).id, lower);
});

test('bandeja: proyección privada, filtros y paginación', async t => {
  const value = await invoke(t, '/alerts?state=UNREAD&query=falta&pageSize=2&pageToken=abc_DEF', { payload: { data: [{ ...alert, pushToken: 'secret', recipientUserId: 'usuario_😀', organizationId: 'org_1' }], pagination: { nextPageToken: 'next', secret: 'hidden' } } });
  assert.equal(value.status, 200); assert.deepEqual(value.body.pagination, { nextPageToken: 'next' }); assert.equal(value.body.data[0].sourceAvailable, false); assert.equal(value.body.data[0].sourceId, lower); assert.equal(JSON.stringify(value.body).includes('secret'), false);
  assert.equal(new URL(value.calls[0].url).searchParams.get('state'), 'UNREAD');
});

test('reglas: configuración cerrada y confirmación de versión', async t => {
  const { id: unused, ...input } = ruleInput;
  const success = await invoke(t, `/alert-rules/${id}`, { method: 'PUT', body: { ...input, rowVersion: 1 }, requestHeaders: { 'idempotency-key': 'stable-key-0001' }, payload: { data: { ...rule, rowVersion: 2 } } });
  assert.equal(success.status, 200); assert.equal(new URL(success.calls[0].url).pathname.endsWith(lower), true);
  for (const output of [{ ...rule }, { ...rule, rowVersion: 2, active: true }, { ...rule, rowVersion: 2, type: 'TRAINING_ASSIGNED', configuration: {} }, { ...rule, rowVersion: 2, locationId: site }]) {
    const value = await invoke(t, `/alert-rules/${id}`, { method: 'PUT', body: { ...input, rowVersion: 1 }, requestHeaders: { 'idempotency-key': 'stable-key-0001' }, payload: { data: output } });
    assert.equal(value.status, 503);
  }
});

test('acuses intrínsecos sin Idempotency-Key, contador y dispositivos sin secretos', async t => {
  for (const action of ['view', 'attend']) {
    const value = await invoke(t, `/alerts/${id}/${action}`, { method: 'POST', body: {}, payload: { data: { ...alert, viewedAt: date, status: action === 'view' ? 'VIEWED' : 'ATTENDED', attendedAt: action === 'attend' ? date : null } } });
    assert.equal(value.status, 200); assert.equal(value.calls[0].headers['Idempotency-Key'], undefined);
  }
  assert.deepEqual((await invoke(t, '/alerts/unread-count', { payload: { count: 4, private: 'secret' } })).body, { count: 4 });
  const value = await invoke(t, '/notifications/devices', { method: 'POST', body: { id, installationId: site, platform: 'ANDROID', pushToken: 'private-token' }, payload: { data: { ...device, pushToken: 'private-token', installationId: site, userId: 'usuario_😀' } } });
  assert.equal(value.status, 200); assert.deepEqual(value.body.data, { ...device, id: lower }); assert.equal(JSON.parse(value.calls[0].body).pushToken, 'private-token');
  assert.deepEqual((await invoke(t, `/notifications/devices/${id}`, { method: 'DELETE', payload: { data: { id, status: 'INACTIVE', pushToken: 'private-token' } } })).body, { data: { id: lower, status: 'INACTIVE' } });
});

test('rechaza entradas desconocidas, duplicadas, UTF8 corrupto, límites, origen y métodos', async t => {
  for (const body of [{ ...ruleInput, active: undefined }, { ...ruleInput, emailEnabled: 0 }, { ...ruleInput, pushEnabled: 'false' }, { ...ruleInput, actor: 'other' }, { ...ruleInput, configuration: { afterLocalTime: '25:00' } }, { ...ruleInput, configuration: { afterLocalTime: '18:00', secret: true } }]) {
    const value = await invoke(t, '/alert-rules', { method: 'POST', body, requestHeaders: { 'idempotency-key': 'stable-key-0001' } }); assert.equal(value.status, 400); assert.equal(value.calls.length, 0);
  }
  for (const raw of ['{"id":1,"id":2}', '{"x":{"a":1,"\\u0061":2}}', Buffer.from([123, 34, 120, 34, 58, 34, 255, 34, 125]), ' '.repeat(512 * 1024 + 1)]) {
    const value = await invoke(t, '/alert-rules', { method: 'POST', raw, requestHeaders: { 'idempotency-key': 'stable-key-0001' } }); assert.equal(value.status, 400); assert.equal(value.calls.length, 0);
  }
  assert.equal((await invoke(t, '/alert-rules', { method: 'POST', body: ruleInput })).status, 400);
  assert.equal((await invoke(t, `/alerts/${id}/view`, { method: 'POST', body: { actor: 'other' } })).status, 400);
  assert.equal((await invoke(t, '/alerts', { method: 'GET', body: {} })).status, 400);
  assert.equal((await invoke(t, '/notifications/devices', { method: 'GET' })).status, 405);
  assert.equal((await invoke(t, '/notifications/devices', { method: 'POST', body: {}, requestHeaders: { origin: 'https://evil.test' } })).status, 403);
  assert.equal((await invoke(t, '/notifications/devices', { method: 'POST', body: {}, requestHeaders: { 'content-type': 'text/plain' } })).status, 415);
});

test('rechaza filtros y autenticación antes de consultar', async t => {
  for (const path of ['/alerts?state=OTHER', '/alerts?state=ALL&state=ALL', '/alerts?query=x&query=y', '/alerts?pageSize=101', '/alerts?pageToken=bad%3D', '/alerts?locationId=' + site, '/alert-rules?locationId=x', '/alerts?actor=someone', `/alerts/${id}?query=x`, '/alerts/unread-count?pageSize=1']) {
    const value = await invoke(t, path); assert.equal(value.status, 400, path); assert.equal(value.calls.length, 0);
  }
  for (const authorization of [undefined, 'Basic fixture', 'Bearer bad token', 'Bearer ' + 'a'.repeat(8192)]) { const value = await invoke(t, '/alerts', { requestHeaders: { authorization } }); assert.equal(value.status, 401); assert.equal(value.calls.length, 0); }
});

test('rechaza respuestas malformadas, duplicados, contexto ajeno y confirmaciones falsas', async t => {
  for (const data of [{ ...alert, id: site }, { ...alert, sourceAvailable: 'false' }, { ...alert, createdAt: '2026-02-30T10:00:00Z' }, { ...alert, locationId: 'bad' }, { ...alert, sourceParentId: 'bad' }, { ...alert, organizationId: 'foreign' }, { ...alert, title: null }, { ...alert, type: 'OTHER' }, { ...alert, status: 'OTHER' }, { ...alert, sourceType: 'OTHER' }]) assert.equal((await invoke(t, `/alerts/${id}`, { payload: { data } })).status, 503);
  for (const payload of [{ data: [alert, { ...alert, id: lower }], pagination: { nextPageToken: '' } }, { data: [alert] }, { data: [], pagination: { nextPageToken: 'bad=' } }, { data: [alert, { ...alert, id: site }], pagination: { nextPageToken: '' } }]) assert.equal((await invoke(t, '/alerts?pageSize=1', { payload })).status, 503);
  assert.equal((await invoke(t, '/alerts?state=ATTENDED', { payload: { data: [alert], pagination: { nextPageToken: '' } } })).status, 503);
  assert.equal((await invoke(t, `/alerts/${id}/view`, { method: 'POST', body: {}, payload: { data: alert } })).status, 503);
  for (const count of [-1, 1.5, '0', null]) assert.equal((await invoke(t, '/alerts/unread-count', { payload: { count } })).status, 503);
  for (const raw of ['{"data":{},"data":{}}', ' '.repeat(4 * 1024 * 1024 + 1), Buffer.from([255])]) assert.equal((await invoke(t, `/alerts/${id}`, { upstreamRaw: raw })).status, 503);
});

test('errores sanitizados y encabezados de sesión acotados', async t => {
  for (const [status, code] of [[400, 'INVALID_PAGINATION'], [401, 'SESSION_EXPIRED'], [403, 'ACCESS_DENIED'], [404, 'NOT_FOUND'], [409, 'RESOURCE_CONFLICT'], [422, 'VALIDATION_ERROR'], [429, 'RATE_LIMITED'], [500, 'INTERNAL_ERROR']]) {
    const value = await invoke(t, '/alerts', { status, payload: { errors: [{ code, message: 'SQL private-token', fields: { pushToken: 'private-token' } }] }, upstreamHeaders: { 'X-BPM-Session-Expires-At': '1793793600', 'X-BPM-Session-Idle-Expires-At': 'bad', 'Set-Cookie': 'private-token' } });
    assert.equal(value.status, status); assert.equal(value.body.errors[0].code, code); assert.equal(JSON.stringify(value.body).includes('private-token'), false); assert.deepEqual(value.forwarded, { 'X-BPM-Session-Expires-At': '1793793600' });
  }
  assert.equal((await invoke(t, '/alerts', { status: 403, payload: { errors: 'bad' } })).status, 503);
});


test('endurece tipo MIME, identificadores vacíos, nombre y confirmación semántica', async t => {
  assert.equal((await invoke(t, `/alerts/${id}`, { upstreamHeaders: { 'Content-Type': 'text/html' } })).status, 503);
  assert.equal((await invoke(t, `/alerts/00000000-0000-0000-0000-000000000000`)).calls.length, 0);
  assert.equal((await invoke(t, '/alert-rules', { method: 'POST', body: { ...ruleInput, name: 'Regla\nprivada' }, requestHeaders: { 'idempotency-key': 'stable-key-0001' } })).status, 400);
  assert.equal((await invoke(t, `/alerts/${id}/view`, { method: 'POST', body: {}, payload: { data: { ...alert, viewedAt: date } } })).status, 503);
  assert.equal((await invoke(t, `/alerts/${id}`, { payload: { data: { ...alert, sourceType: 'TRAINING_ASSIGNMENT' } } })).status, 503);
});

test('configuraciones por tipo, sede exacta y fuente de organización textual', async t => {
  for (const [type, configuration] of [['TEMPERATURE_DANGER', { minimumTemperatureCelsius: -2.45, maximumTemperatureCelsius: 8.01 }], ['ORGANIZATION_INACTIVE', { inactivityDays: 30 }], ['TRAINING_ASSIGNED', {}]]) {
    const value = await invoke(t, '/alert-rules', { method: 'POST', body: { ...ruleInput, type, configuration }, requestHeaders: { 'idempotency-key': 'stable-key-0001' }, payload: { data: { ...rule, type, configuration } }, status: 201 }); assert.equal(value.status, 201, type);
  }
  for (const [type, configuration] of [['TEMPERATURE_DANGER', { minimumTemperatureCelsius: 8, maximumTemperatureCelsius: 8 }], ['TEMPERATURE_DANGER', { minimumTemperatureCelsius: 0.001, maximumTemperatureCelsius: 8 }], ['TEMPERATURE_DANGER', { minimumTemperatureCelsius: -10000, maximumTemperatureCelsius: 8 }], ['ORGANIZATION_INACTIVE', { inactivityDays: 0 }], ['ORGANIZATION_INACTIVE', { inactivityDays: 1.5 }], ['TRAINING_ASSIGNED', { active: true }]]) assert.equal((await invoke(t, '/alert-rules', { method: 'POST', body: { ...ruleInput, type, configuration }, requestHeaders: { 'idempotency-key': 'stable-key-0001' } })).status, 400);
  assert.equal((await invoke(t, `/alert-rules?locationId=${site}`, { payload: { data: [rule], pagination: { nextPageToken: '' } } })).status, 503);
  const value = await invoke(t, `/alerts/${id}`, { payload: { data: { ...alert, type: 'ORGANIZATION_INACTIVE', sourceType: 'ORGANIZATION', sourceId: 'org_1', locationId: null, timezone: '', recipientUserId: '😀'.repeat(128) } } }); assert.equal(value.status, 200); assert.equal(value.body.data.sourceId, 'org_1');
  for (const recipientUserId of ['😀'.repeat(129), 'bad\nuser', '\ud800']) assert.equal((await invoke(t, `/alerts/${id}`, { payload: { data: { ...alert, recipientUserId } } })).status, 503);
});

test('servidor: registra las rutas y responde al cuerpo excesivo sin cerrar la conexión', async t => {
  const { default: http } = await import('node:http');
  const { once } = await import('node:events');
  const { createAppServer } = await import('./server.mjs');
  let calls = 0;
  const backend = http.createServer((req, res) => { calls++; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(req.url.endsWith('unread-count') ? { count: 3 } : { data: device })); });
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
  const app = createAppServer({ distDir: '/private/tmp', apiBaseUrl: `http://127.0.0.1:${backend.address().port}`, allowInsecureForTests: true });
  app.listen(0, '127.0.0.1'); await once(app, 'listening');
  t.after(async () => { app.closeAllConnections(); backend.closeAllConnections(); await Promise.all([new Promise(resolve => app.close(resolve)), new Promise(resolve => backend.close(resolve))]); });
  const base = `http://127.0.0.1:${app.address().port}/backend/api/v1/organizations/org_1`;
  const get = await fetch(base + '/alerts/unread-count', { headers: { Authorization: 'Bearer fixture.token' } });
  assert.equal(get.status, 200); assert.deepEqual(await get.json(), { count: 3 });
  const post = await fetch(base + '/notifications/devices', { method: 'POST', headers, body: JSON.stringify({ id, installationId: site, platform: 'ANDROID', pushToken: 'fixture-private' }) });
  assert.equal(post.status, 200);
  const response = await fetch(base + '/alert-rules', { method: 'POST', headers: { ...headers, 'idempotency-key': 'stable-key-0001' }, body: JSON.stringify({ ...ruleInput, name: 'x'.repeat(600000) }) });
  assert.equal(response.status, 400); assert.equal(calls, 2);
});
