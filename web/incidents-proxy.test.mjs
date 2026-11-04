import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';

const org = 'tenant_incidents';
const id = '10000000-0000-4000-8000-000000000001';
const site = '20000000-0000-4000-8000-000000000001';
const catalogId = '30000000-0000-4000-8000-000000000001';
const actionId = '40000000-0000-4000-8000-000000000001';
const operationId = '50000000-0000-4000-8000-000000000001';
const stamp = '2026-01-01T12:00:00Z';
const headers = { Authorization: 'Bearer test.token', 'Content-Type': 'application/json', 'Idempotency-Key': 'operation-unique', Cookie: 'private-cookie', 'X-Role': 'ADMIN' };
const catalogInput = { id: catalogId, code: 'CRITERIO_INTERNO', name: 'Criterio propio', area: 'Cocina', normativeCriterion: 'Texto proporcionado por la empresa', riskLevel: 'HIGH', evidenceRequired: true };
const catalog = { ...catalogInput, versionNumber: 1 };
const summary = { id, locationId: site, catalogId, name: catalog.name, area: catalog.area, riskLevel: 'HIGH', status: 'OPEN', description: 'Observación de cocina', reportedByName: 'Persona', createdAt: stamp, rowVersion: 1 };
const detail = { ...summary, reportedBy: 'firebase-user', catalogVersion: 1, normativeCriterion: catalog.normativeCriterion, evidenceRequired: true, actions: [], allowedActions: ['ASSIGN'], beforeCount: 0, afterCount: 0 };
const report = { id, locationId: site, catalogId, description: summary.description, clientOperationId: operationId, clientCreatedAt: stamp };
const assign = { operationId, rowVersion: 1, actionType: 'ASSIGN', actionId, responsibleUserId: 'firebase-worker', description: 'Corregir observación', dueAt: '2099-01-01T12:00:00Z' };
const action = { id: actionId, responsibleUserId: 'firebase-worker', responsibleName: 'Trabajador', description: assign.description, dueAt: assign.dueAt, status: 'PENDING', resolutionNote: '' };

async function fixture(t) {
  const state = { status: 200, payload: { data: [catalog], pagination: { nextPageToken: '' } }, requests: [], redirect: false };
  const backend = http.createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    state.requests.push({ path: req.url, method: req.method, headers: req.headers, body: Buffer.concat(chunks).toString('utf8') });
    res.writeHead(state.redirect ? 302 : state.status, { 'Content-Type': 'application/json', 'Set-Cookie': 'private=secret', 'X-BPM-Session-Expires-At': '1900000000', ...(state.redirect ? { Location: '/private' } : {}) });
    res.end(typeof state.payload === 'string' ? state.payload : JSON.stringify(state.payload));
  });
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
  const app = createAppServer({ apiBaseUrl: `http://127.0.0.1:${backend.address().port}`, allowInsecureForTests: true });
  app.listen(0, '127.0.0.1'); await once(app, 'listening');
  t.after(async () => { app.closeAllConnections(); backend.closeAllConnections(); await Promise.all([new Promise(r => app.close(r)), new Promise(r => backend.close(r))]); });
  const base = `http://127.0.0.1:${app.address().port}/backend/api/v1/organizations/${org}`;
  return { state, base, get: path => fetch(base + path, { headers }), write: (path, body, extra = {}) => fetch(base + path, { method: 'POST', headers: { ...headers, ...extra }, body: JSON.stringify(body) }) };
}

test('incidencias: catálogo cerrado proyecta versión y conserva sesión sin cookies', async t => {
  const { state, get } = await fixture(t);
  state.payload.data = [{ ...catalog, organizationId: org, requestHash: 'private', responseBody: 'private' }];
  const response = await get('/incident-catalog?pageSize=10&query=Cocina');
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { data: [catalog], pagination: { nextPageToken: '' } });
  assert.equal(response.headers.get('x-bpm-session-expires-at'), '1900000000');
  assert.equal(response.headers.get('set-cookie'), null);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(state.requests[0].path, `/api/v1/organizations/${org}/incident-catalog?pageSize=10&query=Cocina`);
  assert.equal(state.requests[0].headers.cookie, undefined);
  assert.equal(state.requests[0].headers['x-role'], undefined);
});

