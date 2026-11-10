import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';

const org = 'tenant_training';
const courseId = '10000000-0000-4000-8000-000000000001';
const versionId = '20000000-0000-4000-8000-000000000001';
const lessonId = '30000000-0000-4000-8000-000000000001';
const questionId = '40000000-0000-4000-8000-000000000001';
const secondQuestionId = '40000000-0000-4000-8000-000000000002';
const attemptId = '50000000-0000-4000-8000-000000000001';
const operationId = '60000000-0000-4000-8000-000000000001';
const mediaId = '70000000-0000-4000-8000-000000000001';
const stamp = '2026-01-01T12:00:00Z';
const r2 = 'https://bucket.abc123.r2.cloudflarestorage.com/organizations/o/training/videos/x.mp4?X-Amz-Signature=secret';
const headers = { Authorization: 'Bearer test.token', 'Content-Type': 'application/json', Cookie: 'private-cookie', 'X-Role': 'ADMIN' };
const progress = { status: 'IN_PROGRESS', lastLessonId: lessonId, bestScore: null, experiencePoints: 0, completedAt: null };
const course = { id: courseId, code: 'M-01', title: 'Higiene de manos', description: 'Lavado correcto', estimatedDurationMinutes: 15 };
const lesson = { id: lessonId, title: 'Paso a paso', content: 'Texto\nde la lección', displayOrder: 1, video: { mediaId, mimeType: 'video/mp4', sizeBytes: 1234 } };
const question = { id: questionId, lessonId, questionText: '¿Cuánto dura el lavado?', options: ['5 s', '20 s'], displayOrder: 1 };
const learning = { course, version: { id: versionId, versionNumber: 2, passingScore: 80 }, lessons: [lesson], questions: [question], progress, certificate: null };
const draft = {
  version: { id: versionId, versionNumber: 3, passingScore: 80, rowVersion: 'v7' },
  lessons: [{ id: lessonId, title: 'Paso a paso', content: 'Texto', displayOrder: 1, videoMediaId: null }],
  questions: [{ id: questionId, lessonId, questionText: '¿Cuánto dura?', options: ['5 s', '20 s'], correctOption: 1, explanation: 'Al menos 20 s', displayOrder: 1 }],
};
const attempt = {
  id: attemptId, clientOperationId: operationId, versionId, attemptType: 'FINAL', startedAt: stamp, clientCreatedAt: stamp,
  answers: [{ questionId, selectedOption: 1 }, { questionId: secondQuestionId, selectedOption: 0 }],
};

async function fixture(t) {
  const state = { status: 200, payload: { data: learning }, requests: [], redirect: false };
  const backend = http.createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    state.requests.push({ path: req.url, method: req.method, headers: req.headers, body: Buffer.concat(chunks).toString('utf8') });
    res.writeHead(state.redirect ? 302 : state.status, { 'Content-Type': 'application/json', 'Set-Cookie': 'private=secret', 'X-BPM-Session-Expires-At': '1900000000', ...(state.redirect ? { Location: '/private' } : {}) });
    res.end(typeof state.payload === 'string' ? state.payload : JSON.stringify(state.payload));
  });
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
  const app = createAppServer({ apiBaseUrl: `http://127.0.0.1:${backend.address().port}`, allowInsecureForTests: true });
  app.listen(0, '127.0.0.1'); await once(app, 'listening');
  t.after(async () => { app.closeAllConnections(); backend.closeAllConnections(); await Promise.all([new Promise(r => app.close(r)), new Promise(r => backend.close(r))]); });
  const base = `http://127.0.0.1:${app.address().port}/backend/api/v1/organizations/${org}/training`;
  const call = (method, path, body, extra = {}) => fetch(base + path, { method, headers: { ...headers, ...extra }, body: body === undefined ? undefined : JSON.stringify(body) });
  return { state, base, get: path => call('GET', path), post: (path, body = {}, extra) => call('POST', path, body, extra), put: (path, body, extra) => call('PUT', path, body, extra) };
}

test('capacitación: el detalle de aprendizaje nunca expone respuesta correcta ni explicación', async t => {
  const { state, get } = await fixture(t);
  state.payload = { data: { ...learning, organizationId: 'otra', questions: [{ ...question, correctOption: 1, explanation: 'secreto' }] } };
  const response = await get(`/courses/${courseId}`);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { data: learning });
  assert.equal(response.headers.get('x-bpm-session-expires-at'), '1900000000');
  assert.equal(response.headers.get('set-cookie'), null);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(state.requests[0].path, `/api/v1/organizations/${org}/training/courses/${courseId}`);
  assert.equal(state.requests[0].headers.cookie, undefined);
  assert.equal(state.requests[0].headers['x-role'], undefined);
});

