import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';

async function harness(t, handler) {
  const calls = [];
  const backend = http.createServer(async (req, res) => {
    let raw = ''; for await (const chunk of req) raw += chunk;
    calls.push({ method: req.method, url: req.url, headers: req.headers, raw });
    res.setHeader('Content-Type', 'application/json');
    handler(req, res, raw);
  });
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
  const app = createAppServer({ apiBaseUrl: 'http://127.0.0.1:' + backend.address().port, allowInsecureForTests: true });
  app.listen(0, '127.0.0.1'); await once(app, 'listening');
  t.after(async () => { app.closeAllConnections(); backend.closeAllConnections(); await Promise.all([
    new Promise(resolve => app.close(resolve)), new Promise(resolve => backend.close(resolve))]); });
  return { base: 'http://127.0.0.1:' + app.address().port, calls };
}

const json = { 'Content-Type': 'application/json' };

test('verification proxy forwards only the Bearer, ignores any body and returns a fixed status', async t => {
  const { base, calls } = await harness(t, (req, res) => { res.statusCode = 202; res.end(JSON.stringify({ data: { status: 'SENT', secret: 'x' } })); });
  const url = base + '/backend/api/v1/auth/email/verification';
  assert.equal((await fetch(url, { method: 'POST' })).status, 401);
  assert.equal((await fetch(url, { method: 'POST', headers: { Authorization: 'Basic abc' } })).status, 401);
  assert.equal((await fetch(url, { method: 'POST', headers: { Authorization: 'Bearer a.b.c', Origin: 'https://other.example' } })).status, 403);
  assert.equal((await fetch(url, { headers: { Authorization: 'Bearer a.b.c' } })).status, 405);
  assert.equal((await fetch(url + '?x=1', { method: 'POST', headers: { Authorization: 'Bearer a.b.c' } })).status, 400);
  assert.equal(calls.length, 0);
  const ok = await fetch(url, { method: 'POST', headers: { Authorization: 'Bearer a.b.c', ...json, Cookie: 'private' }, body: '{"email":"victima@example.com"}' });
  assert.equal(ok.status, 202);
  assert.deepEqual(await ok.json(), { data: { status: 'SENT' } });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, '/api/v1/auth/email/verification');
  assert.equal(calls[0].headers.authorization, 'Bearer a.b.c');
  assert.equal(calls[0].headers.cookie, undefined);
  assert.equal(calls[0].raw, '', 'el cuerpo del navegador no se reenvía');
});

test('password reset proxy is public, accepts only {email} and forwards a minimal body', async t => {
  const { base, calls } = await harness(t, (req, res) => { res.statusCode = 202; res.end(JSON.stringify({ data: { status: 'ACCEPTED' } })); });
  const url = base + '/backend/api/v1/auth/email/password-reset';
  const post = (body, headers = json) => fetch(url, { method: 'POST', headers, body });
  assert.equal((await post('{"email":"a@b.pe"}', {})).status, 415);
  for (const body of ['{}', '{"email":"a@b.pe","x":1}', '{"email":5}', '{', '[]', '{"email":"a@b.pe","email":"c@d.pe"}'])
    assert.equal((await post(body)).status, 400, body);
  assert.equal((await post('{"email":"a@b.pe"}', { ...json, Origin: 'https://other.example' })).status, 403);
  assert.equal(calls.length, 0);
  const ok = await post('{"email":"a@b.pe"}', { ...json, Authorization: 'Bearer should.not.pass' });
  assert.equal(ok.status, 202);
  assert.deepEqual(await ok.json(), { data: { status: 'ACCEPTED' } });
  assert.equal(calls[0].raw, '{"email":"a@b.pe"}');
  assert.equal(calls[0].headers.authorization, undefined);
});

test('proxy passes MAIL_UNAVAILABLE and rate limits, hides every other backend detail', async t => {
  let mode = 'mail';
  const { base } = await harness(t, (req, res) => {
    if (mode === 'mail') { res.statusCode = 503; res.end(JSON.stringify({ errors: [{ code: 'MAIL_UNAVAILABLE', message: 'detalle interno' }] })); }
    else if (mode === 'limit') { res.statusCode = 429; res.end(JSON.stringify({ errors: [{ code: 'RATE_LIMITED' }] })); }
    else if (mode === 'invalid') { res.statusCode = 422; res.end(JSON.stringify({ errors: [{ code: 'VALIDATION_ERROR', fields: { email: 'x', other: 'y' } }] })); }
    else if (mode === 'html') { res.statusCode = 200; res.end('<html>'); }
    else { res.statusCode = 500; res.end('boom a@b.pe'); }
  });
  const call = () => fetch(base + '/backend/api/v1/auth/email/password-reset', { method: 'POST', headers: json, body: '{"email":"a@b.pe"}' });
  let r = await call(); assert.equal(r.status, 503); assert.equal((await r.json()).errors[0].code, 'MAIL_UNAVAILABLE');
  mode = 'limit'; r = await call(); assert.equal(r.status, 429); assert.equal((await r.json()).errors[0].code, 'RATE_LIMITED');
  mode = 'invalid'; r = await call(); assert.equal(r.status, 422);
  assert.deepEqual(Object.keys((await r.json()).errors[0].fields), ['email']);
  mode = 'html'; r = await call(); assert.equal(r.status, 503);
  mode = 'boom'; r = await call(); const body = await r.text(); assert.equal(r.status, 503); assert.ok(!body.includes('boom') && !body.includes('a@b.pe'));
});