test('incidencias: listas, detalle, responsables e historial tienen DTO públicos y contexto', async t => {
  const { state, get } = await fixture(t);
  state.payload = { data: [summary], pagination: { nextPageToken: 'next_page' } };
  assert.deepEqual(await (await get(`/incidents?locationId=${site}&status=OPEN`)).json(), state.payload);
  state.payload = { data: { ...detail, requestHash: 'private' } };
  assert.deepEqual(await (await get(`/incidents/${id}`)).json(), { data: detail });
  state.payload = { data: [{ id: 'firebase-worker', displayName: 'Trabajador', role: 'WORKER', email: 'private@example.com' }], pagination: { nextPageToken: '' } };
  assert.deepEqual(await (await get(`/incidents/${id}/assignees?query=Persona`)).json(), { data: [{ id: 'firebase-worker', displayName: 'Trabajador', role: 'WORKER' }], pagination: { nextPageToken: '' } });
  const event = { id: operationId, operationId, fromStatus: 'OPEN', toStatus: 'ASSIGNED', actorName: 'Persona', note: '', createdAt: stamp };
  state.payload = { data: [{ ...event, requestHash: 'private', responseBody: 'private' }], pagination: { nextPageToken: '' } };
  assert.deepEqual(await (await get(`/incidents/${id}/history`)).json(), { data: [event], pagination: { nextPageToken: '' } });
  for (const payload of [{ data: [{ ...summary, locationId: catalogId }], pagination: { nextPageToken: '' } }, { data: [summary] }]) {
    state.payload = payload; assert.equal((await get(`/incidents?locationId=${site}`)).status, 503);
  }
});

test('incidencias: creación, versiones y comandos confirman el destino y la carga', async t => {
  const { state, write } = await fixture(t);
  state.payload = { data: catalog };
  assert.equal((await write('/incident-catalog', catalogInput)).status, 200);
  assert.deepEqual(JSON.parse(state.requests.at(-1).body), catalogInput);
  const { code, ...version } = catalogInput;
  state.payload = { data: { ...catalog, versionNumber: 2 } };
  assert.equal((await write(`/incident-catalog/${id}/versions`, { ...version, expectedVersion: 1 })).status, 200);
  state.payload = { data: detail };
  assert.equal((await write('/incidents', report)).status, 200);
  state.payload = { data: { ...detail, rowVersion: 2, status: 'ASSIGNED', actions: [action], allowedActions: [] } };
  assert.equal((await write(`/incidents/${id}/actions`, assign)).status, 200);
  assert.equal(state.requests.at(-1).headers['idempotency-key'], headers['Idempotency-Key']);
  state.payload.data.id = catalogId;
  assert.equal((await write(`/incidents/${id}/actions`, assign)).status, 503);
  state.payload = { data: { ...catalog, evidenceRequired: false } };
  assert.equal((await write('/incident-catalog', catalogInput)).status, 503);
});

test('incidencias: rechaza filtros, métodos y campos desconocidos antes del backend', async t => {
  const { state, base, get, write } = await fixture(t);
  for (const path of ['/incident-catalog?status=OPEN', '/incident-catalog?pageSize=0', '/incident-catalog?pageSize=2&pageSize=3', '/incident-catalog?pageToken=bad%3D', `/incidents?locationId=${site}&status=UNKNOWN`, `/incidents?locationId=${site}&actor=other`, `/incidents/${id}?query=x`, `/incidents/${id}/history?query=x`]) assert.equal((await get(path)).status, 400, path);
  assert.equal((await fetch(base + '/incident-catalog', { headers, method: 'DELETE' })).status, 405);
  assert.equal((await get(`/incident-catalog/${id}`)).status, 404);
  assert.equal((await get(`/incidents/${id}/private`)).status, 404);
  assert.equal((await fetch(base + '/incident-catalog')).status, 401);
  for (const input of [{ ...catalogInput, actor: 'private' }, { ...catalogInput, evidenceRequired: undefined }, { ...catalogInput, code: 'lowercase' }, { ...catalogInput, evidenceRequired: 'true' }]) assert.equal((await write('/incident-catalog', input)).status, 400);
  for (const input of [{ ...assign, note: 'extra' }, { operationId, rowVersion: 1, actionType: 'START', actionId }, { operationId, rowVersion: 1, actionType: 'RESOLVE' }, { ...assign, dueAt: 'invalid' }, { ...assign, rowVersion: 0 }]) assert.equal((await write(`/incidents/${id}/actions`, input)).status, 400);
  assert.equal((await write('/incidents', { ...report, clientCreatedAt: '2099-01-01T00:00:00Z' })).status, 400);
  assert.equal((await write('/incidents', report, { Origin: 'https://foreign.example' })).status, 403);
  assert.equal((await write('/incidents', report, { 'Idempotency-Key': 'short' })).status, 400);
  assert.equal(state.requests.length, 0);
});

test('incidencias: JSON UTF8 y límites, errores sanitizados y redirecciones cerradas', async t => {
  const { state, base, get, write } = await fixture(t);
  assert.equal((await fetch(base + '/incidents', { method: 'POST', headers, body: Buffer.from([0x7b, 0x22, 0x61, 0x22, 0x3a, 0x22, 0xff, 0x22, 0x7d]) })).status, 400);
  assert.equal((await write('/incidents', { ...report, description: 'á'.repeat(270000) })).status, 400);
  assert.equal(state.requests.length, 0);
  for (const [status, code] of [[400, 'INVALID_PAGINATION'], [401, 'SESSION_EXPIRED'], [403, 'ACCESS_DENIED'], [404, 'NOT_FOUND'], [409, 'RESOURCE_CONFLICT'], [422, 'VALIDATION_ERROR'], [429, 'RATE_LIMITED'], [500, 'INTERNAL_ERROR']]) {
    state.status = status; state.payload = { errors: [{ code, message: 'SQL private.secret', fields: { description: 'SQL private', actor: 'private' } }] };
    const response = await get('/incident-catalog'); assert.equal(response.status, status);
    const body = await response.json(); assert.equal(body.errors[0].code, code); assert.equal(JSON.stringify(body).includes('private'), false);
  }
  state.status = 200; state.payload = 'x'.repeat(4 * 1024 * 1024 + 1);
  assert.equal((await get('/incident-catalog')).status, 503);
  state.redirect = true;
  assert.equal((await get('/incident-catalog')).status, 503);
});

