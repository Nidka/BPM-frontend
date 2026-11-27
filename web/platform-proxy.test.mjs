import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { platformRoute, proxyPlatform } from './platform-proxy.mjs';

const headers = { authorization: 'Bearer fixture.token', host: 'bpm.test', 'content-type': 'application/json' };
async function invoke(path, { method = 'GET', body, requestHeaders = {}, payload = {}, status = 200 } = {}) {
  const saved = globalThis.fetch, calls = [];
  globalThis.fetch = async (url, options) => { calls.push({ url: String(url), ...options }); return new Response(JSON.stringify(payload), { status, headers: { 'Content-Type': 'application/json' } }); };
  const request = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))]);
  request.method = method; request.headers = { ...headers, ...requestHeaders };
  if (body !== undefined) request.headers['content-length'] = String(Buffer.byteLength(JSON.stringify(body)));
  let answer;
  try { await proxyPlatform(request, new URL('http://bpm.test' + path), new URL('https://api.test/'), (code, data) => { answer = { status: code, body: data ? JSON.parse(data) : null }; }, { setHeader: () => {} }); }
  finally { globalThis.fetch = saved; }
  return { ...answer, calls };
}

test('plataforma: rutas cerradas', () => {
  assert.equal(platformRoute('/backend/api/v1/me/onboarding'), true);
  assert.equal(platformRoute('/backend/api/v1/platform/support'), true);
  for (const p of ['/backend/api/v1/platform', '/backend/api/v1/platform/support/x', '/backend/api/v1/me/onboarding/1', '/backend/api/v1/platform/settings']) assert.equal(platformRoute(p), false, p);
});

test('introducción: marcar y volver a mostrar, sin cuerpo', async () => {
  const at = '2026-11-27T15:00:00Z';
  const put = await invoke('/backend/api/v1/me/onboarding', { method: 'PUT', payload: { data: { onboardingSeenAt: at, extra: 1 } } });
  assert.equal(put.status, 200); assert.deepEqual(put.body.data, { onboardingSeenAt: at }); assert.equal(put.calls[0].method, 'PUT');
  assert.ok(put.calls[0].url.endsWith('/api/v1/me/onboarding'));
  const del = await invoke('/backend/api/v1/me/onboarding', { method: 'DELETE', payload: { data: { onboardingSeenAt: null } } });
  assert.deepEqual(del.body.data, { onboardingSeenAt: null });
  assert.equal((await invoke('/backend/api/v1/me/onboarding')).status, 405);
  assert.equal((await invoke('/backend/api/v1/me/onboarding', { method: 'PUT', requestHeaders: { origin: 'https://otro.pe' } })).status, 403);
  assert.equal((await invoke('/backend/api/v1/me/onboarding', { method: 'PUT', requestHeaders: { authorization: '' } })).status, 401);
});

test('soporte: lectura proyectada y edición validada', async () => {
  const get = await invoke('/backend/api/v1/platform/support', { payload: { data: { supportEmail: 'proyectobpmupn@gmail.com', supportWhatsapp: null, secret: 'x' } } });
  assert.equal(get.status, 200); assert.deepEqual(get.body.data, { supportEmail: 'proyectobpmupn@gmail.com', supportWhatsapp: null });
  const put = await invoke('/backend/api/v1/platform/support', { method: 'PUT', body: { supportEmail: 'soporte@bpm.pe', supportWhatsapp: '+51 987 654 321' },
    payload: { data: { supportEmail: 'soporte@bpm.pe', supportWhatsapp: '51987654321', updatedAt: '2026-11-27T15:00:00Z' } } });
  assert.equal(put.status, 200); assert.equal(put.body.data.supportWhatsapp, '51987654321');
  assert.deepEqual(JSON.parse(put.calls[0].body), { supportEmail: 'soporte@bpm.pe', supportWhatsapp: '+51 987 654 321' });
  assert.equal((await invoke('/backend/api/v1/platform/support', { method: 'PUT', body: { supportEmail: 'a@b.pe', role: 'SUPER_ADMIN' } })).status, 400);
  assert.equal((await invoke('/backend/api/v1/platform/support', { method: 'DELETE' })).status, 405);
  assert.equal((await invoke('/backend/api/v1/platform/support', { payload: { data: { supportEmail: 'no es correo' } } })).status, 503);
  const denied = await invoke('/backend/api/v1/platform/support', { method: 'PUT', body: { supportEmail: 'a@b.pe' }, status: 403, payload: { errors: [{ code: 'ACCESS_DENIED' }] } });
  assert.equal(denied.status, 403);
  const invalid = await invoke('/backend/api/v1/platform/support', { method: 'PUT', body: { supportEmail: 'mal' }, status: 422, payload: { errors: [{ code: 'VALIDATION_ERROR', fields: { supportEmail: 'x' } }] } });
  assert.equal(invalid.status, 422); assert.ok(invalid.body.errors[0].fields.supportEmail);
});
