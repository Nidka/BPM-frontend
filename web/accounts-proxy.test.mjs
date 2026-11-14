import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';

const stamp = '2026-01-01T12:00:00Z';
const token = 'A'.repeat(43);
const member = { id: 'u1', name: 'Ana', email: 'ana@example.com', role: 'CHEF', locationIds: ['l1'], status: 'ACTIVE', rowVersion: 3, secret: 'hidden' };
const invitation = { id: 'i1', email: 'a@example.com', role: 'WORKER', locationIds: ['l1'], status: 'PENDING', expiresAt: stamp, lastSentAt: stamp, createdAt: stamp, sendCount: 1, rowVersion: 1, token: 'SECRETTOKEN' };
const device = { id: 'd1', organizationId: 'o1', organizationName: 'BPM', platform: 'WEB', status: 'ACTIVE', lastSeenAt: null, createdAt: stamp, pushToken: 'hidden' };
const headers = { Authorization: 'Bearer test.token', 'Content-Type': 'application/json', Cookie: 'private=value' };

async function fixture(t) {
  const state = { status: 200, payload: { data: member }, requests: [] };
  const backend = http.createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    state.requests.push({ method: req.method, path: req.url, headers: req.headers, body: Buffer.concat(chunks).toString('utf8') });
    res.writeHead(state.status, { 'Content-Type': 'application/json', 'Set-Cookie': 'private=1' });
    res.end(JSON.stringify(state.payload));
  });
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
  const app = createAppServer({ apiBaseUrl: 'http://127.0.0.1:' + backend.address().port, allowInsecureForTests: true });
  app.listen(0, '127.0.0.1'); await once(app, 'listening');
  t.after(async () => { app.closeAllConnections(); backend.closeAllConnections(); await Promise.all([new Promise(r => app.close(r)), new Promise(r => backend.close(r))]); });
  const base = 'http://127.0.0.1:' + app.address().port + '/backend/api/v1';
  const call = (method, path, body, extra = {}) => fetch(base + path, { method, headers: { ...headers, ...extra }, body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body) });
  return { state, call };
}

test('miembros: PATCH y acciones validan el cuerpo, proyectan campos públicos y no filtran cookies', async t => {
  const { state, call } = await fixture(t);
  const ok = await call('PATCH', '/organizations/o1/members/u1', { role: 'CHEF', locationIds: ['l1'], rowVersion: 3 });
  assert.equal(ok.status, 200);
  const data = (await ok.json()).data;
  assert.equal(data.rowVersion, 3); assert.equal(data.secret, undefined);
  assert.equal(state.requests[0].headers.cookie, undefined);
  assert.equal(state.requests[0].headers.authorization, 'Bearer test.token');
  // Cuerpos fuera de contrato nunca llegan al backend.
  for (const bad of [{ role: 'OWNER', locationIds: [], rowVersion: 1 }, { role: 'CHEF', locationIds: ['l1', 'l1'], rowVersion: 1 }, { role: 'CHEF', locationIds: [], rowVersion: 0 }, { role: 'CHEF', locationIds: [], rowVersion: 1, extra: 1 }])
    assert.equal((await call('PATCH', '/organizations/o1/members/u1', bad)).status, 400);
  assert.equal(state.requests.length, 1);
  state.payload = { data: member };
  for (const action of ['deactivate', 'reactivate']) assert.equal((await call('POST', `/organizations/o1/members/u1/${action}`, { rowVersion: 3 })).status, 200);
  assert.equal((await call('POST', '/organizations/o1/members/u1/deactivate', {})).status, 400);
});

test('transferir administración: SUPER_ADMIN omite rol y sedes; ADMIN debe enviar ambos', async t => {
  const { state, call } = await fixture(t);
  state.payload = { data: { newAdmin: { ...member, role: 'ADMIN' }, formerAdmin: null } };
  assert.equal((await call('POST', '/organizations/o1/members/u1/transfer-admin', { rowVersion: 3 })).status, 200);
  assert.equal((await call('POST', '/organizations/o1/members/u1/transfer-admin', { rowVersion: 3, actorRole: 'WORKER' })).status, 400);
  assert.equal((await call('POST', '/organizations/o1/members/u1/transfer-admin', { rowVersion: 3, actorRole: 'ADMIN', actorLocationIds: ['l1'] })).status, 400);
  state.payload = { data: { newAdmin: { ...member, role: 'ADMIN' }, formerAdmin: { ...member, role: 'WORKER' } } };
  const ok = await call('POST', '/organizations/o1/members/u1/transfer-admin', { rowVersion: 3, actorRole: 'WORKER', actorLocationIds: ['l1'] });
  assert.equal(ok.status, 200);
  assert.equal((await ok.json()).data.formerAdmin.role, 'WORKER');
});

