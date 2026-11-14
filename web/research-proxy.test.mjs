import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';

const org = 'tenant_research';
const versionId = '20000000-0000-4000-8000-000000000001';
const questionId = '40000000-0000-4000-8000-000000000001';
const submissionId = '50000000-0000-4000-8000-000000000001';
const operationId = '60000000-0000-4000-8000-000000000001';
const userId = '70000000-0000-4000-8000-000000000001';
const orgId = '80000000-0000-4000-8000-000000000001';
const instrumentId = '90000000-0000-4000-8000-000000000001';
const stamp = '2026-01-01T12:00:00Z';
const headers = { Authorization: 'Bearer test.token', 'Content-Type': 'application/json', Cookie: 'private-cookie' };
const question = { id: questionId, order: 1, dimension: 'KNOWLEDGE', type: 'MULTIPLE_CHOICE', text: '¿Cuándo?', options: ['a', 'b'] };
const part = {
  participant: { code: 'P-7K2M9QXA', status: 'ACTIVE' },
  consentDocument: { version: 'v1', title: 'T', text: 'Texto', responsibleInvestigator: 'Dra. X', publishedAt: stamp },
  consent: { documentVersion: 'v1', accepted: true, recordedAt: stamp, responsibleInvestigator: 'Dra. X' }, consentValid: true,
  survey: { instrumentCode: 'CAP-BPM', name: 'CAP', instrumentVersionId: versionId, versionNumber: 1, questions: [question] },
  submissions: [{ moment: 'PRETEST', instrumentVersionId: versionId, submittedAt: stamp }],
};
const submission = { id: submissionId, clientOperationId: operationId, instrumentVersionId: versionId, moment: 'PRETEST', clientCreatedAt: stamp, answers: [{ questionId, value: 1 }] };
const draftData = {
  instrument: { id: instrumentId, code: 'CAP-BPM', name: 'CAP', description: '', isCap: true, status: 'DRAFT', createdAt: stamp },
  version: { id: versionId, versionNumber: 1, status: 'DRAFT', rowVersion: 3, publishedAt: null },
  questions: [{ id: questionId, order: 1, dimension: 'KNOWLEDGE', type: 'MULTIPLE_CHOICE', text: 'P', options: ['a', 'b'], correctOption: 1 }],
};

async function fixture(t, raw) {
  const state = { status: 200, payload: { data: part }, requests: [], raw };
  const backend = http.createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    state.requests.push({ path: req.url, method: req.method, headers: req.headers, body: Buffer.concat(chunks).toString('utf8') });
    if (state.raw) { res.writeHead(state.status, state.raw.headers); res.end(state.raw.body); return; }
    res.writeHead(state.status, { 'Content-Type': 'application/json', 'Set-Cookie': 'private=secret' });
    res.end(state.status === 204 ? undefined : JSON.stringify(state.payload));
  });
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
  const app = createAppServer({ apiBaseUrl: `http://127.0.0.1:${backend.address().port}`, allowInsecureForTests: true });
  app.listen(0, '127.0.0.1'); await once(app, 'listening');
  t.after(async () => { app.closeAllConnections(); backend.closeAllConnections(); await Promise.all([new Promise(r => app.close(r)), new Promise(r => backend.close(r))]); });
  const root = `http://127.0.0.1:${app.address().port}/backend/api/v1`;
  const call = (method, path, body, extra = {}) => fetch(root + path, { method, headers: { ...headers, ...extra }, body: body === undefined ? undefined : JSON.stringify(body) });
  return { state, call, get: path => call('GET', path), post: (path, body = {}, extra) => call('POST', path, body, extra), put: (path, body) => call('PUT', path, body) };
}

const p = `/organizations/${org}/research`;

test('investigación: la participación nunca expone respuestas correctas ni puntajes', async t => {
  const { state, get } = await fixture(t);
  state.payload = { data: { ...part, scores: { knowledgePercent: 90 }, survey: { ...part.survey, questions: [{ ...question, correctOption: 1 }] } } };
  const response = await get(`${p}/participation`);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { data: part });
  assert.equal(response.headers.get('set-cookie'), null);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(state.requests[0].path, `/api/v1/organizations/${org}/research/participation`);
  assert.equal(state.requests[0].headers.cookie, undefined);
});

test('investigación: rechaza participación inconsistente', async t => {
  const { state, get } = await fixture(t);
  for (const data of [
    { ...part, consentValid: false },
    { ...part, survey: { ...part.survey, questions: [{ ...question, dimension: 'PRACTICE' }] } },
    { ...part, survey: { ...part.survey, questions: [{ ...question, type: 'LIKERT', dimension: 'ATTITUDE', options: ['a'] }] } },
    { ...part, participant: { code: 'x', status: 'ACTIVE' } },
  ]) { state.payload = { data }; assert.equal((await get(`${p}/participation`)).status, 503); }
});

