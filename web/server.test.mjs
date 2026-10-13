import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import http from 'node:http';
import { createAppServer } from './server.mjs';

test('sirve frontend, verifica backend real y restringe el proxy', async t => {
  const root = await mkdtemp(join(tmpdir(), 'bpm-web-'));
  await writeFile(join(root, 'index.html'), '<html lang="es">BPM Aula</html>');
  await writeFile(join(root, 'sample.wasm'), 'wasm');
  let upstreamCalls = 0;
  const backend = http.createServer((req, res) => {
    upstreamCalls++;
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify(req.url === '/health'
      ? { status: 'ok', version: 'test123', internalSecret: 'must-not-leak' }
      : { status: 'ready' }));
  });
  backend.listen(0, '127.0.0.1');
  await once(backend, 'listening');
  const app = createAppServer({ distDir: root,
    apiBaseUrl: 'http://127.0.0.1:' + backend.address().port, allowInsecureForTests: true });
  app.listen(0, '127.0.0.1');
  await once(app, 'listening');
  t.after(async () => {
    app.closeAllConnections(); backend.closeAllConnections();
    await Promise.all([new Promise(resolve => app.close(resolve)), new Promise(resolve => backend.close(resolve))]);
    await rm(root, { recursive: true, force: true });
  });
  const base = 'http://127.0.0.1:' + app.address().port;
  const home = await fetch(base);
  assert.equal(home.status, 200);
  assert.match(home.headers.get('content-security-policy'), /wasm-unsafe-eval/);
  assert.equal((await fetch(base + '/healthz')).status, 200);
  assert.equal((await fetch(base + '/sample.wasm')).headers.get('content-type'), 'application/wasm');
  assert.deepEqual(await (await fetch(base + '/backend/health')).json(), { status: 'ok', version: 'test123' });
  assert.deepEqual(await (await fetch(base + '/backend/ready')).json(), { status: 'ready' });
  assert.equal(upstreamCalls, 2);
  assert.equal((await fetch(base + '/backend/admin')).status, 404);
  assert.equal((await fetch(base + '/backend/health?url=https://attacker.example')).status, 404);
  assert.equal((await fetch(base + '/backend/health', { method: 'POST' })).status, 405);
  assert.equal(upstreamCalls, 2);
  assert.equal((await fetch(base + '/missing.js')).status, 404);
  assert.equal((await fetch(base + '/future-route')).status, 200);
});

test('rechaza orígenes inseguros o con credenciales', () => {
  for (const apiBaseUrl of ['http://example.com', 'https://user:pass@example.com', 'https://example.com/api']) {
    assert.throws(() => createAppServer({ apiBaseUrl }));
  }
});

test('perfil autenticado: solo Bearer, sin cookies ni errores internos', async t => {
  const root = await mkdtemp(join(tmpdir(), 'bpm-login-'));
  await writeFile(join(root, 'index.html'), '<html>BPM</html>');
  let calls = 0;
  const backend = http.createServer((req, res) => {
    calls++;
    assert.equal(req.url, '/api/v1/me');
    assert.equal(req.headers.cookie, undefined);
    assert.equal(req.headers['x-organization-id'], undefined);
    res.setHeader('Content-Type', 'application/json');
    if (req.headers.authorization === 'Bearer invalid.token') {
      res.writeHead(403);
      return res.end(JSON.stringify({ errors: [{ code: 'USER_NOT_REGISTERED', message: 'SQL internal secret' }] }));
    }
    assert.equal(req.headers.authorization, 'Bearer test.id.token');
    res.end(JSON.stringify({ data: { id: 'uid', email: 'test@example.com', displayName: 'Persona',
      internalSecret: 'hidden', organizations: [{ id: 'org', legalName: 'BPM', role: 'WORKER', secret: 'hidden' }] } }));
  });
  backend.listen(0, '127.0.0.1');
  await once(backend, 'listening');
  const app = createAppServer({ distDir: root, firebaseApiKey: 'public-key-for-tests-only',
    apiBaseUrl: 'http://127.0.0.1:' + backend.address().port, allowInsecureForTests: true });
  app.listen(0, '127.0.0.1');
  await once(app, 'listening');
  t.after(async () => {
    app.closeAllConnections(); backend.closeAllConnections();
    await Promise.all([new Promise(resolve => app.close(resolve)), new Promise(resolve => backend.close(resolve))]);
    await rm(root, { recursive: true, force: true });
  });
  const base = 'http://127.0.0.1:' + app.address().port;
  const config = await fetch(base + '/config.json');
  assert.equal(config.headers.get('cache-control'), 'no-store');
  assert.deepEqual(await config.json(), { firebaseApiKey: 'public-key-for-tests-only' });
  assert.equal((await fetch(base + '/backend/api/v1/me')).status, 401);
  assert.equal(calls, 0);
  const result = await fetch(base + '/backend/api/v1/me', { headers: {
    Authorization: 'Bearer test.id.token', Cookie: 'private=true', 'X-Organization-ID': 'untrusted',
  } });
  assert.equal(result.status, 200);
  assert.deepEqual(await result.json(), { data: { id: 'uid', email: 'test@example.com', displayName: 'Persona',
    organizations: [{ id: 'org', legalName: 'BPM', role: 'WORKER' }] } });
  const denied = await fetch(base + '/backend/api/v1/me', { headers: { Authorization: 'Bearer invalid.token' } });
  assert.equal(denied.status, 403);
  const error = await denied.json();
  assert.equal(error.errors[0].code, 'USER_NOT_REGISTERED');
  assert.equal(JSON.stringify(error).includes('SQL'), false);
  assert.equal((await fetch(base + '/backend/api/v1/me?organizationId=other')).status, 404);
  assert.equal((await fetch(base + '/backend/api/v1/me', { method: 'POST' })).status, 405);
  assert.equal(calls, 2);
});

test('rechaza secretos Admin como API key pública', () => {
  assert.throws(() => createAppServer({ firebaseApiKey: '{"private_key":"secret"}' }));
});
