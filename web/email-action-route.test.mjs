import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';

test('/auth/accion sirve la app con cabeceras seguras para enlaces de correo de Firebase', async t => {
  const root = await mkdtemp(join(tmpdir(), 'bpm-accion-'));
  await writeFile(join(root, 'index.html'), '<html lang="es">BPM acción</html>');
  const app = createAppServer({ distDir: root, apiBaseUrl: 'http://127.0.0.1:9', allowInsecureForTests: true });
  app.listen(0, '127.0.0.1');
  await once(app, 'listening');
  t.after(async () => { app.closeAllConnections(); await new Promise(resolve => app.close(resolve)); await rm(root, { recursive: true, force: true }); });
  const base = 'http://127.0.0.1:' + app.address().port;

  for (const path of [
    '/auth/accion',
    '/auth/accion/',
    '/auth/accion?mode=verifyEmail&oobCode=abc&apiKey=AIzaFake&continueUrl=https%3A%2F%2Fappbpm.com&lang=es',
    '/auth/accion?mode=resetPassword&oobCode=abc',
    '/auth/accion?mode=recoverEmail&oobCode=abc',
  ]) {
    const response = await fetch(base + path);
    assert.equal(response.status, 200, path);
    assert.match(response.headers.get('content-type'), /text\/html/, path);
    assert.equal(await response.text(), '<html lang="es">BPM acción</html>', path);
    // El código de un solo uso no se cachea, no se indexa y no sale en el Referer.
    assert.equal(response.headers.get('cache-control'), 'no-store', path);
    assert.match(response.headers.get('x-robots-tag'), /noindex/, path);
    assert.equal(response.headers.get('referrer-policy'), 'no-referrer', path);
    assert.equal(response.headers.get('x-frame-options'), 'DENY', path);
    // La página llama a Firebase (REST) desde el navegador: la CSP lo permite y nada más amplio.
    const csp = response.headers.get('content-security-policy');
    assert.match(csp, /connect-src[^;]*https:\/\/identitytoolkit\.googleapis\.com/, path);
    assert.doesNotMatch(csp, /script-src[^;]*'unsafe-inline'/, path);
  }
  assert.equal((await fetch(base + '/auth/accion', { method: 'HEAD' })).status, 200);
  // Otras rutas bajo /auth/ no se reinterpretan como la página de acciones.
  assert.equal((await fetch(base + '/auth/accion.js')).status, 404);
  assert.equal((await fetch(base + '/auth/accion', { method: 'POST', body: '{}' })).status, 405);
});

test('los recursos relativos de Compose bajo /auth/accion y /verificar-cumplimiento se sirven desde la raíz', async t => {
  const root = await mkdtemp(join(tmpdir(), 'bpm-accion-res-'));
  await writeFile(join(root, 'index.html'), '<html lang="es">BPM</html>');
  await mkdir(join(root, 'composeResources', 'pe.bpm.resources', 'font'), { recursive: true });
  await writeFile(join(root, 'composeResources', 'pe.bpm.resources', 'font', 'baloo2.ttf'), 'fuente');
  const app = createAppServer({ distDir: root, apiBaseUrl: 'http://127.0.0.1:9', allowInsecureForTests: true });
  app.listen(0, '127.0.0.1');
  await once(app, 'listening');
  t.after(async () => { app.closeAllConnections(); await new Promise(resolve => app.close(resolve)); await rm(root, { recursive: true, force: true }); });
  const base = 'http://127.0.0.1:' + app.address().port;
  const resource = 'composeResources/pe.bpm.resources/font/baloo2.ttf';
  for (const prefix of ['/', '/auth/', '/auth/accion/', '/verificar-cumplimiento/ABCDEFGHIJKLMNOPQRSTUVWX/']) {
    const response = await fetch(base + prefix + resource);
    assert.equal(response.status, 200, prefix);
    assert.equal(await response.text(), 'fuente', prefix);
  }
  // Solo se reconducen los recursos de Compose; no se abre el resto del árbol.
  assert.equal((await fetch(base + '/auth/accion/otro.ttf')).status, 404);
});
