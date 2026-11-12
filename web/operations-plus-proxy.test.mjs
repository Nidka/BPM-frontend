import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';

const org = '10000000-0000-4000-8000-000000000001';
const site = '20000000-0000-4000-8000-000000000001';
const id = '30000000-0000-4000-8000-000000000001';
const lotId = '50000000-0000-4000-8000-000000000001';
const other = '40000000-0000-4000-8000-000000000001';
const stamp = '2026-11-10T15:00:00Z';
const shift = { id, locationId: site, name: 'Mañana', startTime: '06:00', endTime: '14:00', timezone: 'America/Lima', crossesMidnight: false, active: true, openNow: true, rowVersion: 1, createdAt: stamp };
const shiftInput = { id, locationId: site, name: 'Mañana', startTime: '06:00', endTime: '14:00' };
const movementInput = { id, locationId: site, lotId, type: 'DISCARD', quantity: '0.5', reason: 'EXPIRED', clientOperationId: id, clientCreatedAt: stamp };
const movement = { ...movementInput, note: '', productName: 'Leche', unit: 'L', batchCode: 'L-1', balanceAfter: '0.625', performedBy: 'firebase-user', performedByName: 'Ana', createdAt: stamp };
const consumption = { id, locationId: site, lotId, type: 'CONSUMPTION', quantity: '1.250', clientOperationId: id, clientCreatedAt: stamp };
const headers = { Authorization: 'Bearer test.token', 'Content-Type': 'application/json', 'Idempotency-Key': 'operation-unique', Cookie: 'private-cookie', 'X-Role': 'SUPER_ADMIN' };

async function fixture(t) {
  const requests = [];
  const state = { status: 200, payload: { data: [shift], pagination: { nextPageToken: 'next_page' } } };
  const backend = http.createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    requests.push({ path: req.url, method: req.method, headers: req.headers, body: Buffer.concat(chunks).toString('utf8') });
    res.writeHead(state.status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(state.payload));
  });
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
  const app = createAppServer({ apiBaseUrl: `http://127.0.0.1:${backend.address().port}`, allowInsecureForTests: true });
  app.listen(0, '127.0.0.1'); await once(app, 'listening');
  t.after(async () => { app.closeAllConnections(); backend.closeAllConnections(); await Promise.all([new Promise(r => app.close(r)), new Promise(r => backend.close(r))]); });
  const base = `http://127.0.0.1:${app.address().port}/backend/api/v1/organizations/${org}`;
  const call = (path, method, body, extra = {}) => fetch(base + path, { method, headers: { ...headers, ...extra }, body: body === undefined ? undefined : JSON.stringify(body) });
  return { state, requests, call, base };
}

test('turnos: lista por sede con filtro de estado y proyección pública', async t => {
  const { state, requests, call } = await fixture(t);
  state.payload = { data: [{ ...shift, privateData: 'secret' }], pagination: { nextPageToken: 'next_page' } };
  const response = await call(`/shifts?locationId=${site}&status=ALL&pageSize=10`, 'GET');
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.deepEqual(body.data[0], shift);
  assert.equal(JSON.stringify(body).includes('secret'), false);
  const upstream = new URL(requests[0].path, 'http://local');
  assert.equal(upstream.searchParams.get('status'), 'ALL');
  assert.equal(upstream.searchParams.get('locationId'), site);
  assert.equal(requests[0].headers.cookie, undefined);
  for (const bad of [`/shifts?locationId=${site}&status=DELETED`, `/shifts?locationId=${site}&status=ALL&status=ACTIVE`, `/shifts?locationId=${site}&lotId=${lotId}`, '/shifts'])
    assert.equal((await call(bad, 'GET')).status, 400, bad);
  assert.equal(requests.length, 1);
});

