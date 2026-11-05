import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';

const org = '10000000-0000-4000-8000-000000000001';
const site = '20000000-0000-4000-8000-000000000001';
const id = '30000000-0000-4000-8000-000000000001';
const other = '40000000-0000-4000-8000-000000000001';
const itemId = '50000000-0000-4000-8000-000000000001';
const stamp = '2026-01-01T12:00:00Z';
const supplier = { id, name: 'Proveedor', taxId: '', contactName: '', contactPhone: '', contactEmail: '', address: '' };
const product = { id, name: 'Insumo', category: 'Alimentos', unit: 'kg', requiresTemperature: true, minimumTemperature: 1.25, maximumTemperature: 5, rowVersion: 1 };
const receiptInput = { id, locationId: site, supplierId: other, receivedAt: stamp, documentReference: '', notes: '', clientOperationId: id, clientCreatedAt: stamp, items: [{ id: itemId, productId: other, batchCode: 'Lote', expirationDate: '2026-12-31', quantity: '1.125', temperatureCelsius: 3.25, decision: 'ACCEPTED', rejectionReason: '', packagingCondition: 'INTACT', storageLocation: 'Frío', label: '' }] };
const receipt = { ...receiptInput, supplierName: 'Proveedor', receivedBy: 'firebase-user', createdAt: stamp, items: receiptInput.items.map(item => ({ ...item, productName: 'Insumo', unit: 'kg', minimumTemperature: 1, maximumTemperature: 5 })) };
const planInput = { id, locationId: site, area: 'Cocina', task: 'Limpiar mesa', instructions: '', productName: '', frequency: 'DAILY', dueTime: '12:30' };
const plan = { ...planInput, active: true };
const executionInput = { id, locationId: site, cleaningPlanId: other, executedAt: stamp, result: 'COMPLETED', observation: '', clientOperationId: id, clientCreatedAt: stamp };
const execution = { ...executionInput, area: 'Cocina', task: 'Limpiar mesa', responsibleUserId: 'firebase-user', responsibleName: 'Nombre', createdAt: stamp };
const lot = { id: itemId, locationId: site, receiptItemId: itemId, productId: other, productName: 'Insumo', unit: 'kg', batchCode: 'Lote', expirationDate: null, receivedAt: stamp, storageLocation: 'Frío', label: '', quantityAvailable: '0.000', status: 'CONSUMED', expired: false };
const headers = { Authorization: 'Bearer test.token', 'Content-Type': 'application/json', 'Idempotency-Key': 'operation-unique', Cookie: 'private-cookie', 'X-Role': 'SUPER_ADMIN', 'X-Organization-ID': 'foreign' };

async function fixture(t, organizationId = org) {
  const requests = [];
  const state = { status: 200, payload: { data: [supplier], pagination: { nextPageToken: 'next_page', secret: 'private' } }, redirect: false };
  const backend = http.createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    const body = Buffer.concat(chunks).toString('utf8');
    requests.push({ path: req.url, method: req.method, headers: req.headers, body });
    res.writeHead(state.redirect ? 302 : state.status, { 'Content-Type': 'application/json', 'X-BPM-Session-Expires-At': '1900000000', 'X-BPM-Session-Idle-Expires-At': '1900000001', 'Set-Cookie': 'private=secret', ...(state.redirect ? { Location: '/private' } : {}) });
    res.end(typeof state.payload === 'string' ? state.payload : JSON.stringify(state.payload));
  });
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
  const app = createAppServer({ apiBaseUrl: `http://127.0.0.1:${backend.address().port}`, allowInsecureForTests: true });
  app.listen(0, '127.0.0.1'); await once(app, 'listening');
  t.after(async () => { app.closeAllConnections(); backend.closeAllConnections(); await Promise.all([new Promise(r => app.close(r)), new Promise(r => backend.close(r))]); });
  const base = `http://127.0.0.1:${app.address().port}/backend/api/v1/organizations/${organizationId}`;
  const get = path => fetch(base + path, { headers });
  const write = (path, body, extra = {}, method = 'POST') => fetch(base + path, { method, headers: { ...headers, ...extra }, body: JSON.stringify(body) });
  return { base, state, requests, get, write };
}

