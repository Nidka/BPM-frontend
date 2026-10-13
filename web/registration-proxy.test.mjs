import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';

test('registration proxy accepts only bounded name JSON and Bearer, no credentials or roles', async t => {
  let calls = 0;
  const backend = http.createServer(async (req, res) => {
    calls++;
    assert.equal(req.method, 'POST'); assert.equal(req.url, '/api/v1/auth/register');
    assert.equal(req.headers.authorization, 'Bearer firebase.test.token');
    assert.equal(req.headers.cookie, undefined); assert.equal(req.headers['x-organization-id'], undefined);
    let raw = ''; for await (const chunk of req) raw += chunk;
    assert.deepEqual(JSON.parse(raw), { displayName: 'Persona' });
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ data: { id: 'uid', email: 'person@example.com', displayName: 'Persona', organizations: [], privateData: 'hidden' } }));
  });
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
  const app = createAppServer({ apiBaseUrl: 'http://127.0.0.1:' + backend.address().port, allowInsecureForTests: true });
  app.listen(0, '127.0.0.1'); await once(app, 'listening');
  t.after(async () => { app.closeAllConnections(); backend.closeAllConnections(); await Promise.all([
    new Promise(resolve => app.close(resolve)), new Promise(resolve => backend.close(resolve))]); });
  const base = 'http://127.0.0.1:' + app.address().port;
  const url = base + '/backend/api/v1/auth/register';
  const headers = { Authorization: 'Bearer firebase.test.token', 'Content-Type': 'application/json', Origin: base,
    Cookie: 'private', 'X-Organization-ID': 'untrusted' };
  assert.equal((await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).status, 401);
  for (const body of ['{}', '{', '{"displayName":"Persona","platformRole":"SUPER_ADMIN"}',
    '{"displayName":"Persona","password":"private"}', JSON.stringify({ displayName: 'x'.repeat(5000) })]) {
    assert.equal((await fetch(url, { method: 'POST', headers, body })).status, 400);
  }
  assert.equal((await fetch(url, { method: 'POST', headers: { ...headers, Origin: 'https://other.example' }, body: '{"displayName":"Persona"}' })).status, 403);
  for (const Origin of ['null', 'invalid', 'https://user:secret@' + app.address().address]) {
    const denied = await fetch(url, { method: 'POST', headers: { ...headers, Origin }, body: '{"displayName":"Persona"}' });
    assert.equal(denied.status, 403);
    assert.deepEqual(await denied.json(), { errors: [{ code: 'ORIGIN_NOT_ALLOWED', message: 'Origen no permitido.', fields: {} }] });
  }
  assert.equal((await fetch(url)).status, 405);
  assert.equal(calls, 0);
  const result = await fetch(url, { method: 'POST', headers, body: '{"displayName":"Persona"}' });
  assert.equal(result.status, 200);
  assert.deepEqual(await result.json(), { data: { id: 'uid', email: 'person@example.com', displayName: 'Persona', organizations: [] } });
  assert.equal(calls, 1);
});
