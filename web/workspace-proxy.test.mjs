import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';

test('workspace proxy: lista cerrada, Bearer, campos públicos y errores sanitizados', async t => {
  let calls = 0;
  const org = { id: 'org', legalName: 'BPM', tradeName: '', businessType: 'RESTAURANT', taxId: '', contactName: '', contactPhone: '', contactEmail: '', secret: 'hidden' };
  const backend = http.createServer(async (req, res) => {
    calls++;
    assert.equal(req.headers.authorization, 'Bearer test.token');
    assert.equal(req.headers.cookie, undefined);
    res.setHeader('Content-Type', 'application/json');
    if (req.method === 'POST') {
      res.writeHead(422);
      return res.end(JSON.stringify({ errors: [{ code: 'VALIDATION_ERROR', message: 'SQL private', fields: { name: 'secret', password: 'private' } }] }));
    }
    if (req.url.endsWith('/workspace')) return res.end(JSON.stringify({ data: { organization: org, role: 'ADMIN', locations: [], members: [], courses: [], checklists: [], incidents: [], secret: 'hidden' } }));
    res.end(JSON.stringify({ data: [org] }));
  });
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
  const app = createAppServer({ apiBaseUrl: 'http://127.0.0.1:' + backend.address().port, allowInsecureForTests: true });
  app.listen(0, '127.0.0.1'); await once(app, 'listening');
  t.after(async () => {
    app.closeAllConnections(); backend.closeAllConnections();
    await Promise.all([new Promise(r => app.close(r)), new Promise(r => backend.close(r))]);
  });
  const base = 'http://127.0.0.1:' + app.address().port + '/backend/api/v1/organizations';
  const headers = { Authorization: 'Bearer test.token', 'Content-Type': 'application/json', Cookie: 'private=value' };
  assert.equal((await fetch(base)).status, 401);
  assert.equal((await fetch(base + '?destination=evil', { headers })).status, 404);
  assert.equal((await fetch(base + '/org/workspace', { method: 'POST', headers, body: '{}' })).status, 405);
  assert.equal((await fetch(base, { method: 'POST', headers: { ...headers, Origin: 'https://evil.example' }, body: '{}' })).status, 403);
  assert.equal((await fetch(base, { method: 'POST', headers, body: '{"platformRole":"SUPER_ADMIN"}' })).status, 400);
  assert.equal(calls, 0);
  const list = await fetch(base, { headers });
  assert.equal(list.status, 200);
  assert.equal((await list.text()).includes('hidden'), false);
  const workspace = await fetch(base + '/org/workspace', { headers });
  assert.equal(workspace.status, 200);
  assert.equal((await workspace.json()).data.role, 'ADMIN');
  const invalid = await fetch(base + '/org/locations', { method: 'POST', headers, body: '{}' });
  assert.equal(invalid.status, 422);
  const error = await invalid.json();
  assert.deepEqual(Object.keys(error.errors[0].fields), ['name']);
  assert.equal(JSON.stringify(error).includes('secret'), false);
  assert.equal(calls, 3);
});
