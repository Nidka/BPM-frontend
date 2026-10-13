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
