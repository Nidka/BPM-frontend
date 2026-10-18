import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';

test('session proxy: closed routes, safe errors, numeric deadlines and production HSTS', async t => {
  const calls = [];
  const upstream = http.createServer((req, res) => {
    calls.push(req.url);
    assert.equal(req.headers.cookie, undefined);
    assert.equal(req.headers['x-organization-id'], undefined);
    assert.equal(req.method, 'POST');
    res.setHeader('Content-Type', 'application/json');
    res.setHeader('X-BPM-Session-Expires-At', '1792300000');
    res.setHeader('X-BPM-Session-Idle-Expires-At', 'private-invalid-value');
    if (req.headers.authorization === 'Bearer expired') {
      res.writeHead(401);
      return res.end(JSON.stringify({ errors: [{ code: 'SESSION_EXPIRED', message: 'private provider detail' }] }));
    }
    res.end(JSON.stringify({ data: { status: req.url.endsWith('/logout') ? 'SIGNED_OUT' : 'ACTIVE', private: 'must-not-leak' } }));
  });
  upstream.listen(0, '127.0.0.1'); await once(upstream, 'listening');
  const app = createAppServer({ apiBaseUrl: 'http://127.0.0.1:' + upstream.address().port, allowInsecureForTests: true, hstsEnabled: true });
  app.listen(0, '127.0.0.1'); await once(app, 'listening');
  t.after(async () => {
    app.closeAllConnections(); upstream.closeAllConnections();
    await Promise.all([new Promise(resolve => app.close(resolve)), new Promise(resolve => upstream.close(resolve))]);
  });
  const base = 'http://127.0.0.1:' + app.address().port;
  const request = (path, token = 'valid', extra = {}) => fetch(base + path, {
    method: 'POST', headers: { Authorization: 'Bearer ' + token, Cookie: 'private-cookie', 'X-Organization-ID': 'spoofed', ...extra },
  });
  const result = await request('/backend/api/v1/auth/activity');
  assert.equal(result.status, 200);
  assert.equal(result.headers.get('strict-transport-security'), 'max-age=31536000');
  assert.equal(result.headers.get('x-bpm-session-expires-at'), '1792300000');
  assert.equal(result.headers.get('x-bpm-session-idle-expires-at'), null);
  assert.deepEqual(await result.json(), { data: { status: 'ACTIVE' } });
  assert.deepEqual(await (await request('/backend/api/v1/auth/logout')).json(), { data: { status: 'SIGNED_OUT' } });
  const expired = await request('/backend/api/v1/auth/activity', 'expired');
  assert.equal(expired.status, 401);
  const body = await expired.text();
  assert.match(body, /SESSION_EXPIRED/); assert.doesNotMatch(body, /private/);
  assert.equal((await request('/backend/api/v1/auth/activity', 'valid', { Origin: 'https://attacker.example' })).status, 403);
  assert.equal((await fetch(base + '/backend/api/v1/auth/activity', { headers: { Authorization: 'Bearer valid' } })).status, 405);
  assert.equal((await request('/backend/api/v1/auth/activity?target=other')).status, 405);
  assert.equal(calls.length, 3);
});