test('capacitación: rechaza respuestas de detalle con curso distinto, opciones inválidas o pregunta huérfana', async t => {
  const { state, get } = await fixture(t);
  for (const data of [
    { ...learning, course: { ...course, id: versionId } },
    { ...learning, questions: [{ ...question, options: ['solo una'] }] },
    { ...learning, questions: [{ ...question, lessonId: versionId }] },
    { ...learning, lessons: [lesson, lesson] },
    { ...learning, progress: { ...progress, status: 'OTRO' } },
    { ...learning, lessons: [{ ...lesson, video: { ...lesson.video, mimeType: 'video/avi' } }] },
  ]) { state.payload = { data }; assert.equal((await get(`/courses/${courseId}`)).status, 503); }
});

test('capacitación: el video devuelve solo una URL firmada HTTPS de R2 vigente y sin cuerpo de entrada', async t => {
  const { state, post } = await fixture(t);
  state.payload = { data: { url: r2, mimeType: 'video/mp4', expiresAt: '2099-01-01T00:00:00Z', headers: { host: ['x'] } } };
  const response = await post(`/courses/${courseId}/lessons/${lessonId}/video`);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { data: { url: r2, mimeType: 'video/mp4', expiresAt: '2099-01-01T00:00:00Z' } });
  assert.equal(state.requests[0].body, '{}');
  for (const data of [
    { url: 'http://bucket.r2.cloudflarestorage.com/x', mimeType: 'video/mp4', expiresAt: '2099-01-01T00:00:00Z' },
    { url: 'https://evil.example/x.mp4', mimeType: 'video/mp4', expiresAt: '2099-01-01T00:00:00Z' },
    { url: 'https://user@bucket.r2.cloudflarestorage.com/x', mimeType: 'video/mp4', expiresAt: '2099-01-01T00:00:00Z' },
    { url: r2, mimeType: 'video/mp4', expiresAt: '2001-01-01T00:00:00Z' },
    { url: r2, mimeType: 'text/html', expiresAt: '2099-01-01T00:00:00Z' },
  ]) { state.payload = { data }; assert.equal((await post(`/courses/${courseId}/lessons/${lessonId}/video`)).status, 503); }
  assert.equal((await post(`/courses/${courseId}/lessons/${lessonId}/video`, { extra: 1 })).status, 400);
});

test('capacitación: progreso y modo estudio validan el cuerpo y proyectan la respuesta', async t => {
  const { state, put, post } = await fixture(t);
  state.payload = { data: { progress, extra: 'privado' } };
  assert.deepEqual(await (await put(`/courses/${courseId}/progress`, { lastLessonId: lessonId })).json(), { data: { progress } });
  assert.deepEqual(JSON.parse(state.requests.at(-1).body), { lastLessonId: lessonId });
  state.payload = { data: { correct: true, correctOption: 1, explanation: 'Por higiene', secret: 'x' } };
  const answer = await post(`/courses/${courseId}/study/answers`, { questionId, selectedOption: 1 });
  assert.deepEqual(await answer.json(), { data: { correct: true, correctOption: 1, explanation: 'Por higiene' } });
  state.payload = { data: { correct: true, correctOption: 0, explanation: '' } };
  assert.equal((await post(`/courses/${courseId}/study/answers`, { questionId, selectedOption: 1 })).status, 503);
  const before = state.requests.length;
  for (const body of [{ questionId, selectedOption: 6 }, { questionId, selectedOption: -1 }, { questionId: 'x', selectedOption: 0 }, { questionId, selectedOption: 0, extra: 1 }, {}])
    assert.equal((await post(`/courses/${courseId}/study/answers`, body)).status, 400);
  assert.equal((await put(`/courses/${courseId}/progress`, { lastLessonId: 'x' })).status, 400);
  assert.equal(state.requests.length, before);
});

