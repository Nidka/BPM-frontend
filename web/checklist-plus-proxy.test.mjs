import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';

const org = 'org';
const uuid = n => `${String(n).padStart(8, '0')}-0000-4000-8000-000000000001`;
const templateId = 'template', versionId = 'version2';
const headers = { Authorization: 'Bearer test.token', 'Content-Type': 'application/json', Cookie: 'private' };
const item = (n, extra = {}) => ({ id: uuid(n), sectionName: 'Higiene', label: 'Superficie limpia', responseType: 'COMPLIANCE', displayOrder: n, required: true, unit: '', minimumValue: null, maximumValue: null, evidenceRequiredOnFailure: true, ...extra });
const template = (n = 2) => ({ id: templateId, name: 'Apertura', description: '', area: 'Cocina', versionId, versionNumber: n, items: [item(1)], secret: 'x' });
const draft = { versionId, baseVersionNumber: 1, changeNote: 'Nueva pregunta', items: [item(1)] };

async function fixture(t) {
  const requests = [];
  const state = { status: 200, payload: {} };
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
  const call = (path, method = 'GET', body, extra = {}) => fetch(base + path, { method, headers: { ...headers, ...extra }, body: body === undefined ? undefined : JSON.stringify(body) });
  return { state, requests, call };
}

test('versiones: publica con base y nota, proyecta la plantilla y no reenvía cookies', async t => {
  const { state, requests, call } = await fixture(t);
  state.payload = { data: template() };
  const response = await call(`/checklists/${templateId}/versions`, 'POST', draft);
  assert.equal(response.status, 201);
  const data = (await response.json()).data;
  assert.equal(data.versionNumber, 2); assert.equal(data.secret, undefined);
  assert.equal(requests[0].path, `/api/v1/organizations/${org}/checklists/${templateId}/versions`);
  assert.equal(requests[0].headers.cookie, undefined);
  assert.equal(JSON.parse(requests[0].body).baseVersionNumber, 1);
});

test('versiones: rechaza cuerpos inválidos y métodos antes del upstream', async t => {
  const { requests, call } = await fixture(t);
  const url = `/checklists/${templateId}/versions`;
  for (const body of [{ ...draft, baseVersionNumber: 0 }, { ...draft, role: 'ADMIN' }, { ...draft, items: [] }, { ...draft, changeNote: 'x'.repeat(501) },
    { ...draft, items: [item(1), item(1)] }, { ...draft, items: [{ ...item(1), extra: 1 }] }, { ...draft, items: [item(1, { responseType: 'NUMBER' })] }])
    assert.equal((await call(url, 'POST', body)).status, 400, JSON.stringify(body).slice(0, 80));
  assert.equal((await call(`${url}/${versionId}`, 'POST', draft)).status, 405);
  assert.equal((await call(url, 'PUT', draft)).status, 405);
  assert.equal((await call(url + '?query=x')).status, 404);
  assert.equal(requests.length, 0);
});

test('versiones: historial, detalle y conflictos 409 con código propio', async t => {
  const { state, call } = await fixture(t);
  const summary = { id: 'v1', templateId, versionNumber: 1, status: 'ARCHIVED', changeNote: '', itemCount: 1, publishedAt: '2026-11-01T00:00:00Z', secret: 'x' };
  state.payload = { data: [summary, { ...summary, id: 'v2', versionNumber: 2, status: 'PUBLISHED' }], pagination: { nextPageToken: '' } };
  const list = await (await call(`/checklists/${templateId}/versions?pageSize=10`)).json();
  assert.equal(list.data.length, 2); assert.equal(list.data[0].secret, undefined);
  state.payload = { data: [{ ...summary, status: 'OTHER' }], pagination: { nextPageToken: '' } };
  assert.equal((await call(`/checklists/${templateId}/versions`)).status, 502);
  state.payload = { data: template(1) };
  assert.equal((await call(`/checklists/${templateId}/versions/v1`)).status, 200);
  state.status = 409;
  state.payload = { errors: [{ code: 'VERSION_CONFLICT', message: 'sql secreto' }] };
  const conflict = await call(`/checklists/${templateId}/versions`, 'POST', draft);
  assert.equal(conflict.status, 409);
  const body = await conflict.json();
  assert.equal(body.errors[0].code, 'VERSION_CONFLICT'); assert.equal(JSON.stringify(body).includes('secreto'), false);
  state.payload = { errors: [{ code: 'RESOURCE_CONFLICT', message: 'x' }] };
  assert.equal((await (await call(`/checklists/${templateId}/versions`, 'POST', draft)).json()).errors[0].code, 'RESOURCE_CONFLICT');
});

const assignment = { id: 'a1', templateId, templateVersionId: versionId, templateName: 'Apertura', locationId: 'site', locationName: 'Lima', frequency: 'DAILY', dueTime: '', timezone: 'America/Lima', active: true,
  createdAt: '2026-11-10T10:00:00Z', periodStartsAt: '2026-11-10T11:00:00Z', periodEndsAt: '2026-11-10T19:00:00Z', templateVersionNumber: 3,
  shiftId: 's1', shiftName: 'Mañana', shiftStartTime: '06:00', shiftEndTime: '14:00', shiftState: 'OPEN', run: null };