test('errores: conflicto de versión y último ADMIN conservan su código; los demás se sanitizan', async t => {
  const { state, call } = await fixture(t);
  const send = () => call('PATCH', '/organizations/o1/members/u1', { role: 'CHEF', locationIds: ['l1'], rowVersion: 1 });
  state.status = 409; state.payload = { errors: [{ code: 'VERSION_CONFLICT', message: 'SQL private' }] };
  let body = await (await send()).json();
  assert.equal(body.errors[0].code, 'VERSION_CONFLICT'); assert.equal(JSON.stringify(body).includes('SQL'), false);
  state.payload = { errors: [{ code: 'LAST_ADMIN_REQUIRED' }] };
  assert.equal((await (await send()).json()).errors[0].code, 'LAST_ADMIN_REQUIRED');
  state.payload = { errors: [{ code: 'SOMETHING_INTERNAL' }] };
  assert.equal((await (await send()).json()).errors[0].code, 'RESOURCE_CONFLICT');
  state.status = 422; state.payload = { errors: [{ code: 'VALIDATION_ERROR', fields: { locationIds: 'x', password: 'private' } }] };
  const invalid = await (await send()).json();
  assert.deepEqual(Object.keys(invalid.errors[0].fields), ['locationIds']);
  state.status = 500; state.payload = { errors: [] };
  assert.equal((await send()).status, 503);
});

test('organización y sede: PATCH exacto con tipo de establecimiento y estado', async t => {
  const { state, call } = await fixture(t);
  const org = { legalName: 'BPM SAC', tradeName: '', taxId: '', businessType: 'CAFETERIA', contactName: '', contactPhone: '', contactEmail: '', rowVersion: 2 };
  state.payload = { data: { id: 'o1', ...org } };
  assert.equal((await call('PATCH', '/organizations/o1', org)).status, 200);
  assert.equal((await call('PATCH', '/organizations/o1', { ...org, businessType: 'RELATED_SERVICE' })).status, 400);
  assert.equal((await call('PATCH', '/organizations/o1', { ...org, tenant: 'otra' })).status, 400);
  const loc = { name: 'Sede', address: 'Calle 1', district: '', contactPhone: '', timezone: 'America/Lima', status: 'INACTIVE', rowVersion: 1 };
  state.payload = { data: { id: 'l1', ...loc } };
  assert.equal((await call('PATCH', '/organizations/o1/locations/l1', loc)).status, 200);
  assert.equal((await call('PATCH', '/organizations/o1/locations/l1', { ...loc, status: 'DELETED' })).status, 400);
  assert.equal(state.requests.length, 2);
});

test('invitaciones: crear, listar con filtros cerrados, reenviar, revocar; el token nunca sale', async t => {
  const { state, call } = await fixture(t);
  state.payload = { data: invitation };
  const created = await call('POST', '/organizations/o1/invitations', { id: 'i1', email: 'a@example.com', role: 'WORKER', locationIds: ['l1'] });
  assert.equal(created.status, 200);
  assert.equal(JSON.stringify(await created.json()).includes('SECRETTOKEN'), false);
  assert.equal((await call('POST', '/organizations/o1/invitations', { id: 'i1', email: 'no-es-correo', role: 'WORKER', locationIds: [] })).status, 400);
  state.payload = { data: [invitation], pagination: { nextPageToken: 'abc_1' } };
  const listed = await call('GET', '/organizations/o1/invitations?status=EXPIRED&pageSize=25&query=ana');
  assert.equal(listed.status, 200);
  assert.equal((await listed.json()).pagination.nextPageToken, 'abc_1');
  assert.equal(state.requests.at(-1).path, '/api/v1/organizations/o1/invitations?status=EXPIRED&pageSize=25&query=ana');
  for (const bad of ['?status=OTHER', '?evil=1', '?pageSize=1000', '?status=PENDING&status=EXPIRED']) assert.equal((await call('GET', '/organizations/o1/invitations' + bad)).status, 400);
  state.payload = { data: invitation };
  for (const action of ['resend', 'revoke']) assert.equal((await call('POST', `/organizations/o1/invitations/i1/${action}`, '')).status, 200);
  assert.equal((await call('POST', '/organizations/o1/invitations/i1/resend', { x: 1 })).status, 400);
  state.status = 429; state.payload = { errors: [{ code: 'INVITATION_RESEND_TOO_SOON' }] };
  const wait = await call('POST', '/organizations/o1/invitations/i1/resend', '');
  assert.equal(wait.status, 429); assert.equal((await wait.json()).errors[0].code, 'INVITATION_RESEND_TOO_SOON');
});