test('operaciones: listas cerradas proyectan datos y paginación sin filtrar cabeceras ajenas', async t => {
  const { state, requests, get } = await fixture(t);
  for (const [resource, entity] of [['suppliers', supplier], ['products', product], ['supply-receipts', receipt], ['storage-lots', lot], ['cleaning-plans', plan], ['cleaning-executions', execution]]) {
    state.payload = { data: [{ ...entity, organizationId: org, privateData: 'secret' }], pagination: { nextPageToken: 'next_page', secret: 'private' } };
    const path = `/${resource}?pageSize=2&pageToken=cursor_1&query=${encodeURIComponent('área fría')}` + (['suppliers', 'products'].includes(resource) ? '' : `&locationId=${site}`);
    const response = await get(path);
    assert.equal(response.status, 200, resource);
    const publicData = await response.json();
    assert.deepEqual(publicData.pagination, { nextPageToken: 'next_page' });
    assert.equal(publicData.data[0].id, entity.id);
    assert.equal(JSON.stringify(publicData).includes('secret'), false);
    assert.equal(publicData.data[0].organizationId, undefined);
    assert.equal(response.headers.get('set-cookie'), null);
    assert.equal(response.headers.get('x-bpm-session-expires-at'), '1900000000');
    assert.equal(response.headers.get('x-bpm-session-idle-expires-at'), '1900000001');
    assert.equal(requests.at(-1).headers.authorization, 'Bearer test.token');
    for (const name of ['cookie', 'x-role', 'x-organization-id']) assert.equal(requests.at(-1).headers[name], undefined);
    assert.equal(new URL(requests.at(-1).path, 'http://local').searchParams.get('query'), 'área fría');
    assert.equal(requests.at(-1).body, '');
  }
});

test('operaciones: rechaza rutas, filtros, métodos, sesión y origen antes del upstream', async t => {
  const { base, requests, get, write } = await fixture(t);
  assert.equal((await fetch(base + '/suppliers')).status, 401);
  for (const path of ['/suppliers?url=https://evil.example', '/suppliers?organizationId=' + other, '/suppliers?locationId=' + site, '/suppliers?pageSize=0', '/suppliers?pageSize=101', '/suppliers?pageToken=https%3A%2F%2Fevil', '/suppliers?pageSize=2&pageSize=3', '/suppliers?query=' + 'a'.repeat(161), '/suppliers?query=%00', '/supply-receipts', '/cleaning-plans?locationId=bad', '/storage-lots?locationId=' + site + '&locationId=' + site, '/supply-receipts/' + id + '?locationId=' + site])
    assert.equal((await get(path)).status, 400, path);
  for (const path of ['/suppliers/' + id, '/products/' + id, '/storage-lots/' + id, '/cleaning-plans/' + id, '/supply-receipts/bad', '/suppliers/extra/path', '/products/' + id + '/temperature-policy/extra']) assert.equal((await get(path)).status, 404, path);
  for (const [path, method] of [['/suppliers', 'DELETE'], ['/storage-lots', 'POST'], ['/supply-receipts/' + id, 'POST'], ['/products/' + id + '/temperature-policy', 'POST'], ['/suppliers', 'HEAD']])
    assert.equal((await fetch(base + path, { method, headers })).status, 405, path);
  assert.equal((await write('/suppliers', supplier, { Origin: 'https://evil.example' })).status, 403);
  assert.equal((await write('/suppliers', supplier, { Origin: 'null' })).status, 403);
  assert.equal((await write('/suppliers', supplier, { 'Content-Type': 'text/plain' })).status, 415);
  assert.equal((await write('/suppliers', supplier, { 'Idempotency-Key': 'short' })).status, 400);
  assert.equal((await write('/suppliers', supplier, { Authorization: 'Bearer a/b' })).status, 401);
  assert.equal(requests.length, 0);
});

