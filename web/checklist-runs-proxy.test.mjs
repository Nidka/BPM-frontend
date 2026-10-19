import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';

test('ejecuciones: rutas cerradas, idempotencia, aislamiento y errores sanitizados', async t => {
  const requests = [];
  let status = 200;
  let tenant = 'org';
  const backend = http.createServer((req, res) => {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      requests.push({ path: req.url, method: req.method, key: req.headers['idempotency-key'], body });
      assert.equal(req.headers.authorization, 'Bearer test.token');
      assert.equal(req.headers.cookie, undefined);
      assert.equal(req.headers['x-role'], undefined);
      res.writeHead(status, { 'Content-Type': 'application/json', 'X-BPM-Session-Expires-At': '1900000000' });
      res.end(JSON.stringify(status === 200 ? { data: {
        id: 'run', organizationId: tenant, locationId: 'site', templateVersionId: 'version', performedBy: 'user',
        rowVersion: 2, status: 'DRAFT', items: [{ id: 'item', sectionName: 'Higiene', label: 'Limpieza', responseType: 'COMPLIANCE', displayOrder: 1, required: true }],
        answers: [{ itemId: 'item', complianceResult: 'COMPLIANT' }], privateData: 'secret',
      } } : { errors: [{ message: 'private SQL token' }] }));
    });
  });
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
  const app = createAppServer({ apiBaseUrl: `http://127.0.0.1:${backend.address().port}`, allowInsecureForTests: true });
  app.listen(0, '127.0.0.1'); await once(app, 'listening');
  t.after(async () => {
    app.closeAllConnections(); backend.closeAllConnections();
    await Promise.all([new Promise(r => app.close(r)), new Promise(r => backend.close(r))]);
  });
  const base = `http://127.0.0.1:${app.address().port}/backend/api/v1/organizations/org/checklist-runs`;
  const headers = { Authorization: 'Bearer test.token', 'Content-Type': 'application/json', Cookie: 'secret', 'X-Role': 'ADMIN' };
  assert.equal((await fetch(base + '/run')).status, 401);
  assert.equal((await fetch(base + '/run?token=private', { headers })).status, 404);
  assert.equal((await fetch(base + '/run', { headers, method: 'DELETE' })).status, 405);
  assert.equal((await fetch(base + '/run/answers', { headers, method: 'PUT', body: '{}' })).status, 400);
  const keyHeaders = { ...headers, 'Idempotency-Key': 'operation-unique' };
  assert.equal((await fetch(base + '/run/answers', { headers: { ...keyHeaders, Origin: 'https://foreign.example' }, method: 'PUT', body: '{}' })).status, 403);
  assert.equal((await fetch(base + '/run/submit', { headers: keyHeaders, method: 'POST', body: JSON.stringify({ rowVersion: 1, performedBy: 'other' }) })).status, 400);
  assert.equal((await fetch(base + '/run/answers', { headers: keyHeaders, method: 'PUT', body: JSON.stringify({ rowVersion: 1, answers: [{ itemId: 'item', actor: 'other' }] }) })).status, 400);
  assert.equal(requests.length, 0);
  const response = await fetch(base + '/run', { headers });
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('x-bpm-session-expires-at'), '1900000000');
  assert.equal(JSON.stringify(await response.json()).includes('secret'), false);
  const body = JSON.stringify({ rowVersion: 1, answers: [{ itemId: 'item', complianceResult: 'COMPLIANT' }] });
  assert.equal((await fetch(base + '/run/answers', { headers: keyHeaders, method: 'PUT', body })).status, 200);
  assert.equal(requests.at(-1).key, 'operation-unique');
  assert.equal(requests.at(-1).method, 'PUT');
  assert.equal(JSON.parse(requests.at(-1).body).rowVersion, 1);
  status = 409;
  assert.equal((await fetch(base + '/run/submit', { headers: keyHeaders, method: 'POST', body: '{"rowVersion":2}' })).status, 409);
  status = 503;
  const unavailable = await fetch(base + '/run', { headers });
  assert.equal(unavailable.status, 503);
  assert.equal((await unavailable.text()).includes('SQL'), false);
  status = 200; tenant = 'foreign';
  assert.equal((await fetch(base + '/run', { headers })).status, 503);
});