test('aceptar invitación: token de 43 caracteres, errores de dominio y sin eco del token', async t => {
  const { state, call } = await fixture(t);
  state.payload = { data: { organizationId: 'o1', role: 'CHEF', locationIds: ['l1'] } };
  const ok = await call('POST', '/invitations/accept', { token });
  assert.equal(ok.status, 200);
  assert.equal((await ok.json()).data.role, 'CHEF');
  for (const bad of [{ token: 'corto' }, { token, extra: 1 }, {}, { token: token + '=' }]) assert.equal((await call('POST', '/invitations/accept', bad)).status, 400);
  assert.equal((await call('POST', '/invitations/accept?token=' + token, { token })).status, 400);
  for (const [status, code] of [[403, 'INVITATION_EMAIL_MISMATCH'], [404, 'INVITATION_NOT_FOUND'], [409, 'INVITATION_EXPIRED'], [409, 'INVITATION_ALREADY_USED'], [409, 'INVITATION_REVOKED']]) {
    state.status = status; state.payload = { errors: [{ code, message: token }] };
    const response = await call('POST', '/invitations/accept', { token });
    const text = await response.text();
    assert.equal(response.status, status); assert.ok(text.includes(code)); assert.equal(text.includes(token), false);
  }
});

test('dispositivos propios: lista y revocación sin cuerpo ni token push', async t => {
  const { state, call } = await fixture(t);
  state.payload = { data: [device], pagination: { nextPageToken: '' } };
  const listed = await call('GET', '/me/devices?pageSize=25');
  assert.equal(listed.status, 200);
  const text = JSON.stringify(await listed.json());
  assert.equal(text.includes('hidden'), false); assert.ok(text.includes('"platform":"WEB"'));
  assert.equal((await call('GET', '/me/devices?query=x')).status, 400);
  state.payload = { data: { ...device, status: 'INACTIVE' } };
  assert.equal((await call('DELETE', '/me/devices/d1', '')).status, 200);
  assert.equal((await call('DELETE', '/me/devices/d1', { x: 1 })).status, 400);
  assert.equal((await call('DELETE', '/me/devices', '')).status, 405);
  assert.equal((await call('PUT', '/me/devices/d1', '{}')).status, 405);
});

test('seguridad común: Bearer, origen y rutas fuera de contrato', async t => {
  const { state, call } = await fixture(t);
  const body = { rowVersion: 1 };
  const noAuth = await fetch(`${(await call('GET', '/me/devices')).url.split('/me')[0]}/me/devices`);
  assert.equal(noAuth.status, 401);
  assert.equal((await call('POST', '/organizations/o1/members/u1/deactivate', body, { Origin: 'https://evil.example' })).status, 403);
  assert.equal((await call('GET', '/organizations/o1/members/u1')).status, 405);
  assert.equal((await call('DELETE', '/organizations/o1')).status, 405);
  assert.equal((await call('POST', '/organizations/o1/members/u1/purge', body)).status, 405);
  // Solo la lectura inicial de la URL base llegó al backend; ninguna llamada inválida lo hizo.
  assert.equal(state.requests.length, 1);
});

test('workspace: listados de miembros y sedes aceptan solo status ACTIVE/INACTIVE y devuelven rowVersion', async t => {
  const { state, call } = await fixture(t);
  state.payload = { data: [{ id: 'u1', name: 'Ana', email: 'a@example.com', role: 'CHEF', locationIds: [], status: 'INACTIVE', rowVersion: 4 }], pagination: { nextPageToken: '' } };
  const ok = await call('GET', '/organizations/o1/members?status=INACTIVE&pageSize=25');
  assert.equal(ok.status, 200);
  assert.equal((await ok.json()).data[0].rowVersion, 4);
  assert.equal((await call('GET', '/organizations/o1/members?status=DELETED')).status, 404);
  assert.equal((await call('GET', '/organizations/o1/courses?status=ACTIVE')).status, 404);
});