test('capacitación: el intento se confirma contra las respuestas enviadas y conserva 201', async t => {
  const { state, post } = await fixture(t);
  const results = [
    { questionId, selectedOption: 1, correct: true, correctOption: 1, explanation: 'Bien', organizationId: 'otra' },
    { questionId: secondQuestionId, selectedOption: 0, correct: false, correctOption: 2, explanation: '' },
  ];
  state.status = 201;
  state.payload = { data: { attempt: { id: attemptId, attemptType: 'FINAL', score: 50, passed: false, completedAt: stamp, userId: 'privado' }, results, progress, certificate: null } };
  const response = await post(`/courses/${courseId}/attempts`, attempt);
  assert.equal(response.status, 201);
  const body = await response.json();
  assert.equal(body.data.attempt.userId, undefined);
  assert.equal(body.data.results[0].organizationId, undefined);
  assert.equal(body.data.attempt.score, 50);
  assert.deepEqual(JSON.parse(state.requests.at(-1).body), attempt);
  // Repetición idéntica: 200 y el mismo cuerpo del cliente.
  state.status = 200;
  assert.equal((await post(`/courses/${courseId}/attempts`, attempt)).status, 200);
  // Constancia en un intento aprobado.
  const code = 'ABCDEFGHIJKLMNOP';
  state.payload = { data: { attempt: { id: attemptId, attemptType: 'FINAL', score: 100, passed: true, completedAt: stamp },
    results: results.map(item => ({ ...item, selectedOption: item.selectedOption, correct: true, correctOption: item.selectedOption })),
    progress: { ...progress, status: 'COMPLETED', bestScore: 100, experiencePoints: 20, completedAt: stamp }, certificate: { id: mediaId, verificationCode: code, issuedAt: stamp } } };
  const passed = await (await post(`/courses/${courseId}/attempts`, attempt)).json();
  assert.equal(passed.data.certificate.verificationCode, code);
  // Respuestas inconsistentes con lo enviado o con el puntaje.
  for (const data of [
    { attempt: { id: operationId, attemptType: 'FINAL', score: 50, passed: false, completedAt: stamp }, results, progress, certificate: null },
    { attempt: { id: attemptId, attemptType: 'FINAL', score: 99, passed: true, completedAt: stamp }, results, progress, certificate: null },
    { attempt: { id: attemptId, attemptType: 'FINAL', score: 50, passed: false, completedAt: stamp }, results: [results[0]], progress, certificate: null },
    { attempt: { id: attemptId, attemptType: 'FINAL', score: 50, passed: false, completedAt: stamp }, results: [{ ...results[0], selectedOption: 0 }, results[1]], progress, certificate: null },
  ]) { state.payload = { data }; assert.equal((await post(`/courses/${courseId}/attempts`, attempt)).status, 503); }
});

test('capacitación: rechaza intentos mal formados antes de llegar al backend', async t => {
  const { state, post } = await fixture(t);
  for (const body of [
    { ...attempt, answers: [] }, { ...attempt, attemptType: 'PRACTICE' }, { ...attempt, score: 100 }, { ...attempt, passed: true },
    { ...attempt, answers: [attempt.answers[0], attempt.answers[0]] }, { ...attempt, startedAt: '2026-01-02T00:00:00Z' },
    { ...attempt, clientCreatedAt: '2099-01-01T00:00:00Z', startedAt: '2099-01-01T00:00:00Z' }, { ...attempt, clientOperationId: 'x' },
    { ...attempt, answers: [{ questionId, selectedOption: 9 }] }, { ...attempt, answers: [{ questionId, selectedOption: 1, correct: true }] },
  ]) assert.equal((await post(`/courses/${courseId}/attempts`, body)).status, 400);
  assert.equal(state.requests.length, 0);
});

test('capacitación: autoría de lista, creación, borrador y publicación', async t => {
  const { state, get, post, put } = await fixture(t);
  const item = { id: courseId, code: 'M-01', title: 'Higiene', status: 'DRAFT', publishedVersionNumber: null, hasDraft: true };
  state.payload = { data: { items: [{ ...item, organizationId: 'privado' }] } };
  assert.deepEqual(await (await get('/courses?status=DRAFT')).json(), { data: { items: [item] } });
  assert.equal(state.requests.at(-1).path, `/api/v1/organizations/${org}/training/courses?status=DRAFT`);
  state.payload = { data: { items: [{ ...item, status: 'PUBLISHED' }] } };
  assert.equal((await get('/courses?status=DRAFT')).status, 503);
  const input = { code: 'M-02', title: 'Nuevo curso', description: '', estimatedDurationMinutes: 10 };
  state.status = 201;
  state.payload = { data: { id: courseId, ...input, status: 'DRAFT' } };
  const created = await post('/courses', input);
  assert.equal(created.status, 201);
  assert.deepEqual((await created.json()).data, { id: courseId, ...input, status: 'DRAFT' });
  state.status = 200;
  state.payload = { data: { course, ...draft } };
  assert.deepEqual(await (await get(`/courses/${courseId}/draft`)).json(), { data: { course, ...draft } });
  state.payload = { data: { ...draft, version: { ...draft.version, rowVersion: 'v8' } } };
  const saved = await (await put(`/courses/${courseId}/draft`, draft)).json();
  assert.equal(saved.data.version.rowVersion, 'v8');
  assert.deepEqual(JSON.parse(state.requests.at(-1).body), draft);
  state.payload = { data: { cualquier: 'cosa' } };
  assert.deepEqual(await (await post(`/courses/${courseId}/publish`)).json(), { data: { status: 'PUBLISHED' } });
});

