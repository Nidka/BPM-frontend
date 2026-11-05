import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';

const org = '10000000-0000-4000-8000-000000000001';
const run = '20000000-0000-4000-8000-000000000002';
const item = '30000000-0000-4000-8000-000000000003';
const id = '40000000-0000-4000-8000-000000000004';
const foreign = '50000000-0000-4000-8000-000000000005';
const limit = 25 * 1024 * 1024;
const authorization = 'Bearer test.token';
const headers = { Authorization: authorization, 'Content-Type': 'application/json', Cookie: 'private.cookie', 'X-Role': 'SUPER_ADMIN' };
const upload = { id, runId: run, itemId: item, originalName: 'Cámara limpia.jpg', mimeType: 'image/jpeg', sizeBytes: 4, sha256: 'a'.repeat(64) };
const file = (status = 'AVAILABLE', overrides = {}) => ({ id, runId: run, itemId: item, originalName: upload.originalName,
  mimeType: 'image/jpeg', sizeBytes: 4, status, createdAt: '2026-11-01T00:00:00Z', uploadedAt: status === 'AVAILABLE' ? '2026-11-01T00:01:00Z' : null, ...overrides });
const signedUrl = 'https://account.r2.cloudflarestorage.com/bucket/photo?X-Amz-Signature=private-signature';
const ticket = (overrides = {}) => ({ url: signedUrl, headers: {}, expiresAt: '2099-11-01T00:10:00Z', ...overrides });
const uploadTicket = () => ticket({ headers: { 'Content-Type': ['image/jpeg'], 'If-None-Match': ['*'], 'Cache-Control': ['private, no-store'],
  'Content-Disposition': ['attachment'], 'x-amz-meta-sha256': [upload.sha256], Host: ['account.r2.cloudflarestorage.com'], 'Content-Length': ['4'] } });
const listing = (files = [file()], overrides = {}) => ({ files, maxBytes: limit, allowedMimeTypes: ['image/jpeg', 'image/png'], maxFiles: 3, uploadAvailable: true, ...overrides });

async function setup(t) {
  const state = { requests: [], status: 200, data: listing(), errorCode: '', extraHeaders: {} };
  const backend = http.createServer((req, res) => {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      state.requests.push({ path: req.url, method: req.method, headers: req.headers, body });
      res.writeHead(state.status, { 'Content-Type': 'application/json', 'X-BPM-Session-Expires-At': '1900000000', ...state.extraHeaders });
      res.end(JSON.stringify(state.status < 300 ? { data: state.data, internal: 'private.provider' } : { errors: [{ code: state.errorCode, message: 'SQL private.provider secret', fields: { bucket: 'private' } }] }));
    });
  });
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
  const app = createAppServer({ apiBaseUrl: `http://127.0.0.1:${backend.address().port}`, allowInsecureForTests: true });
  app.listen(0, '127.0.0.1'); await once(app, 'listening');
  t.after(async () => {
    app.closeAllConnections(); backend.closeAllConnections();
    await Promise.all([new Promise(resolve => app.close(resolve)), new Promise(resolve => backend.close(resolve))]);
  });
  const origin = `http://127.0.0.1:${app.address().port}`;
  return { state, origin, base: `${origin}/backend/api/v1/organizations/${org}`, evidence: `${origin}/backend/api/v1/organizations/${org}/checklist-runs/${run}/items/${item}/evidence` };
}

test('media: lista por pregunta, proyección pública, sesión y CSP exclusiva de R2', async t => {
  const { state, origin, evidence } = await setup(t);
  state.data = listing([file('AVAILABLE', { objectKey: 'private.key', bucket: 'private.bucket', url: 'private.url' })]);
  const response = await fetch(evidence, { headers });
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(response.headers.get('x-bpm-session-expires-at'), '1900000000');
  const body = await response.json();
  assert.deepEqual(body, { data: listing() });
  assert.equal(JSON.stringify(body).includes('private'), false);
  const request = state.requests[0];
  assert.equal(request.path, `/api/v1/organizations/${org}/checklist-runs/${run}/items/${item}/evidence`);
  assert.equal(request.headers.authorization, authorization);
  assert.equal(request.headers.cookie, undefined); assert.equal(request.headers['x-role'], undefined);
  const cspResponse = await fetch(`${origin}/config.json`);
  const csp = cspResponse.headers.get('content-security-policy');
  assert.match(csp, /connect-src[^;]* https:\/\/\*\.r2\.cloudflarestorage\.com(?: |;)/);
  assert.doesNotMatch(csp, /amazonaws\.com/);
  assert.doesNotMatch(csp.match(/img-src[^;]*/)[0], /r2\.cloudflarestorage\.com/);
});