test('operaciones: altas y política versionada usan JSON estricto y claves idempotentes', async t => {
  const { state, requests, write } = await fixture(t);
  const { rowVersion, ...productInput } = product;
  for (const [resource, input, entity, method] of [['suppliers', supplier, supplier, 'POST'], ['products', productInput, product, 'POST'], ['supply-receipts', receiptInput, receipt, 'POST'], ['cleaning-plans', planInput, plan, 'POST'], ['cleaning-executions', executionInput, execution, 'POST'], ['products/' + id + '/temperature-policy', { rowVersion: 1, requiresTemperature: false, minimumTemperature: null, maximumTemperature: null }, { ...product, requiresTemperature: false, minimumTemperature: null, maximumTemperature: null, rowVersion: 2 }, 'PUT']]) {
    state.status = method === 'POST' ? 201 : 200; state.payload = { data: { ...entity, privateData: 'secret' } };
    const response = await write('/' + resource, input, {}, method);
    assert.equal(response.status, method === 'POST' ? 201 : 200, resource);
    assert.equal((await response.json()).data.id, id);
    assert.equal(requests.at(-1).headers['idempotency-key'], 'operation-unique');
    assert.equal(requests.at(-1).method, method);
    assert.deepEqual(JSON.parse(requests.at(-1).body), input);
  }
});

test('operaciones: tipos, UUID, decimales, fechas y actores forjados nunca se reenvían', async t => {
  const { requests, write } = await fixture(t);
  const invalid = [
    ['/suppliers', { ...supplier, receivedBy: 'other' }], ['/suppliers', { ...supplier, organizationId: other }], ['/suppliers', { ...supplier, id: 'bad' }], ['/suppliers', { ...supplier, name: 12 }], ['/suppliers', { ...supplier, contactPhone: 'abc' }], ['/suppliers', { ...supplier, contactEmail: 'invalid' }],
    ['/products', product], ['/products', { ...product, rowVersion: undefined, requiresTemperature: 'yes' }], ['/products', { ...product, rowVersion: undefined, minimumTemperature: 1.001 }], ['/products', { ...product, rowVersion: undefined, minimumTemperature: null }],
    ['/cleaning-plans', { ...planInput, active: true }], ['/cleaning-plans', { ...planInput, frequency: 'PER_SHIFT' }], ['/cleaning-plans', { ...planInput, dueTime: '24:00' }],
    ['/cleaning-executions', { ...executionInput, responsibleUserId: 'other' }], ['/cleaning-executions', { ...executionInput, executedAt: '2026-02-30T12:00:00Z' }], ['/cleaning-executions', { ...executionInput, clientCreatedAt: '2999-01-01T00:00:00Z' }], ['/cleaning-executions', { ...executionInput, cleaningPlanId: 'https://evil.example' }],
  ];
  for (const patch of [{ quantity: 1.125 }, { quantity: '1.1234' }, { quantity: '0.000' }, { quantity: '1000000000.000' }, { temperatureCelsius: '3.25' }, { temperatureCelsius: 3.251 }, { expirationDate: '2026-02-30' }, { packagingCondition: null }, { actor: 'other' }, { decision: 'DISCARDED' }]) invalid.push(['/supply-receipts', { ...receiptInput, items: [{ ...receiptInput.items[0], ...patch }] }]);
  invalid.push(['/supply-receipts', { ...receiptInput, items: [] }], ['/supply-receipts', { ...receiptInput, items: Array(51).fill(receiptInput.items[0]) }]);
  for (const [path, body] of invalid) assert.equal((await write(path, body)).status, 400, JSON.stringify(body));
  assert.equal((await write('/products/' + id + '/temperature-policy', { rowVersion: 0, requiresTemperature: false }, {}, 'PUT')).status, 400);
  assert.equal((await write('/products/' + id + '/temperature-policy', { rowVersion: 1, requiresTemperature: false, role: 'ADMIN' }, {}, 'PUT')).status, 400);
  assert.equal(requests.length, 0);
});

test('operaciones: detalles y legado no inventan envase ni criterios', async t => {
  const { state, get } = await fixture(t);
  state.payload = { data: { ...receipt, items: [{ ...receipt.items[0], packagingCondition: null, minimumTemperature: null, maximumTemperature: null }] } };
  const receiptResponse = await get('/supply-receipts/' + id);
  assert.equal(receiptResponse.status, 200);
  const item = (await receiptResponse.json()).data.items[0];
  assert.equal(item.packagingCondition, null); assert.equal(item.minimumTemperature, null);
  state.payload = { data: execution }; assert.equal((await get('/cleaning-executions/' + id)).status, 200);
  state.payload = { data: [{ ...product, minimumTemperature: null, maximumTemperature: null }], pagination: { nextPageToken: '' } };
  assert.equal((await get('/products')).status, 200);
});