test('capacitación: el borrador respeta los límites del contrato', async t => {
  const { state, put, post } = await fixture(t);
  const bad = [
    { ...draft, lessons: [{ ...draft.lessons[0], title: 'x'.repeat(201) }] },
    { ...draft, lessons: [{ ...draft.lessons[0], content: 'x'.repeat(20001) }] },
    { ...draft, lessons: Array.from({ length: 51 }, (_, i) => ({ ...draft.lessons[0], id: `30000000-0000-4000-8000-${String(i).padStart(12, '0')}` })) },
    { ...draft, questions: [{ ...draft.questions[0], options: ['única'], correctOption: 0 }] },
    { ...draft, questions: [{ ...draft.questions[0], options: ['a', 'b', 'c', 'd', 'e', 'f', 'g'] }] },
    { ...draft, questions: [{ ...draft.questions[0], options: ['a', 'x'.repeat(301)] }] },
    { ...draft, questions: [{ ...draft.questions[0], correctOption: 2 }] },
    { ...draft, questions: [{ ...draft.questions[0], lessonId: versionId }] },
    { ...draft, version: { ...draft.version, rowVersion: '' } },
    { ...draft, extra: true },
  ];
  for (const body of bad) assert.equal((await put(`/courses/${courseId}/draft`, body)).status, 400);
  assert.equal((await post('/courses', { code: 'M 01', title: 'Curso', description: '', estimatedDurationMinutes: 5 })).status, 400);
  assert.equal(state.requests.length, 0);
});

test('capacitación: videos reservan con SHA-256, firma de R2 acotada y confirman', async t => {
  const { state, post } = await fixture(t);
  const input = { id: mediaId, originalName: 'lavado.mp4', mimeType: 'video/mp4', sizeBytes: 2048, sha256: 'a'.repeat(64) };
  const file = { id: mediaId, originalName: 'lavado.mp4', mimeType: 'video/mp4', sizeBytes: 2048, status: 'PENDING_UPLOAD' };
  const upload = { method: 'PUT', url: r2, headers: { 'Content-Type': ['video/mp4'], 'If-None-Match': ['*'], 'X-Amz-Meta-Sha256': ['a'.repeat(64)] }, expiresAt: '2099-01-01T00:00:00Z' };
  state.status = 201;
  state.payload = { data: { file: { ...file, organizationId: 'privado' }, upload } };
  const reserved = await post('/videos/uploads', input);
  assert.equal(reserved.status, 201);
  assert.deepEqual(await reserved.json(), { data: { file, upload } });
  for (const bad of [
    { ...upload, headers: { ...upload.headers, Authorization: ['Bearer x'] } },
    { ...upload, headers: { 'Content-Type': ['video/webm'] } },
    { ...upload, headers: { ...upload.headers, 'X-Amz-Meta-Sha256': ['b'.repeat(64)] } },
    { ...upload, url: 'https://evil.example/x' }, { ...upload, method: 'POST' },
  ]) { state.payload = { data: { file, upload: bad } }; assert.equal((await post('/videos/uploads', input)).status, 503); }
  state.status = 200;
  state.payload = { data: { file: { ...file, status: 'AVAILABLE' } } };
  assert.deepEqual(await (await post(`/videos/${mediaId}/confirm`)).json(), { data: { file: { ...file, status: 'AVAILABLE' } } });
  state.payload = { data: { ...file, status: 'FAILED' } };
  assert.equal((await (await post(`/videos/${mediaId}/confirm`)).json()).data.file.status, 'FAILED');
  const before = state.requests.length;
  for (const body of [{ ...input, sizeBytes: 1024 * 1024 * 1024 + 1 }, { ...input, mimeType: 'image/png' }, { ...input, originalName: '../x.mp4' }, { ...input, sha256: 'z'.repeat(64) }, { ...input, extra: 1 }])
    assert.equal((await post('/videos/uploads', body)).status, 400);
  assert.equal(state.requests.length, before);
});