test('media: reserva exacta, confirmación, descarga y retirada sin reenviar cuerpos ni credenciales adicionales', async t => {
  const { state, base } = await setup(t);
  state.data = { file: file('PENDING_UPLOAD'), upload: uploadTicket(), secret: 'private' };
  let response = await fetch(`${base}/media/uploads`, { headers, method: 'POST', body: JSON.stringify(upload) });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { data: { file: file('PENDING_UPLOAD'), upload: uploadTicket() } });
  assert.deepEqual(JSON.parse(state.requests.at(-1).body), upload);
  state.data = file();
  response = await fetch(`${base}/media/${id}/confirm`, { headers, method: 'POST', body: '{}' });
  assert.equal(response.status, 200); assert.deepEqual(await response.json(), { data: file() });
  assert.equal(state.requests.at(-1).body, '{}');
  state.data = ticket();
  response = await fetch(`${base}/media/${id}/download`, { headers, method: 'POST', body: '{}' });
  assert.equal(response.status, 200); assert.deepEqual(await response.json(), { data: ticket() });
  state.data = file('DELETED');
  response = await fetch(`${base}/media/${id}`, { headers, method: 'DELETE' });
  assert.equal(response.status, 200); assert.deepEqual(await response.json(), { data: file('DELETED') });
  assert.equal(state.requests.at(-1).body, '');
  assert.equal(state.requests.at(-1).headers['content-type'], undefined);
  for (const request of state.requests) {
    assert.equal(request.headers.authorization, authorization);
    assert.equal(request.headers.cookie, undefined); assert.equal(request.headers['x-role'], undefined);
  }
});

test('media: rechaza métodos, UUID, consultas, actores, origen y metadatos inválidos antes de contactar backend', async t => {
  const { state, base, evidence } = await setup(t);
  assert.equal((await fetch(evidence)).status, 401);
  assert.equal((await fetch(`${evidence}?url=https://private.example`, { headers })).status, 404);
  assert.equal((await fetch(`${base}/media`, { headers })).status, 404);
  assert.equal((await fetch(`${base}/media/not-a-uuid/download`, { headers, method: 'POST', body: '{}' })).status, 405);
  assert.equal((await fetch(`${base}/media/${id}/download`, { headers })).status, 405);
  assert.equal((await fetch(evidence, { headers, method: 'POST', body: '{}' })).status, 405);
  assert.equal((await fetch(`${base}/media/uploads`, { headers: { ...headers, Origin: 'https://foreign.example' }, method: 'POST', body: JSON.stringify(upload) })).status, 403);
  assert.equal((await fetch(`${base}/media/${id}`, { headers: { ...headers, Origin: 'https://foreign.example' }, method: 'DELETE' })).status, 403);
  assert.equal((await fetch(`${base}/media/uploads`, { headers: { Authorization: authorization, 'Content-Type': 'text/plain' }, method: 'POST', body: JSON.stringify(upload) })).status, 415);
  for (const changes of [{ actor: 'other' }, { id: 'bad' }, { runId: 'bad' }, { itemId: 'bad' }, { originalName: '../private.jpg' },
    { originalName: ' foto.jpg ' }, { originalName: 'foto\\private.jpg' }, { originalName: 'a'.repeat(201) }, { originalName: '\u0001.jpg' },
    { mimeType: 'image/webp' }, { sizeBytes: limit + 1 }, { sizeBytes: 0 }, { sizeBytes: 1.5 }, { sha256: 'bad' }]) {
    assert.equal((await fetch(`${base}/media/uploads`, { headers, method: 'POST', body: JSON.stringify({ ...upload, ...changes }) })).status, 400);
  }
  assert.equal((await fetch(`${base}/media/${id}/confirm`, { headers, method: 'POST', body: '{"actor":"other"}' })).status, 400);
  assert.equal((await fetch(`${base}/media/${id}`, { headers, method: 'DELETE', body: '{}' })).status, 400);
  assert.equal(state.requests.length, 0);
});