test('programación por turno: envía shiftId, valida y proyecta turno y versión', async t => {
  const { state, requests, call } = await fixture(t);
  state.payload = { data: assignment };
  const input = { id: 'a1', templateId, locationId: 'site', frequency: 'DAILY', dueTime: '', shiftId: 's1' };
  const response = await call('/checklist-assignments', 'POST', input);
  assert.equal(response.status, 200);
  const data = (await response.json()).data;
  assert.equal(data.shiftState, 'OPEN'); assert.equal(data.templateVersionNumber, 3); assert.equal(data.shiftName, 'Mañana');
  assert.equal(JSON.parse(requests[0].body).shiftId, 's1');
  for (const body of [{ ...input, frequency: 'WEEKLY' }, { ...input, dueTime: '08:00' }, { ...input, shiftId: 'bad id' }])
    assert.equal((await call('/checklist-assignments', 'POST', body)).status, 400);
  state.payload = { data: { ...assignment, shiftState: 'SOON' } };
  assert.equal((await call('/checklist-assignments/a1')).status, 503);
  state.payload = { data: { ...assignment, shiftId: 'otro' } };
  assert.equal((await call('/checklist-assignments', 'POST', input)).status, 503);
  state.payload = { data: [{ ...assignment, shiftId: '', shiftName: '', shiftStartTime: '', shiftEndTime: '', shiftState: '' }], pagination: { nextPageToken: '' } };
  assert.equal((await (await call('/checklist-assignments')).json()).data[0].shiftState, '');
});

test('ejecuciones: conserva SHIFT_OUT_OF_HOURS y TEMPLATE_VERSION_OUTDATED', async t => {
  const { state, call } = await fixture(t);
  const start = { id: 'r1', locationId: 'site', templateVersionId: versionId, clientOperationId: 'c1', clientCreatedAt: '2026-11-10T12:00:00Z', assignmentId: 'a1' };
  state.status = 422;
  state.payload = { errors: [{ code: 'SHIFT_OUT_OF_HOURS', message: 'secreto' }] };
  let response = await call('/checklist-runs', 'POST', start);
  assert.equal(response.status, 422);
  assert.equal((await response.json()).errors[0].code, 'SHIFT_OUT_OF_HOURS');
  state.status = 409;
  state.payload = { errors: [{ code: 'TEMPLATE_VERSION_OUTDATED', message: 'secreto' }] };
  response = await call('/checklist-runs', 'POST', start);
  const body = await response.json();
  assert.equal(body.errors[0].code, 'TEMPLATE_VERSION_OUTDATED'); assert.equal(JSON.stringify(body).includes('secreto'), false);
  state.status = 422;
  state.payload = { errors: [{ code: 'VALIDATION_ERROR', message: 'x' }] };
  assert.equal((await (await call('/checklist-runs', 'POST', start)).json()).errors[0].code, 'VALIDATION_ERROR');
});

test('fotos de limpieza: lista, reserva y retiro con destino cleaningExecutionId', async t => {
  const { state, requests, call } = await fixture(t);
  const exec = uuid(7), mediaId = uuid(8);
  const file = (status = 'AVAILABLE') => ({ id: mediaId, runId: '', itemId: '', cleaningExecutionId: exec, originalName: 'foto.jpg', mimeType: 'image/jpeg', sizeBytes: 4, status, createdAt: '2026-11-10T00:00:00Z', uploadedAt: status === 'AVAILABLE' ? '2026-11-10T00:01:00Z' : null, objectKey: 'privada' });
  state.payload = { data: { files: [file()], maxBytes: 1024, allowedMimeTypes: ['image/jpeg'], maxFiles: 3, uploadAvailable: true } };
  let response = await call(`/cleaning-executions/${exec}/evidence`);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.data.files[0].cleaningExecutionId, exec); assert.equal(JSON.stringify(body).includes('privada'), false);
  assert.equal(requests[0].path, `/api/v1/organizations/${org}/cleaning-executions/${exec}/evidence`);
  const upload = { id: mediaId, cleaningExecutionId: exec, originalName: 'foto.jpg', mimeType: 'image/jpeg', sizeBytes: 4, sha256: 'a'.repeat(64) };
  state.payload = { data: { file: file('AVAILABLE'), upload: null } };
  response = await call('/media/uploads', 'POST', upload);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).data.file.cleaningExecutionId, exec);
  for (const bad of [{ ...upload, runId: uuid(1), itemId: uuid(2) }, { ...upload, incidentId: uuid(3) }, { ...upload, stage: 'BEFORE' }])
    assert.equal((await call('/media/uploads', 'POST', bad)).status, 400);
  state.payload = { data: file('DELETED') };
  assert.equal((await call(`/media/${mediaId}`, 'DELETE')).status, 200);
  assert.equal((await call(`/cleaning-executions/${exec}/evidence`, 'POST', {})).status, 405);
  state.payload = { data: { files: [{ ...file(), cleaningExecutionId: uuid(9) }], maxBytes: 1024, allowedMimeTypes: ['image/jpeg'], maxFiles: 3, uploadAvailable: true } };
  assert.equal((await call(`/cleaning-executions/${exec}/evidence`)).status, 503);
});
