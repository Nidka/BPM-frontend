import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createAppServer } from './server.mjs';

const value = { id: 'assignment', templateId: 'template', templateVersionId: 'version', templateName: 'Limpieza',
  locationId: 'site', locationName: 'Lima', frequency: 'DAILY', dueTime: '08:30', timezone: 'America/Lima', active: true,
  createdAt: '2026-10-24T10:00:00Z', periodStartsAt: '2026-10-24T05:00:00Z', periodEndsAt: '2026-10-25T05:00:00Z',
  run: { id: 'run', status: 'DRAFT', performedBy: 'chef', performedByName: 'Chef Pruebas', createdAt: '2026-10-24T12:00:00Z', privateValue: 'private' }, privateValue: 'private' };
const input = { id: 'assignment', templateId: 'template', locationId: 'site', frequency: 'DAILY', dueTime: '08:30' };

async function fixture(action) {
  const calls = [];
  let payload = { data: [value], pagination: { nextPageToken: 'next', privateValue: 'private' } }, status = 200;
  const backend = http.createServer(async (req, res) => {
    let body = ''; for await (const chunk of req) body += chunk;
    calls.push({ url: req.url, headers: req.headers, body: body && JSON.parse(body), method: req.method });
    res.writeHead(status, { 'Content-Type': 'application/json', 'X-BPM-Session-Expires-At': '2026-10-24T14:00:00Z' });
    res.end(JSON.stringify(payload));
  });
  await new Promise(resolve => backend.listen(0, '127.0.0.1', resolve));
  const app = createAppServer({ apiBaseUrl: `http://127.0.0.1:${backend.address().port}`, allowInsecureForTests: true });
  await new Promise(resolve => app.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${app.address().port}`;
  try { await action({ base: origin + '/backend/api/v1/organizations/org/checklist-assignments', origin, calls,
    reply: (body, code = 200) => { payload = body; status = code; } }); }
  finally { app.closeAllConnections(); backend.closeAllConnections(); await Promise.all([new Promise(r => app.close(r)), new Promise(r => backend.close(r))]); }
}
const headers = { Authorization: 'Bearer test', 'Content-Type': 'application/json' };

test('programación: lista paginada filtra datos privados y no reenvía cookies', () => fixture(async ({ base, calls }) => {
  const response = await fetch(base + '?pageSize=25&query=Lima', { headers: { ...headers, Cookie: 'secret=private' } });
  assert.equal(response.status, 200);
  const data = await response.json();
  assert.equal(data.data[0].locationName, 'Lima');
  assert.equal(data.data[0].privateValue, undefined); assert.equal(data.data[0].run.privateValue, undefined);
  assert.deepEqual(data.pagination, { nextPageToken: 'next' });
  assert.equal(calls[0].headers.cookie, undefined); assert.equal(calls[0].headers.authorization, 'Bearer test');
  assert.equal(calls[0].url, '/api/v1/organizations/org/checklist-assignments?pageSize=25&query=Lima');
}));

test('programación: rechaza métodos, parámetros, origen y campos no autorizados antes de HTTP', () => fixture(async ({ base, calls }) => {
  assert.equal((await fetch(base)).status, 401);
  for (const query of ['?pageSize=101', '?actor=other', '?query=Lima&query=otro', '?pageToken=bad.token'])
    assert.equal((await fetch(base + query, { headers })).status, 400);
  assert.equal((await fetch(base + '/assignment?query=Lima', { headers })).status, 400);
  assert.equal((await fetch(base + '/assignment', { method: 'POST', headers, body: '{}' })).status, 405);
  assert.equal((await fetch(base, { method: 'POST', headers: { ...headers, Origin: 'https://elsewhere.example' }, body: JSON.stringify(input) })).status, 403);
  assert.equal((await fetch(base, { method: 'POST', headers, body: JSON.stringify({ ...input, role: 'ADMIN' }) })).status, 400);
  assert.equal((await fetch(base, { method: 'POST', headers, body: JSON.stringify({ ...input, dueTime: '24:00' }) })).status, 400);
  assert.equal((await fetch(base + '/assignment/deactivate', { method: 'POST', headers, body: '{"active":false}' })).status, 400);
  assert.equal(calls.length, 0);
}));

test('programación: crea, consulta y detiene con confirmación de identidad', () => fixture(async ({ base, reply, calls, origin }) => {
  reply({ data: { ...value, run: null, periodStartsAt: null, periodEndsAt: null } });
  const response = await fetch(base, { method: 'POST', headers: { ...headers, Origin: origin }, body: JSON.stringify(input) });
  assert.equal(response.status, 200); assert.deepEqual(calls[0].body, input);
  reply({ data: value });
  assert.equal((await fetch(base + '/assignment', { headers })).status, 200);
  reply({ data: { ...value, active: false, run: null, periodStartsAt: null, periodEndsAt: null } });
  assert.equal((await fetch(base + '/assignment/deactivate', { method: 'POST', headers, body: '{}' })).status, 200);
  reply({ data: { ...value, id: 'other' } });
  assert.equal((await fetch(base + '/assignment', { headers })).status, 503);
}));

test('programación: metadatos inconsistentes y respuestas ajenas no se aceptan', () => fixture(async ({ base, reply }) => {
  for (const corrupt of [{ ...value, active: false }, { ...value, periodStartsAt: null }, { ...value, periodEndsAt: '2026-10-23T00:00:00Z' },
    { ...value, run: { ...value.run, status: 'SUBMITTED', submittedAt: null } }]) {
    reply({ data: [corrupt], pagination: { nextPageToken: '' } });
    assert.equal((await fetch(base, { headers })).status, 503);
  }
  for (const code of [401, 403, 409, 422, 500]) {
    reply({ errors: [{ code: 'SQL_INTERNAL', message: 'private SQL', fields: { token: 'private' } }] }, code);
    const response = await fetch(base, { headers });
    assert.equal(response.status, code === 500 ? 503 : code);
    assert.doesNotMatch(await response.text(), /private|SQL_INTERNAL|token/);
  }
}));

test('ejecución programada: preserva el conflicto para orientar al mismo registro', () => fixture(async ({ origin, reply }) => {
  const runInput = { id: 'run', locationId: 'site', templateVersionId: 'version', clientOperationId: 'operation', clientCreatedAt: '2026-10-24T12:00:00Z', assignmentId: 'assignment' };
  for (const code of ['ASSIGNMENT_REQUIRED', 'ASSIGNMENT_ALREADY_STARTED']) {
    reply({ errors: [{ code, message: 'private SQL' }] }, 409);
    const response = await fetch(origin + '/backend/api/v1/organizations/org/checklist-runs', { method: 'POST', headers, body: JSON.stringify(runInput) });
    assert.equal(response.status, 409); const body = await response.json(); assert.equal(body.errors[0].code, code);
    assert.doesNotMatch(body.errors[0].message, /private/);
  }
}));
