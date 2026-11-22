import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { normativeRoute, proxyNormative } from './normative-proxy.mjs';
import { webhooksRoute, proxyWebhooks } from './webhooks-proxy.mjs';

const id = 'a0000000-0000-4000-8000-000000000001';
const date = '2026-11-21T12:00:00Z';
const headers = { authorization: 'Bearer fixture.token', host: 'bpm.test', 'content-type': 'application/json' };
async function invoke(proxy, path, { method = 'GET', body, raw, requestHeaders = {}, payload = {}, status = 200 } = {}) {
  const saved = globalThis.fetch, calls = [];
  globalThis.fetch = async (url, options) => { calls.push({ url: String(url), ...options }); return new Response(status === 204 ? null : JSON.stringify(payload), { status, headers: { 'Content-Type': 'application/json' } }); };
  const request = Readable.from(raw === undefined ? body === undefined ? [] : [Buffer.from(JSON.stringify(body))] : [Buffer.from(raw)]);
  request.method = method; request.headers = { ...headers, ...requestHeaders };
  if (body !== undefined || raw !== undefined) request.headers['content-length'] = String(Buffer.byteLength(raw ?? JSON.stringify(body)));
  let answer;
  try { await proxy(request, new URL('http://bpm.test' + path), new URL('https://api.test/'), (code, data) => { answer = { status: code, body: data ? JSON.parse(data) : null }; }, { setHeader: () => {} }); }
  finally { globalThis.fetch = saved; }
  return { ...answer, calls };
}

test('normativa: rutas cerradas', () => {
  for (const p of ['status', 'search', 'documents', `documents/${id}`, `documents/${id}/archive`, `documents/${id}/restore`]) assert.equal(normativeRoute('/backend/api/v1/normative/' + p), true, p);
  for (const p of ['questions', 'search/1', `status/${id}`, 'documents/x', `documents/${id}/delete`, 'export']) assert.equal(normativeRoute('/backend/api/v1/normative/' + p), false, p);
});

test('normativa: la búsqueda valida q, la reenvía codificada y proyecta los párrafos', async () => {
  const ok = await invoke(proxyNormative, '/backend/api/v1/normative/search?q=' + encodeURIComponent('temperatura refrigeración'),
    { payload: { data: [{ documentId: id, documentTitle: 'NTS 142', documentSource: 'RM 822', text: 'de 0 °C a 4 °C', secret: 'x' }] } });
  assert.equal(ok.status, 200); assert.equal(ok.body.data[0].text, 'de 0 °C a 4 °C'); assert.equal(JSON.stringify(ok.body).includes('secret'), false);
  assert.ok(ok.calls[0].url.endsWith('/api/v1/normative/search?q=' + encodeURIComponent('temperatura refrigeración')));
  for (const path of ['search', 'search?q=a', 'search?q=' + 'x'.repeat(201), 'search?q=pollo&q=res', 'search?q=pollo&extra=1'])
    { const r = await invoke(proxyNormative, '/backend/api/v1/normative/' + path); assert.equal(r.status, 400, path); assert.equal(r.calls.length, 0); }
  assert.equal((await invoke(proxyNormative, '/backend/api/v1/normative/search?q=pollo', { method: 'POST', body: {} })).status, 405);
  assert.equal((await invoke(proxyNormative, '/backend/api/v1/normative/search?q=pollo', { payload: { data: [{ documentId: 'x', documentTitle: 't', text: 'y' }] } })).status, 503);
});

test('normativa: estado y errores generalizados', async () => {
  const status = await invoke(proxyNormative, '/backend/api/v1/normative/status', { payload: { data: { documents: 3, enabled: true } } });
  assert.deepEqual(status.body.data, { documents: 3 });
  const other = await invoke(proxyNormative, '/backend/api/v1/normative/documents', { status: 403, payload: { errors: [{ code: 'INTERNAL_DETAIL', message: 'x' }] } });
  assert.equal(other.body.errors[0].code, 'ACCESS_DENIED');
});

test('normativa: la lista no expone el texto y el detalle sí', async () => {
  const doc = { id, title: 'NTS 142', source: '', status: 'ACTIVE', characters: 10, createdAt: date, updatedAt: date };
  const list = await invoke(proxyNormative, '/backend/api/v1/normative/documents?status=ARCHIVED', { payload: { data: [doc] } });
  assert.equal(list.status, 200); assert.ok(list.calls[0].url.endsWith('?status=ARCHIVED'));
  assert.equal((await invoke(proxyNormative, '/backend/api/v1/normative/documents', { payload: { data: [{ ...doc, content: 'texto' }] } })).status, 503);
  const detail = await invoke(proxyNormative, `/backend/api/v1/normative/documents/${id}`, { payload: { data: { ...doc, content: 'Artículo 1' } } });
  assert.equal(detail.body.data.content, 'Artículo 1');
  assert.equal((await invoke(proxyNormative, '/backend/api/v1/normative/documents?status=OTRO')).status, 400);
});

test('webhooks: rutas, entrada https y secreto solo al crear o rotar', async () => {
  assert.equal(webhooksRoute(`/backend/api/v1/organizations/org_1/webhooks/${id}/test`), true);
  assert.equal(webhooksRoute(`/backend/api/v1/organizations/org_1/webhooks/${id}/secret`), false);
  const hook = { id, url: 'https://n8n.pe/webhook/bpm', eventTypes: ['TEMPERATURE_DANGER'], active: true, lastStatus: null, lastError: '', lastDeliveryAt: null };
  const created = await invoke(proxyWebhooks, '/backend/api/v1/organizations/org_1/webhooks', { method: 'POST', status: 201,
    body: { url: hook.url, eventTypes: hook.eventTypes, active: true }, payload: { data: { ...hook, secret: 'whsec_' + 'a'.repeat(43) } } });
  assert.equal(created.status, 201); assert.ok(created.body.data.secret.startsWith('whsec_'));
  const list = await invoke(proxyWebhooks, '/backend/api/v1/organizations/org_1/webhooks', { payload: { data: { items: [{ ...hook, secret: 'whsec_' + 'a'.repeat(43) }], eventTypes: ['TEMPERATURE_DANGER'], maxEndpoints: 5 } } });
  assert.equal(list.status, 503, 'la lista nunca debe traer el secreto');
  for (const body of [{ url: 'http://n8n.pe/x', eventTypes: ['TEMPERATURE_DANGER'] }, { url: hook.url, eventTypes: ['OTRO'] }, { url: hook.url, eventTypes: [] }])
    assert.equal((await invoke(proxyWebhooks, '/backend/api/v1/organizations/org_1/webhooks', { method: 'POST', body })).status, 400);
  const removed = await invoke(proxyWebhooks, `/backend/api/v1/organizations/org_1/webhooks/${id}`, { method: 'DELETE', status: 204 });
  assert.equal(removed.status, 204);
  const foreign = await invoke(proxyWebhooks, `/backend/api/v1/organizations/org_1/webhooks/${id}/test`, { method: 'POST', body: {}, requestHeaders: { origin: 'https://otro.test' } });
  assert.equal(foreign.status, 403); assert.equal(foreign.calls.length, 0);
});