test('operaciones: respuestas malformadas, otro contexto y redirecciones se sanitizan a 503', async t => {
  const { state, requests, get, write } = await fixture(t);
  for (const payload of ['private SQL token', { data: [supplier] }, { data: [{ ...supplier, id: 'bad' }], pagination: { nextPageToken: '' } }, { data: [supplier, supplier], pagination: { nextPageToken: '' } }, { data: [supplier], pagination: { nextPageToken: 'https://evil.example' } }, { data: [{ ...supplier, organizationId: other }], pagination: { nextPageToken: '' } }]) {
    state.payload = payload; const response = await get('/suppliers'); assert.equal(response.status, 503); assert.equal((await response.text()).includes('SQL'), false);
  }
  state.payload = { data: [{ ...lot, locationId: other }], pagination: { nextPageToken: '' } };
  assert.equal((await get('/storage-lots?locationId=' + site)).status, 503);
  state.payload = { data: { ...receipt, id: other } }; assert.equal((await get('/supply-receipts/' + id)).status, 503);
  state.payload = { data: { ...execution, locationId: other } }; assert.equal((await write('/cleaning-executions', executionInput)).status, 503);
  state.redirect = true; const before = requests.length; assert.equal((await get('/suppliers')).status, 503); assert.equal(requests.length, before + 1); state.redirect = false;
  for (const status of [400, 401, 403, 404, 409, 422, 429, 500]) {
    state.status = status; state.payload = { errors: [{ code: 'private SQL token', message: 'private SQL token', fields: { locationId: 'private SQL token', password: 'secret', 'items.0.quantity': 'private' } }] };
    const response = await write('/supply-receipts', receiptInput);
    assert.equal(response.status, status === 500 ? 503 : status);
    const body = await response.text(); assert.equal(body.includes('SQL'), false); assert.equal(body.includes('password'), false);
    if (status === 422) assert.equal(JSON.parse(body).errors[0].fields.locationId, 'Revisa este campo.');
  }
});

test('operaciones: no admite cuerpos GET, JSON roto ni cuerpos excesivos', async t => {
  const { base, requests } = await fixture(t);
  const getBodyStatus = await new Promise((resolve, reject) => {
    const req = http.request(base + '/suppliers', { method: 'GET', headers: { ...headers, 'Content-Length': '2' } }, res => { res.resume(); res.on('end', () => resolve(res.statusCode)); });
    req.on('error', reject); req.end('{}');
  });
  assert.equal(getBodyStatus, 400);
  for (const body of ['{', '[]', 'null', '{"name":"' + 'a'.repeat(131072) + '"}']) assert.equal((await fetch(base + '/suppliers', { method: 'POST', headers, body })).status, 400);
  assert.equal(requests.length, 0);
});

test('operaciones: vencimiento vacío se normaliza a null pero no acepta null como hora', async t => {
  const { base, state, requests, write } = await fixture(t);
  const input = { ...receiptInput, items: [{ ...receiptInput.items[0], expirationDate: '' }] };
  state.status = 201; state.payload = { data: { ...receipt, items: [{ ...receipt.items[0], expirationDate: null }] } };
  const response = await write('/supply-receipts', input, { Origin: new URL(base).origin });
  assert.equal(response.status, 201);
  assert.equal((await response.json()).data.items[0].expirationDate, null);
  assert.equal(requests.length, 1);
  assert.equal((await write('/cleaning-plans', { ...planInput, dueTime: null })).status, 400);
  assert.equal(requests.length, 1);
});

test('operaciones: errores upstream sin JSON válido se tratan como servicio no disponible', async t => {
  const { state, get, write } = await fixture(t);
  for (const status of [400, 401, 403, 404, 409, 422, 429]) {
    state.status = status; state.payload = 'private SQL upstream error';
    const response = await write('/suppliers', supplier);
    assert.equal(response.status, 503);
    assert.equal((await response.text()).includes('SQL'), false);
  }
  state.status = 200; state.payload = { data: [{ ...plan, dueTime: null }], pagination: { nextPageToken: '' } };
  assert.equal((await get('/cleaning-plans?locationId=' + site)).status, 503);
});