test('turnos: alta, edición y desactivación con JSON estricto e idempotencia', async t => {
  const { state, requests, call } = await fixture(t);
  state.payload = { data: shift };
  let response = await call('/shifts', 'POST', shiftInput);
  assert.equal(response.status, 201);
  assert.deepEqual((await response.json()).data, shift);
  assert.equal(requests.at(-1).headers['idempotency-key'], 'operation-unique');
  state.payload = { data: { ...shift, name: 'Mañana B', rowVersion: 2 } };
  response = await call(`/shifts/${id}`, 'PUT', { name: 'Mañana B', startTime: '06:00', endTime: '14:00', rowVersion: 1 });
  assert.equal(response.status, 200);
  assert.equal((await response.json()).data.rowVersion, 2);
  state.payload = { data: { ...shift, active: false, openNow: false, rowVersion: 3 } };
  response = await call(`/shifts/${id}/deactivate`, 'POST', { rowVersion: 2 });
  assert.equal(response.status, 200);
  assert.equal((await response.json()).data.active, false);
  const before = requests.length;
  for (const [path, method, body] of [
    ['/shifts', 'POST', { ...shiftInput, rol: 'ADMIN' }], ['/shifts', 'POST', { ...shiftInput, startTime: '6:00' }], ['/shifts', 'POST', { ...shiftInput, name: 'A' }],
    ['/shifts', 'POST', { ...shiftInput, endTime: '24:00' }], [`/shifts/${id}`, 'PUT', { name: 'Tarde', startTime: '14:00', endTime: '22:00' }],
    [`/shifts/${id}`, 'PUT', { name: 'Tarde', startTime: '14:00', endTime: '22:00', rowVersion: 0 }], [`/shifts/${id}/deactivate`, 'POST', {}], [`/shifts/${id}/deactivate`, 'POST', { rowVersion: 1, active: false }],
  ]) assert.equal((await call(path, method, body)).status, 400, path + JSON.stringify(body));
  assert.equal((await call(`/shifts/${id}`, 'PUT', { name: 'Tarde', startTime: '14:00', endTime: '22:00', rowVersion: 1 }, { 'Idempotency-Key': 'short' })).status, 400);
  assert.equal((await call(`/shifts/${id}`, 'DELETE')).status, 405);
  assert.equal((await call(`/shifts/${id}/deactivate`, 'PUT', { rowVersion: 1 })).status, 405);
  assert.equal(requests.length, before);
});

test('turnos: conserva SHIFT_IN_USE y descarta mensajes del proveedor', async t => {
  const { state, call } = await fixture(t);
  state.status = 409;
  state.payload = { errors: [{ code: 'SHIFT_IN_USE', message: 'SQL secreto', fields: { debug: 'x' } }] };
  const response = await call(`/shifts/${id}/deactivate`, 'POST', { rowVersion: 1 });
  assert.equal(response.status, 409);
  const body = await response.json();
  assert.equal(body.errors[0].code, 'SHIFT_IN_USE');
  assert.equal(JSON.stringify(body).includes('secreto'), false);
  state.payload = { errors: [{ code: 'CUALQUIER_COSA', message: 'x' }] };
  assert.equal((await (await call(`/shifts/${id}/deactivate`, 'POST', { rowVersion: 1 })).json()).errors[0].code, 'RESOURCE_CONFLICT');
});

test('turnos: una respuesta que no confirma la operación se trata como no disponible', async t => {
  const { state, call } = await fixture(t);
  state.payload = { data: { ...shift, name: 'Otro nombre' } };
  assert.equal((await call('/shifts', 'POST', shiftInput)).status, 503);
  state.payload = { data: { ...shift, active: true } };
  assert.equal((await call(`/shifts/${id}/deactivate`, 'POST', { rowVersion: 1 })).status, 503);
  state.payload = { data: { ...shift, startTime: '25:00' } };
  assert.equal((await call(`/shifts/${id}`, 'GET')).status, 503);
});

