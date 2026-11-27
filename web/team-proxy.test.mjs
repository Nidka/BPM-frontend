import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { teamRoute, proxyTeam } from './team-proxy.mjs';

const id = 'a0000000-0000-4000-8000-000000000001', site = 'b0000000-0000-4000-8000-000000000001';
const base = '/backend/api/v1/organizations/org_1/';
const headers = { authorization: 'Bearer fixture.token', host: 'bpm.test', 'content-type': 'application/json', 'idempotency-key': 'stable-key-0001' };
async function invoke(path, { method = 'GET', body, requestHeaders = {}, payload = {}, status = 200 } = {}) {
  const saved = globalThis.fetch, calls = [];
  globalThis.fetch = async (url, options) => { calls.push({ url: String(url), ...options }); return new Response(JSON.stringify(payload), { status, headers: { 'Content-Type': 'application/json' } }); };
  const request = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))]);
  request.method = method; request.headers = { ...headers, ...requestHeaders };
  if (body === undefined) delete request.headers['content-type'];
  else request.headers['content-length'] = String(Buffer.byteLength(JSON.stringify(body)));
  let answer;
  try { await proxyTeam(request, new URL('http://bpm.test' + path), new URL('https://api.test/'), (code, data) => { answer = { status: code, body: data ? JSON.parse(data) : null }; }, { setHeader: () => {} }); }
  finally { globalThis.fetch = saved; }
  return { ...answer, calls };
}

test('equipo: rutas cerradas', () => {
  for (const p of ['staff-messages', 'staff-messages/contact', 'notifications/test']) assert.equal(teamRoute(base + p), true, p);
  for (const p of ['staff-messages/x', 'notifications/tests', 'staff']) assert.equal(teamRoute(base + p), false, p);
});

test('avisar a mi jefe: valida el tipo y reenvía la clave', async () => {
  const sent = await invoke(base + 'staff-messages', { method: 'POST', body: { id, locationId: site, kind: 'SUPPLY', text: 'Falta aceite' }, payload: { data: { id, locationId: site, kind: 'SUPPLY', text: 'Falta aceite', createdAt: '2026-11-27T15:00:00Z', extra: 1 } } });
  assert.equal(sent.status, 200); assert.equal(sent.body.data.extra, undefined); assert.equal(sent.calls[0].headers['Idempotency-Key'], 'stable-key-0001');
  assert.equal((await invoke(base + 'staff-messages', { method: 'POST', body: { id, locationId: site, kind: 'OTRO' } })).status, 400);
  assert.equal((await invoke(base + 'staff-messages', { method: 'POST', body: { id, locationId: site, kind: 'HELP' }, requestHeaders: { 'idempotency-key': '' } })).status, 400);
});

test('contacto del jefe y prueba de notificación', async () => {
  const c = await invoke(base + 'staff-messages/contact?locationId=' + site, { payload: { data: { name: 'Ana', role: 'CHEF', whatsapp: '51987654321', email: 'x' } } });
  assert.deepEqual(c.body.data, { name: 'Ana', role: 'CHEF', whatsapp: '51987654321' });
  assert.deepEqual((await invoke(base + 'staff-messages/contact?locationId=' + site, { payload: { data: { name: null, role: null, whatsapp: null } } })).body.data, { name: null, role: null, whatsapp: null });
  assert.equal((await invoke(base + 'staff-messages/contact')).status, 400);
  const t = await invoke(base + 'notifications/test', { method: 'POST', status: 202, payload: { data: { status: 'QUEUED' } } });
  assert.equal(t.status, 202);
  const limited = await invoke(base + 'notifications/test', { method: 'POST', status: 429, payload: { errors: [{ code: 'RATE_LIMITED' }] } });
  assert.equal(limited.status, 429);
  assert.equal((await invoke(base + 'notifications/test')).status, 405);
});