test('operaciones: valida cada entidad pública antes de confirmar una lista o escritura', async t => {
  const { state, get, write } = await fixture(t);
  const cases = [
    ['suppliers', { ...supplier, contactEmail: 'invalid' }],
    ['products', { ...product, rowVersion: 0 }], ['products', { ...product, minimumTemperature: 7, maximumTemperature: 2 }], ['products', { ...product, requiresTemperature: false }],
    ['supply-receipts', { ...receipt, receivedAt: '2026-01-01T12:00:00+00:00' }],
    ['supply-receipts', { ...receipt, items: [{ ...receipt.items[0], quantity: '1.1234' }] }],
    ['supply-receipts', { ...receipt, items: [{ ...receipt.items[0], temperatureCelsius: 10000 }] }],
    ['supply-receipts', { ...receipt, items: [{ ...receipt.items[0], minimumTemperature: 1.001 }] }],
    ['storage-lots', { ...lot, quantityAvailable: '-1.000' }], ['storage-lots', { ...lot, receiptItemId: 'bad' }], ['storage-lots', { ...lot, expired: 'false' }], ['storage-lots', { ...lot, status: 'UNKNOWN' }],
    ['cleaning-plans', { ...plan, active: 'true' }], ['cleaning-plans', { ...plan, frequency: 'PER_SHIFT' }],
    ['cleaning-executions', { ...execution, cleaningPlanId: 'bad' }], ['cleaning-executions', { ...execution, responsibleUserId: '' }], ['cleaning-executions', { ...execution, result: 'UNKNOWN' }],
  ];
  for (const [resource, data] of cases) {
    state.payload = { data: [data], pagination: { nextPageToken: '' } };
    assert.equal((await get('/' + resource + (['products', 'suppliers'].includes(resource) ? '' : '?locationId=' + site))).status, 503, JSON.stringify(data));
  }
  state.payload = { data: { ...product, rowVersion: 1 } };
  assert.equal((await write('/products/' + id + '/temperature-policy', { rowVersion: 1, requiresTemperature: true, minimumTemperature: 1.25, maximumTemperature: 5 }, {}, 'PUT')).status, 503);
  state.payload = { data: { ...receipt, items: [{ ...receipt.items[0], productId: id }] } };
  assert.equal((await write('/supply-receipts', receiptInput)).status, 503);
  state.payload = { data: { ...plan, active: false } };
  assert.equal((await write('/cleaning-plans', planInput)).status, 503);
  state.payload = { data: [supplier, { ...supplier, id: other }], pagination: { nextPageToken: '' } };
  assert.equal((await get('/suppliers?pageSize=1')).status, 503);
});


test('operaciones: cantidades admiten hasta tres decimales y confirman su valor canónico', async t => {
  const { state, write } = await fixture(t);
  for (const [sent, confirmed] of [['1.000', '1'], ['1.12', '1.120'], ['0.001', '0.001']]) {
    state.status = 201; state.payload = { data: { ...receipt, items: [{ ...receipt.items[0], quantity: confirmed }] } };
    assert.equal((await write('/supply-receipts', { ...receiptInput, items: [{ ...receiptInput.items[0], quantity: sent }] })).status, 201);
  }
});


test('operaciones: proyecta nombre público del receptor y rechaza tipos inválidos', async t => {
  const { state, get } = await fixture(t);
  state.payload = { data: { ...receipt, receivedByName: 'Chef responsable', privateEmail: 'secret@example.invalid' } };
  const response = await get('/supply-receipts/' + id);
  assert.equal(response.status, 200);
  const value = (await response.json()).data;
  assert.equal(value.receivedByName, 'Chef responsable');
  assert.equal(value.privateEmail, undefined);
  state.payload = { data: { ...receipt, receivedByName: 3 } };
  assert.equal((await get('/supply-receipts/' + id)).status, 503);
});


test('operaciones: conserva IDs independientes de lote y recepción en datos legados válidos', async t => {
  const { state, get } = await fixture(t);
  state.payload = { data: [{ ...lot, id: other, receiptItemId: itemId }], pagination: { nextPageToken: '' } };
  const response = await get('/storage-lots?locationId=' + site);
  assert.equal(response.status, 200);
  const result = (await response.json()).data[0];
  assert.equal(result.id, '40000000-0000-4000-8000-000000000001');
  assert.equal(result.receiptItemId, '50000000-0000-4000-8000-000000000001');
  assert.equal(result.locationId, site);
  assert.equal(result.quantityAvailable, '0.000');
});