test('capacitación: errores de dominio se sanitizan y los demás se reducen a códigos genéricos', async t => {
  const { state, post, get } = await fixture(t);
  const send = status => { state.status = status; };
  send(409); state.payload = { errors: [{ code: 'TRAINING_VERSION_OUTDATED', message: 'SQL private', fields: { versionId: 'privado' } }] };
  let response = await post(`/courses/${courseId}/attempts`, attempt);
  assert.equal(response.status, 409);
  let body = await response.json();
  assert.equal(body.errors[0].code, 'TRAINING_VERSION_OUTDATED');
  assert.doesNotMatch(JSON.stringify(body), /SQL|privado/);
  state.payload = { errors: [{ code: 'DB_INTERNAL', message: 'private.secret' }] };
  body = await (await post(`/courses/${courseId}/attempts`, attempt)).json();
  assert.equal(body.errors[0].code, 'RESOURCE_CONFLICT');
  send(422); state.payload = { errors: [{ code: 'TRAINING_ANSWERS_INVALID', message: 'x', fields: { answers: 'falta' } }] };
  assert.equal((await (await post(`/courses/${courseId}/attempts`, attempt)).json()).errors[0].code, 'TRAINING_ANSWERS_INVALID');
  send(404); state.payload = { errors: [{ code: 'TRAINING_COURSE_NOT_FOUND', message: 'x' }] };
  assert.equal((await (await get(`/courses/${courseId}`)).json()).errors[0].code, 'TRAINING_COURSE_NOT_FOUND');
  send(401); state.payload = { errors: [{ code: 'SESSION_EXPIRED', message: 'x' }] };
  assert.equal((await get(`/courses/${courseId}`)).status, 401);
  send(500); state.payload = { errors: [{ code: 'INTERNAL_ERROR', message: 'stack' }] };
  assert.equal((await get(`/courses/${courseId}`)).status, 500);
  send(502); state.payload = 'html';
  assert.equal((await get(`/courses/${courseId}`)).status, 503);
  state.status = 200; state.payload = '<html>'; assert.equal((await get(`/courses/${courseId}`)).status, 503);
  state.redirect = true; assert.equal((await get(`/courses/${courseId}`)).status, 503);
});

test('capacitación: rutas, métodos, sesión y origen quedan cerrados', async t => {
  const { state, base, get, post, put } = await fixture(t);
  assert.equal((await fetch(base + `/courses/${courseId}`)).status, 401);
  assert.equal((await get(`/courses/${courseId}/otro`)).status, 404);
  assert.equal((await get('/otra-cosa')).status, 404);
  assert.equal((await get(`/courses/${courseId}?x=1`)).status, 400);
  assert.equal((await get('/courses?status=OTRO')).status, 400);
  assert.equal((await get('/courses?status=DRAFT&status=PUBLISHED')).status, 400);
  assert.equal((await fetch(base + `/courses/${courseId}`, { method: 'DELETE', headers })).status, 405);
  assert.equal((await post(`/courses/${courseId}`)).status, 405);
  assert.equal((await put(`/courses/${courseId}/publish`, {})).status, 405);
  assert.equal((await get(`/courses/${courseId}/attempts`)).status, 405);
  assert.equal((await post('/courses', { code: 'M-02', title: 'Curso', description: '', estimatedDurationMinutes: 5 }, { Origin: 'https://foreign.example' })).status, 403);
  assert.equal((await fetch(base + '/courses', { method: 'POST', headers: { ...headers, 'Content-Type': 'text/plain' }, body: '{}' })).status, 415);
  assert.equal((await fetch(base + `/courses/${courseId}/progress`, { method: 'PUT', headers, body: Buffer.from([0x7b, 0x22, 0x61, 0x22, 0x3a, 0x22, 0xff, 0x22, 0x7d]) })).status, 400);
  assert.equal((await fetch(base + `/courses/${courseId}/progress`, { method: 'PUT', headers, body: 'x'.repeat(600000) })).status, 400);
  assert.equal(state.requests.length, 0);
});

test('capacitación: la CSP permite reproducir video solo desde el propio origen y R2', async t => {
  const { base } = await fixture(t);
  const response = await fetch(base.replace('/backend/api/v1/organizations/' + org + '/training', '/healthz'));
  const csp = response.headers.get('content-security-policy');
  assert.match(csp.match(/media-src[^;]*/)[0], /'self' https:\/\/\*\.r2\.cloudflarestorage\.com/);
  assert.doesNotMatch(csp.match(/img-src[^;]*/)[0], /r2\.cloudflarestorage\.com/);
});