test('movimientos de stock: alta de descarte y consumo, historial filtrado', async t => {
  const { state, requests, call } = await fixture(t);
  state.payload = { data: movement };
  let response = await call('/stock-movements', 'POST', movementInput);
  assert.equal(response.status, 201);
  assert.equal((await response.json()).data.balanceAfter, '0.625');
  state.payload = { data: { ...movement, ...consumption, reason: '', balanceAfter: '0.000' } };
  response = await call('/stock-movements', 'POST', consumption);
  assert.equal(response.status, 201);
  assert.equal((await response.json()).data.balanceAfter, '0.000');
  state.payload = { data: [movement], pagination: { nextPageToken: '' } };
  response = await call(`/stock-movements?locationId=${site}&lotId=${lotId}&type=DISCARD&query=lote&pageSize=20`, 'GET');
  assert.equal(response.status, 200);
  const upstream = new URL(requests.at(-1).path, 'http://local');
  assert.equal(upstream.searchParams.get('lotId'), lotId);
  assert.equal(upstream.searchParams.get('type'), 'DISCARD');
  assert.equal(upstream.searchParams.get('query'), 'lote');
  state.payload = { data: movement };
  assert.equal((await call(`/stock-movements/${id}`, 'GET')).status, 200);
  for (const bad of [`/stock-movements?locationId=${site}&type=OTHER`, `/stock-movements?locationId=${site}&lotId=nope`, `/stock-movements?locationId=${site}&status=ALL`, '/stock-movements'])
    assert.equal((await call(bad, 'GET')).status, 400, bad);
});

test('movimientos de stock: cantidad, motivo y nota se validan antes del upstream', async t => {
  const { requests, call } = await fixture(t);
  const bodies = [
    { ...movementInput, quantity: '0' }, { ...movementInput, quantity: '-1' }, { ...movementInput, quantity: '1.2345' }, { ...movementInput, quantity: '1e3' }, { ...movementInput, quantity: 1 },
    { ...movementInput, reason: undefined }, { ...movementInput, reason: 'ROBO' }, { ...movementInput, reason: 'OTHER' }, { ...movementInput, reason: 'OTHER', note: 'x'.repeat(501) },
    { ...consumption, reason: 'EXPIRED' }, { ...movementInput, type: 'ADJUST' }, { ...movementInput, lotId: 'nope' }, { ...movementInput, performedBy: 'otro' },
    { ...movementInput, clientCreatedAt: new Date(Date.now() + 3600000).toISOString() }, { ...movementInput, balanceAfter: '1' },
  ];
  for (const body of bodies) assert.equal((await call('/stock-movements', 'POST', body)).status, 400, JSON.stringify(body));
  assert.equal((await call('/stock-movements', 'POST', movementInput, { 'Idempotency-Key': 'x' })).status, 400);
  assert.equal((await call(`/stock-movements/${id}`, 'PUT', movementInput)).status, 405);
  assert.equal((await call(`/stock-movements/${id}`, 'DELETE')).status, 405);
  assert.equal(requests.length, 0);
});

test('movimientos de stock: conserva INSUFFICIENT_STOCK y LOT_EXPIRED con mensajes propios', async t => {
  const { state, call } = await fixture(t);
  state.status = 409;
  for (const code of ['INSUFFICIENT_STOCK', 'LOT_EXPIRED']) {
    state.payload = { errors: [{ code, message: 'detalle interno', fields: { quantity: 'x' } }] };
    const response = await call('/stock-movements', 'POST', consumption);
    assert.equal(response.status, 409);
    const body = await response.json();
    assert.equal(body.errors[0].code, code);
    assert.equal(JSON.stringify(body).includes('interno'), false);
  }
  state.payload = { errors: [{ code: 'RESOURCE_CONFLICT', message: 'otro cuerpo' }] };
  assert.equal((await (await call('/stock-movements', 'POST', consumption)).json()).errors[0].code, 'RESOURCE_CONFLICT');
  state.status = 422;
  state.payload = { errors: [{ code: 'VALIDATION_ERROR', message: 'x', fields: { quantity: 'x', note: 'x', secreto: 'x' } }] };
  const invalid = await (await call('/stock-movements', 'POST', consumption)).json();
  assert.deepEqual(Object.keys(invalid.errors[0].fields).sort(), ['note', 'quantity']);
});

