import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import http from 'node:http';
import { gunzipSync, brotliDecompressSync } from 'node:zlib';
import { createAppServer } from './server.mjs';

test('archivos estáticos: compresión, caché inmutable con hash y revalidación', async t => {
  const root = await mkdtemp(join(tmpdir(), 'bpm-static-'));
  const wasm = Buffer.alloc(200_000, 7);
  await writeFile(join(root, 'index.html'), '<html lang="es">' + 'BPM Aula '.repeat(300) + '</html>');
  await writeFile(join(root, '0a9ab08110b046c37ad9.wasm'), wasm);
  await writeFile(join(root, 'composeApp.js'), 'console.log("bpm");'.repeat(200));
  await writeFile(join(root, 'logo.png'), Buffer.alloc(5000, 1));
  await mkdir(join(root, 'composeResources'), { recursive: true });
  await writeFile(join(root, 'composeResources', 'ic_users.xml'), '<vector/>');
  const app = createAppServer({ distDir: root, apiBaseUrl: 'http://127.0.0.1:9', allowInsecureForTests: true });
  app.listen(0, '127.0.0.1');
  await once(app, 'listening');
  t.after(async () => { app.closeAllConnections(); await new Promise(resolve => app.close(resolve)); await rm(root, { recursive: true, force: true }); });
  const base = 'http://127.0.0.1:' + app.address().port;
  // http.get entrega el cuerpo tal cual llega; fetch lo descomprimiría y ocultaría el ahorro.
  const raw = (path, headers = {}) => new Promise((resolve, reject) => {
    http.get(base + path, { headers }, response => {
      const chunks = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => {
        const body = Buffer.concat(chunks);
        resolve({ status: response.statusCode, headers: { get: name => response.headers[name.toLowerCase()] ?? null }, arrayBuffer: async () => body });
      });
    }).on('error', reject);
  });

  const hashed = await raw('/0a9ab08110b046c37ad9.wasm', { 'Accept-Encoding': 'br, gzip' });
  assert.equal(hashed.status, 200);
  assert.equal(hashed.headers.get('cache-control'), 'public, max-age=31536000, immutable');
  assert.equal(hashed.headers.get('content-type'), 'application/wasm');
  assert.equal(hashed.headers.get('vary'), 'Accept-Encoding');
  if (hashed.headers.get('content-encoding') === 'br') {
    const body = Buffer.from(await hashed.arrayBuffer());
    assert.ok(body.length < wasm.length / 10);
    assert.deepEqual(brotliDecompressSync(body), wasm);
  } else assert.equal(Buffer.from(await hashed.arrayBuffer()).length, wasm.length);

  const gz = await raw('/composeApp.js', { 'Accept-Encoding': 'gzip' });
  assert.equal(gz.headers.get('content-encoding'), 'gzip');
  assert.equal(gz.headers.get('cache-control'), 'no-cache');
  assert.match(gunzipSync(Buffer.from(await gz.arrayBuffer())).toString(), /console\.log/);

  const plain = await raw('/composeApp.js', { 'Accept-Encoding': 'identity' });
  assert.equal(plain.headers.get('content-encoding'), null);

  // Los recursos de Compose (iconos) se sirven con caché de una hora y revalidación en segundo plano, no con revalidación por cada uso.
  const icon = await raw('/composeResources/ic_users.xml');
  assert.equal(icon.headers.get('cache-control'), 'public, max-age=3600, stale-while-revalidate=86400');

  const png = await raw('/logo.png', { 'Accept-Encoding': 'gzip, br' });
  assert.equal(png.headers.get('content-encoding'), null);

  const etag = (await raw('/index.html')).headers.get('etag');
  assert.ok(etag);
  const revalidated = await raw('/index.html', { 'If-None-Match': etag });
  assert.equal(revalidated.status, 304);
  assert.equal((await raw('/missing.js')).status, 404);
});