test('investigación: la entrega valida el cuerpo y no devuelve puntajes', async t => {
  const { state, post } = await fixture(t);
  state.status = 201;
  state.payload = { data: { id: submissionId, moment: 'PRETEST', instrumentVersionId: versionId, submittedAt: stamp, scores: { knowledgeCorrect: 7 } } };
  const response = await post(`${p}/submissions`, submission);
  assert.equal(response.status, 201);
  assert.deepEqual(await response.json(), { data: { id: submissionId, moment: 'PRETEST', instrumentVersionId: versionId, submittedAt: stamp } });
  assert.deepEqual(JSON.parse(state.requests[0].body), submission);
  for (const bad of [{ ...submission, extra: 1 }, { ...submission, moment: 'OTHER' }, { ...submission, answers: [] }, { ...submission, answers: [submission.answers[0], submission.answers[0]] },
    { ...submission, clientCreatedAt: '2999-01-01T00:00:00Z' }, { ...submission, answers: [{ questionId, value: 9 }] }]) {
    assert.equal((await post(`${p}/submissions`, bad)).status, 400);
  }
  assert.equal(state.requests.length, 1);
});

test('investigación: el consentimiento y la entrega exigen origen propio y JSON', async t => {
  const { state, post } = await fixture(t);
  assert.equal((await post(`${p}/consent`, { documentVersion: 'v1', accepted: true }, { Origin: 'https://evil.example' })).status, 403);
  assert.equal((await post(`${p}/consent`, { documentVersion: 'v1', accepted: true }, { 'Content-Type': 'text/plain' })).status, 415);
  assert.equal((await post(`${p}/consent`, { documentVersion: 'v 1!', accepted: true })).status, 400);
  assert.equal((await post(`${p}/consent`, { documentVersion: 'v1' })).status, 400);
  assert.equal((await post(`${p}/consent`, { documentVersion: 'v1', accepted: false })).status, 200);
  assert.equal(state.requests.length, 1);
});

test('investigación: sin sesión, métodos y rutas desconocidas', async t => {
  const { state, call, get } = await fixture(t);
  const anonymous = await fetch(`${(await call('GET', '/research/access')).url}`, { headers: {} });
  assert.equal(anonymous.status, 401);
  assert.equal((await call('DELETE', `${p}/participation`)).status, 405);
  assert.equal((await call('GET', '/research/unknown')).status, 404);
  assert.equal((await get(`/research/needs?evil=1`)).status, 400);
  assert.equal((await get(`/research/needs?pageSize=500`)).status, 400);
  assert.equal(state.requests.length, 1);
});

test('investigación: los errores de dominio permitidos se conservan y el resto se sanitiza', async t => {
  const { state, post, get } = await fixture(t);
  state.status = 409; state.payload = { errors: [{ code: 'RESEARCH_SUBMISSION_EXISTS', message: 'SQL secreto' }] };
  let response = await post(`${p}/submissions`, submission);
  assert.equal(response.status, 409);
  const text = await response.text();
  assert.match(text, /RESEARCH_SUBMISSION_EXISTS/); assert.doesNotMatch(text, /secreto/);
  state.status = 403; state.payload = { errors: [{ code: 'RESEARCH_ACCESS_DENIED', message: 'x' }] };
  assert.match(await (await get('/research/instruments')).text(), /RESEARCH_ACCESS_DENIED/);
  state.status = 422; state.payload = { errors: [{ code: 'OTRO', message: 'interno' }] };
  assert.match(await (await post(`${p}/submissions`, submission)).text(), /VALIDATION_ERROR/);
  state.status = 418; assert.equal((await get('/research/access')).status, 503);
});