test('incidencias: rechaza UTF8 inválido incluso dentro de un campo permitido', async t => {
  const { state, base } = await fixture(t);
  state.payload = { data: { ...detail, description: 'Observación �' } };
  const raw = Buffer.from(JSON.stringify({ ...report, description: 'Observación SENTINEL' }));
  const start = raw.indexOf('SENTINEL');
  const invalid = Buffer.concat([raw.subarray(0, start), Buffer.from([255]), raw.subarray(start + 8)]);
  assert.equal((await fetch(base + '/incidents', { method: 'POST', headers, body: invalid })).status, 400);
  assert.equal(state.requests.length, 0);
});

test('incidencias: comandos de atención y revisión preservan operación y exigen confirmación de versión', async t => {
  const { state, write } = await fixture(t);
  for (const [actionType, status, actionStatus] of [['START', 'IN_PROGRESS', 'IN_PROGRESS'], ['RESOLVE', 'RESOLVED', 'RESOLVED'], ['VERIFY', 'CLOSED', 'VERIFIED'], ['RETURN', 'IN_PROGRESS', 'IN_PROGRESS']]) {
    const input = { operationId, rowVersion: 4, actionType, ...(actionType === 'START' ? {} : { note: 'Explicación humana' }) };
    const output = { ...detail, rowVersion: 5, status, actions: [{ ...action, status: actionStatus, resolutionNote: actionType === 'RESOLVE' ? input.note : '' }], allowedActions: [] };
    state.payload = { data: output };
    assert.equal((await write(`/incidents/${id}/actions`, input)).status, 200, actionType);
    assert.deepEqual(JSON.parse(state.requests.at(-1).body), input);
    state.payload.data.rowVersion = 4;
    assert.equal((await write(`/incidents/${id}/actions`, input)).status, 503);
  }
});

test('incidencias: detalle conserva todas las acciones legadas dentro del límite de respuesta', async t => {
  const { state, get } = await fixture(t);
  const actions = Array.from({ length: 101 }, (_, index) => ({ ...action, id: `40000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`, status: 'CANCELLED' }));
  state.payload = { data: { ...detail, actions, allowedActions: [] } };
  const response = await get(`/incidents/${id}`);
  assert.equal(response.status, 200); assert.equal((await response.json()).data.actions.length, 101);
});

test('incidencias: confirmación del plazo conserva precisión de microsegundos', async t => {
  const { state, write } = await fixture(t);
  const input = { ...assign, dueAt: '2099-01-01T12:00:00.123456Z' };
  state.payload = { data: { ...detail, rowVersion: 2, status: 'ASSIGNED', actions: [{ ...action, dueAt: '2099-01-01T12:00:00.123457Z' }], allowedActions: [] } };
  assert.equal((await write(`/incidents/${id}/actions`, input)).status, 503);
  state.payload.data.actions[0].dueAt = '2099-01-01T07:00:00.123456-05:00';
  assert.equal((await write(`/incidents/${id}/actions`, input)).status, 200);
});

test('incidencias: lee y filtra VERIFIED legado sin ofrecer mutaciones', async t => {
  const { state, get } = await fixture(t);
  state.payload = { data: [{ ...summary, status: 'VERIFIED' }], pagination: { nextPageToken: '' } };
  const list = await get(`/incidents?locationId=${site}&status=VERIFIED`);
  assert.equal(list.status, 200); assert.equal((await list.json()).data[0].status, 'VERIFIED');
  state.payload = { data: { ...detail, status: 'VERIFIED', allowedActions: [] } };
  const response = await get(`/incidents/${id}`);
  assert.equal(response.status, 200); assert.deepEqual((await response.json()).data.allowedActions, []);
});

test('incidencias: replay de ASSIGN conserva cuerpo aunque el plazo ya haya pasado', async t => {
  const { state, write } = await fixture(t);
  const input = { ...assign, dueAt: stamp };
  state.payload = { data: { ...detail, rowVersion: 2, status: 'ASSIGNED', actions: [{ ...action, dueAt: stamp }], allowedActions: [] } };
  assert.equal((await write(`/incidents/${id}/actions`, input)).status, 200);
  assert.deepEqual(JSON.parse(state.requests.at(-1).body), input);
});