test('operaciones: empresas legadas conservan ID textual y el resto de IDs siguen siendo UUID', async t => {
  const { state, requests, base, get, write } = await fixture(t, 'org-legado');
  for (const [resource, entity] of [['suppliers', supplier], ['products', product], ['supply-receipts', receipt], ['storage-lots', lot], ['cleaning-plans', plan], ['cleaning-executions', execution]]) {
    state.payload = { data: [{ ...entity, organizationId: 'org-legado' }], pagination: { nextPageToken: '' } };
    const response = await get('/' + resource + (['suppliers', 'products'].includes(resource) ? '' : '?locationId=' + site));
    assert.equal(response.status, 200, resource);
    const value = (await response.json()).data[0];
    assert.equal(value.id, entity.id);
    assert.equal(value.organizationId, undefined);
    assert.equal(requests.at(-1).path.startsWith('/api/v1/organizations/org-legado/'), true);
  }
  assert.equal((await get('/supply-receipts/receipt-legado')).status, 404);
  assert.equal((await get('/storage-lots?locationId=sede-legada')).status, 400);
  state.status = 201; state.payload = { data: { ...supplier, organizationId: 'org-legado' } };
  assert.equal((await write('/suppliers', supplier)).status, 201);
  state.status = 200; state.payload = { data: [{ ...supplier, organizationId: 'org-ajena' }], pagination: { nextPageToken: '' } };
  assert.equal((await get('/suppliers')).status, 503);
  const appOrigin = new URL(base).origin;
  for (const badOrg of ['org.invalida', 'a'.repeat(65), 'org%2Fajena']) assert.equal((await fetch(appOrigin + '/backend/api/v1/organizations/' + badOrg + '/suppliers', { headers })).status, 404);
});

test('operaciones: batchCode vacío se conserva en lectura legada y se rechaza en altas', async t => {
  const { state, requests, get, write } = await fixture(t);
  state.payload = { data: { ...receipt, items: [{ ...receipt.items[0], batchCode: '', packagingCondition: null, minimumTemperature: null, maximumTemperature: null }] } };
  const detail = await get('/supply-receipts/' + id);
  assert.equal(detail.status, 200);
  const item = (await detail.json()).data.items[0];
  assert.equal(item.batchCode, '');
  assert.equal(item.packagingCondition, null);
  assert.equal(item.minimumTemperature, null);
  state.payload = { data: [{ ...lot, batchCode: '' }], pagination: { nextPageToken: '' } };
  const lots = await get('/storage-lots?locationId=' + site);
  assert.equal(lots.status, 200);
  assert.equal((await lots.json()).data[0].batchCode, '');
  const previous = requests.length;
  assert.equal((await write('/supply-receipts', { ...receiptInput, items: [{ ...receiptInput.items[0], batchCode: '' }] })).status, 400);
  assert.equal(requests.length, previous);
});

test('operaciones: recepción Unicode válida mayor de 128 KiB conserva su cuerpo hasta 512 KiB', async t => {
  const { state, requests, base, write } = await fixture(t);
  const input = { ...receiptInput, items: Array.from({ length: 50 }, (_, index) => ({
    ...receiptInput.items[0], id: '50000000-0000-4000-8000-' + String(index + 1).padStart(12, '0'),
    batchCode: 'L'.repeat(100), quantity: '1', decision: 'OBSERVED', rejectionReason: 'á'.repeat(2000),
    packagingCondition: 'DAMAGED', storageLocation: '', label: '',
  })) };
  const body = JSON.stringify(input);
  assert.equal(Buffer.byteLength(body) > 128 * 1024, true);
  assert.equal(Buffer.byteLength(body) < 512 * 1024, true);
  state.status = 201;
  state.payload = { data: { ...receipt, items: input.items.map(item => ({ ...item, productName: 'Insumo', unit: 'kg', minimumTemperature: 1, maximumTemperature: 5 })) } };
  const response = await write('/supply-receipts', input);
  assert.equal(response.status, 201);
  assert.equal((await response.json()).data.items.length, 50);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].body === body, true, 'el upstream debe recibir el JSON Unicode exacto');
  const tooLarge = body + ' '.repeat(512 * 1024 + 1 - Buffer.byteLength(body));
  assert.equal(Buffer.byteLength(tooLarge), 512 * 1024 + 1);
  const rejected = await fetch(base + '/supply-receipts', { method: 'POST', headers, body: tooLarge });
  assert.equal(rejected.status, 400);
  assert.equal(requests.length, 1);
});