test('movimientos de stock: la respuesta debe confirmar el cuerpo enviado y el saldo', async t => {
  const { state, call } = await fixture(t);
  state.payload = { data: { ...movement, quantity: '0.6' } };
  assert.equal((await call('/stock-movements', 'POST', movementInput)).status, 503);
  state.payload = { data: { ...movement, balanceAfter: '-1' } };
  assert.equal((await call('/stock-movements', 'POST', movementInput)).status, 503);
  state.payload = { data: { ...movement, quantity: '0.500' } };
  assert.equal((await call('/stock-movements', 'POST', movementInput)).status, 201);
});

test('limpieza por turno: planes PER_SHIFT, shiftId en ejecuciones y SHIFT_OUT_OF_HOURS', async t => {
  const { state, requests, call } = await fixture(t);
  const planInput = { id, locationId: site, area: 'Cocina', task: 'Limpiar mesa', instructions: '', productName: '', frequency: 'PER_SHIFT', dueTime: '' };
  state.payload = { data: { ...planInput, active: true } };
  assert.equal((await call('/cleaning-plans', 'POST', planInput)).status, 201);
  state.payload = { data: { ...planInput, dueTime: '08:00', active: true } };
  assert.equal((await call('/cleaning-plans', 'POST', { ...planInput, dueTime: '08:00' })).status, 201);
  const executionInput = { id, locationId: site, cleaningPlanId: other, executedAt: stamp, result: 'COMPLETED', observation: '', clientOperationId: id, clientCreatedAt: stamp, shiftId: lotId };
  state.payload = { data: { ...executionInput, shiftName: 'Mañana', area: 'Cocina', task: 'Limpiar mesa', responsibleUserId: 'firebase-user', responsibleName: 'Ana', createdAt: stamp } };
  const response = await call('/cleaning-executions', 'POST', executionInput);
  assert.equal(response.status, 201);
  const data = (await response.json()).data;
  assert.equal(data.shiftId, lotId); assert.equal(data.shiftName, 'Mañana');
  assert.equal(JSON.parse(requests.at(-1).body).shiftId, lotId);
  assert.equal((await call('/cleaning-executions', 'POST', { ...executionInput, shiftId: 'nope' })).status, 400);
  state.status = 422;
  state.payload = { errors: [{ code: 'SHIFT_OUT_OF_HOURS', message: 'secreto', fields: { shiftId: 'x' } }] };
  const rejected = await call('/cleaning-executions', 'POST', executionInput);
  assert.equal(rejected.status, 422);
  const body = await rejected.json();
  assert.equal(body.errors[0].code, 'SHIFT_OUT_OF_HOURS');
  assert.deepEqual(Object.keys(body.errors[0].fields), ['shiftId']);
});

test('lotes: expone cantidad recibida y conserva compatibilidad sin ella', async t => {
  const { state, call } = await fixture(t);
  const lot = { id: lotId, locationId: site, receiptItemId: lotId, productId: other, productName: 'Leche', unit: 'L', batchCode: 'L-1', expirationDate: null, receivedAt: stamp, storageLocation: 'Frío', label: '', quantityAvailable: '1.125', status: 'AVAILABLE', expired: false };
  state.payload = { data: [{ ...lot, receivedQuantity: '2' }, { ...lot, id: id }], pagination: { nextPageToken: '' } };
  const body = await (await call(`/storage-lots?locationId=${site}`, 'GET')).json();
  assert.equal(body.data[0].receivedQuantity, '2');
  assert.equal(body.data[1].receivedQuantity, '');
  state.payload = { data: [{ ...lot, receivedQuantity: '-2' }], pagination: { nextPageToken: '' } };
  assert.equal((await call(`/storage-lots?locationId=${site}`, 'GET')).status, 503);
});
