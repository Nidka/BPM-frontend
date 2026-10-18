import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';

test('catálogo: ruta de lectura cerrada, campos públicos, criterios y fallos seguros', async t => {
  let calls = 0;
  let status = 200;
  const item = { id: 'question', sectionName: 'Conservación', label: 'Temperatura', responseType: 'TEMPERATURE',
    displayOrder: 1, required: true, unit: '°C', minimumValue: -18.5, maximumValue: -15, privateData: 'secret' };
  const template = { id: 'template', name: 'Apertura', description: '', area: 'Cocina', versionId: 'version',
    versionNumber: 1, items: [item], privateData: 'secret' };
  let data = [template];
  const backend = http.createServer((req, res) => {
    calls++;
    assert.equal(req.url, '/api/v1/organizations/org/checklists');
    assert.equal(req.method, 'GET');
    assert.equal(req.headers.authorization, 'Bearer test.token');
    for (const header of ['cookie', 'x-organization-id', 'x-role']) assert.equal(req.headers[header], undefined);
    res.writeHead(status, { 'Content-Type': 'application/json', 'X-BPM-Session-Expires-At': '1900000000' });
    res.end(JSON.stringify(status === 200 ? { data } : { errors: [{ message: 'private SQL token' }] }));
  });
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
  const app = createAppServer({ apiBaseUrl: 'http://127.0.0.1:' + backend.address().port, allowInsecureForTests: true });
  app.listen(0, '127.0.0.1'); await once(app, 'listening');
  t.after(async () => {
    app.closeAllConnections(); backend.closeAllConnections();
    await Promise.all([new Promise(resolve => app.close(resolve)), new Promise(resolve => backend.close(resolve))]);
  });
  const url = 'http://127.0.0.1:' + app.address().port + '/backend/api/v1/organizations/org/checklists';
  const headers = { Authorization: 'Bearer test.token', Cookie: 'secret', 'X-Organization-Id': 'foreign', 'X-Role': 'ADMIN' };
  assert.equal((await fetch(url)).status, 401);
  assert.equal((await fetch(url + '?destination=https://evil.example', { headers })).status, 404);
  assert.equal((await fetch(url, { method: 'POST', headers, body: '{}' })).status, 405);
  assert.equal((await fetch(url, { method: 'HEAD', headers })).status, 405);
  assert.equal((await fetch(url + '/private/extra', { headers })).status, 404);
  assert.equal(calls, 0);
  const success = await fetch(url, { headers });
  assert.equal(success.status, 200);
  assert.equal(success.headers.get('x-bpm-session-expires-at'), '1900000000');
  const result = await success.json();
  assert.equal(result.data[0].items[0].minimumValue, -18.5);
  assert.equal(result.data[0].items[0].maximumValue, -15);
  assert.equal(JSON.stringify(result).includes('secret'), false);
  assert.equal(result.data[0].items[0].evidenceRequiredOnFailure, false);
  data = [];
  assert.deepEqual(await (await fetch(url, { headers })).json(), { data: [] });
  for (const code of [401, 403, 404, 429, 500]) {
    status = code;
    const failure = await fetch(url, { headers });
    assert.equal(failure.status, code === 500 ? 503 : code);
    assert.equal((await failure.text()).includes('private'), false);
  }
  status = 200;
  for (const invalid of [null, Array(101).fill(template), [{ ...template, items: [{ ...item, maximumValue: -30 }] }],
    [{ ...template, items: [{ ...item, responseType: 'UNKNOWN' }] }], [{ ...template, items: [item, item] }],
    [{ ...template, items: [{ ...item, required: 'yes' }] }]]) {
    data = invalid;
    assert.equal((await fetch(url, { headers })).status, 502);
  }
});