test('media: contexto, metadatos, políticas y respuestas de estado deben coincidir', async t => {
  const { state, base, evidence } = await setup(t);
  for (const data of [listing([file('AVAILABLE', { itemId: foreign })]), listing([file(), file()]), listing([], { maxFiles: 4 }),
    listing([], { maxBytes: limit + 1 }), listing([], { allowedMimeTypes: ['image/gif'] }), listing([file('UNKNOWN')])]) {
    state.data = data;
    const response = await fetch(evidence, { headers });
    assert.equal(response.status, 503); assert.equal((await response.text()).includes('private'), false);
  }
  state.data = { file: file('PENDING_UPLOAD', { sizeBytes: 5 }), upload: uploadTicket() };
  assert.equal((await fetch(`${base}/media/uploads`, { headers, method: 'POST', body: JSON.stringify(upload) })).status, 503);
  state.data = file('PENDING_UPLOAD');
  assert.equal((await fetch(`${base}/media/${id}/confirm`, { headers, method: 'POST', body: '{}' })).status, 503);
  state.data = file();
  assert.equal((await fetch(`${base}/media/${id}`, { headers, method: 'DELETE' })).status, 503);
});

test('media: nunca expone tickets con destino, headers o vencimiento inseguros', async t => {
  const { state, base } = await setup(t);
  for (const unsafe of [ticket({ url: 'http://account.r2.cloudflarestorage.com/object' }), ticket({ url: 'https://attacker.example/object' }),
    ticket({ url: 'https://account.r2.cloudflarestorage.com.attacker.example/object' }), ticket({ url: 'https://user:pass@account.r2.cloudflarestorage.com/object' }),
    ticket({ url: `${signedUrl}#fragment` }), ticket({ url: 'https://bucket.s3.amazonaws.com/object' }), ticket({ headers: { Authorization: ['private'] } }),
    ticket({ headers: { Cookie: ['private'] } }), ticket({ expiresAt: '2020-01-01T00:00:00Z' })]) {
    state.data = unsafe;
    const response = await fetch(`${base}/media/${id}/download`, { headers, method: 'POST', body: '{}' });
    assert.equal(response.status, 503); assert.equal((await response.text()).includes('private-signature'), false);
  }
  state.data = { file: file('PENDING_UPLOAD'), upload: ticket({ headers: { 'Content-Type': ['image/jpeg'], 'If-None-Match': ['overwrite'] } }) };
  assert.equal((await fetch(`${base}/media/uploads`, { headers, method: 'POST', body: JSON.stringify(upload) })).status, 503);
});

test('media: conserva códigos de reintento sanitizados y no sigue redirecciones del backend', async t => {
  const { state, base } = await setup(t);
  for (const [status, code] of [[409, 'UPLOAD_NOT_READY'], [409, 'RESOURCE_CONFLICT'], [422, 'MEDIA_CONTENT_INVALID'], [503, 'MEDIA_UNAVAILABLE'], [401, 'UNAUTHENTICATED'], [401, 'SESSION_EXPIRED'], [429, 'RATE_LIMITED']]) {
    state.status = status; state.errorCode = code;
    const response = await fetch(`${base}/media/${id}/confirm`, { headers, method: 'POST', body: '{}' });
    assert.equal(response.status, status);
    const body = await response.json();
    assert.equal(body.errors[0].code, code);
    assert.deepEqual(body.errors[0].fields, {});
    assert.equal(JSON.stringify(body).includes('SQL'), false);
  }
  state.status = 302; state.extraHeaders.Location = 'https://attacker.example/collect';
  const response = await fetch(`${base}/media/${id}/confirm`, { headers, method: 'POST', body: '{}' });
  assert.equal(response.status, 503); assert.equal((await response.text()).includes('attacker'), false);
});