test('investigación: acceso, concesiones y borradores', async t => {
  const { state, get, post, put, call } = await fixture(t);
  state.payload = { data: { canResearch: true, canManageGrants: false, extra: 1 } };
  assert.deepEqual(await (await get('/research/access')).json(), { data: { canResearch: true, canManageGrants: false } });
  const g = { userId, email: 'a@b.pe', displayName: 'A', grantedAt: stamp, revokedAt: null, active: true };
  state.payload = { data: g };
  assert.equal((await post('/research/access-grants', { email: 'a@b.pe' })).status, 200);
  assert.equal((await post('/research/access-grants', { email: 'mal' })).status, 400);
  state.status = 204;
  assert.equal((await call('DELETE', `/research/access-grants/${userId}`)).status, 204);
  state.status = 200;
  state.payload = { data: draftData };
  const read = await get(`/research/instruments/${instrumentId}/draft`);
  assert.equal((await read.json()).data.version.rowVersion, 3);
  const body = { rowVersion: 3, questions: [{ type: 'MULTIPLE_CHOICE', text: 'P', options: ['a', 'b'], correctOption: 1 }, { type: 'LIKERT', text: 'L' }] };
  assert.equal((await put(`/research/instruments/${instrumentId}/draft`, body)).status, 200);
  assert.equal((await put(`/research/instruments/${instrumentId}/draft`, { ...body, extra: 1 })).status, 400);
  state.payload = { data: { version: { id: versionId, versionNumber: 2, status: 'PUBLISHED', rowVersion: 4, publishedAt: stamp } } };
  assert.equal((await post(`/research/instruments/${instrumentId}/publish`)).status, 200);
  assert.equal((await post(`/research/instruments/${instrumentId}/publish`, { x: 1 })).status, 400);
});

test('investigación: la importación ignora source y generalData', async t => {
  const { state, post } = await fixture(t);
  state.status = 201; state.payload = { data: draftData };
  const response = await post('/research/instruments/import', { code: 'CAP-BPM', name: 'CAP', isCap: true, source: 'doc', generalData: { a: 1 }, questions: [{ type: 'FREQUENCY', text: 'F', options: [], correctOption: null }] });
  assert.equal(response.status, 201);
  assert.deepEqual(Object.keys(JSON.parse(state.requests[0].body)).sort(), ['code', 'isCap', 'name', 'questions']);
});

test('investigación: resultados y necesidades paginados', async t => {
  const { state, get, post } = await fixture(t);
  const stats = { n: 2, knowledgeMean: 50.5, attitudeMean: null, practiceMean: 3 };
  state.payload = { data: [{ organizationId: orgId, organizationName: 'E', participants: 3, pretest: stats, posttest: stats, paired: { n: 1, knowledgeDelta: 1, attitudeDelta: null, practiceDelta: 0.5 } }], version: { id: versionId, versionNumber: 1 }, pagination: { nextPageToken: 'abc_1' } };
  const response = await get(`/research/results?pageSize=20&pageToken=abc_1&instrumentVersionId=${versionId}`);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).pagination.nextPageToken, 'abc_1');
  assert.match(state.requests[0].path, /instrumentVersionId=/);
  assert.equal((await get('/research/results?pageToken=a%20b')).status, 400);
  const need = { id: submissionId, organizationId: orgId, organizationName: 'E', category: 'TRAINING', description: 'D', detectedAt: stamp, investigatorName: 'I', createdAt: stamp };
  state.status = 201; state.payload = { data: need };
  assert.equal((await post('/research/needs', { organizationId: orgId, category: 'TRAINING', description: 'D' })).status, 201);
  assert.equal((await post('/research/needs', { organizationId: orgId, category: 'NOPE', description: 'D' })).status, 400);
  state.status = 200; state.payload = { data: [need], pagination: { nextPageToken: '' } };
  assert.equal((await get(`/research/needs?organizationId=${orgId}`)).status, 200);
  assert.equal((await get('/research/needs?organizationId=zzz')).status, 400);
});

test('investigación: el CSV pasa en streaming solo si es text/csv con nombre seguro', async t => {
  const csv = 'participantCode,organizationId\nP-AAAAAAAA,x\n';
  const ok = { headers: { 'Content-Type': 'text/csv; charset=utf-8', 'Content-Disposition': 'attachment; filename="cap-v1-respuestas.csv"', 'Set-Cookie': 'x=y' }, body: csv };
  let f = await fixture(t, ok);
  let response = await f.get(`/research/exports/responses.csv?instrumentVersionId=${versionId}&organizationId=${orgId}`);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('content-type'), 'text/csv; charset=utf-8');
  assert.equal(response.headers.get('content-disposition'), 'attachment; filename="cap-v1-respuestas.csv"');
  assert.equal(response.headers.get('set-cookie'), null);
  assert.equal(await response.text(), csv);
  for (const bad of [{ ...ok, headers: { ...ok.headers, 'Content-Type': 'text/html' } }, { ...ok, headers: { ...ok.headers, 'Content-Disposition': 'attachment; filename="../x.csv"' } },
    { ...ok, headers: { ...ok.headers, 'Content-Length': String(17 * 1024 * 1024) }, body: undefined }]) {
    f = await fixture(t, bad);
    assert.equal((await f.get('/research/exports/responses.csv')).status, 503);
  }
  f = await fixture(t, ok);
  assert.equal((await f.get('/research/exports/responses.csv?evil=1')).status, 400);
});