test('media: evidencia de incidencia y acción conserva destino explícito y oculta almacenamiento', async t => {
  const { state, origin } = await setup(t);
  const base = `${origin}/backend/api/v1/organizations/tenant_media`;
  const before = { ...file(), runId: '', itemId: '', incidentId: run, stage: 'BEFORE' };
  state.data = listing([{ ...before, objectKey: 'private' }]);
  let response = await fetch(`${base}/incidents/${run}/evidence/BEFORE`, { headers });
  assert.equal(response.status, 200); assert.deepEqual(await response.json(), { data: listing([before]) });
  const after = { ...before, stage: 'AFTER', actionId: item };
  state.data = listing([after]);
  response = await fetch(`${base}/incidents/${run}/actions/${item}/evidence/AFTER`, { headers });
  assert.equal(response.status, 200); assert.deepEqual(await response.json(), { data: listing([after]) });
  for (const overrides of [{ incidentId: foreign }, { actionId: foreign }, { stage: 'BEFORE' }, { runId: run }, { actionId: undefined }]) {
    state.data = listing([{ ...after, ...overrides }]);
    assert.equal((await fetch(`${base}/incidents/${run}/actions/${item}/evidence/AFTER`, { headers })).status, 503);
  }
});

test('media: reserva incidente exclusiva, BEFORE sin acción y AFTER con acción', async t => {
  const { state, base } = await setup(t);
  const { runId, itemId, ...metadata } = upload;
  const before = { ...metadata, incidentId: run, stage: 'BEFORE' };
  const after = { ...metadata, incidentId: run, actionId: item, stage: 'AFTER' };
  for (const input of [before, after]) {
    const incidentFile = { ...file('PENDING_UPLOAD'), runId: '', itemId: '', incidentId: input.incidentId, stage: input.stage, ...(input.actionId ? { actionId: input.actionId } : {}) };
    state.data = { file: incidentFile, upload: uploadTicket() };
    const response = await fetch(`${base}/media/uploads`, { headers, method: 'POST', body: JSON.stringify(input) });
    assert.equal(response.status, 200); assert.deepEqual((await response.json()).data.file, incidentFile);
    assert.deepEqual(JSON.parse(state.requests.at(-1).body), input);
  }
  state.requests.length = 0;
  for (const input of [{ ...before, actionId: item }, { ...after, actionId: undefined }, { ...before, runId: run, itemId: item }, { ...before, stage: 'UNKNOWN' }, { ...before, incidentId: 'invalid' }, { ...before, unexpected: true }, { ...upload, stage: 'BEFORE' }]) {
    assert.equal((await fetch(`${base}/media/uploads`, { headers, method: 'POST', body: JSON.stringify(input) })).status, 400);
  }
  assert.equal(state.requests.length, 0);
  state.data = { file: { ...file('PENDING_UPLOAD'), runId: '', itemId: '', incidentId: foreign, stage: 'BEFORE' }, upload: uploadTicket() };
  assert.equal((await fetch(`${base}/media/uploads`, { headers, method: 'POST', body: JSON.stringify(before) })).status, 503);
});

test('media: rechaza UTF8 inválido dentro de metadatos permitidos', async t => {
  const { state, base } = await setup(t);
  const raw = Buffer.from(JSON.stringify({ ...upload, originalName: 'SENTINEL.jpg' }));
  const start = raw.indexOf('SENTINEL');
  const invalid = Buffer.concat([raw.subarray(0, start), Buffer.from([255]), raw.subarray(start + 8)]);
  state.data = { file: file('PENDING_UPLOAD', { originalName: '�.jpg' }), upload: uploadTicket() };
  assert.equal((await fetch(base + '/media/uploads', { headers, method: 'POST', body: invalid })).status, 400);
  assert.equal(state.requests.length, 0);
});

test('media: conserva cuatro fotos legadas de incidencia y mantiene el límite de controles', async t => {
  const { state, base, evidence } = await setup(t);
  const photos = Array.from({ length: 4 }, (_, index) => file('AVAILABLE', {
    id: `40000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
    runId: '', itemId: '', incidentId: run, stage: 'BEFORE',
  }));
  state.data = listing(photos.map(photo => ({ ...photo, objectKey: 'private.storage', bucket: 'private.bucket' })));
  const response = await fetch(`${base}/incidents/${run}/evidence/BEFORE`, { headers });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { data: listing(photos) });
  for (const changes of [{ incidentId: foreign }, { stage: 'AFTER', actionId: item }, { id: photos[0].id }]) {
    state.data = listing(photos.map((photo, index) => index === 3 ? { ...photo, ...changes } : photo));
    assert.equal((await fetch(`${base}/incidents/${run}/evidence/BEFORE`, { headers })).status, 503);
  }
  state.data = listing(photos.map(photo => ({ ...photo, runId: run, itemId: item, incidentId: undefined, stage: undefined })));
  assert.equal((await fetch(evidence, { headers })).status, 503);
});
