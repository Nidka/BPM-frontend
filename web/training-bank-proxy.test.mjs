import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';

// Banco oficial de preguntas BPM: evaluación sorteada, práctica con fuentes, resultados con ítem y banco de administración.
const org = 'tenant_training';
const courseId = '10000000-0000-4000-8000-000000000001';
const versionId = '20000000-0000-4000-8000-000000000001';
const lessonId = '30000000-0000-4000-8000-000000000001';
const q1 = '40000000-0000-4000-8000-000000000001';
const q2 = '40000000-0000-4000-8000-000000000002';
const attemptId = '50000000-0000-4000-8000-000000000001';
const operationId = '60000000-0000-4000-8000-000000000001';
const stamp = '2026-01-01T12:00:00Z';
const headers = { Authorization: 'Bearer test.token', 'Content-Type': 'application/json' };
const progress = { status: 'IN_PROGRESS', lastLessonId: lessonId, bestScore: null, experiencePoints: 0, completedAt: null, completedLessonIds: [], completedLessons: 0, totalLessons: 1, percent: 0 };
const course = { id: courseId, code: 'BPM-M01', title: 'Módulo 1. Inocuidad', description: 'x', estimatedDurationMinutes: 25 };
const source = { code: 'P01', label: 'NTS 142 (P01)', title: 'MINSA (2018). NTS N.º 142', url: 'https://www.digesa.minsa.gob.pe/normaslegales/normas/rm_822-2018-minsa.pdf' };

async function fixture(t) {
  const state = { status: 200, payload: {}, requests: [] };
  const backend = http.createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    state.requests.push({ path: req.url, method: req.method, body: Buffer.concat(chunks).toString('utf8') });
    res.writeHead(state.status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(state.payload));
  });
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
  const app = createAppServer({ apiBaseUrl: `http://127.0.0.1:${backend.address().port}`, allowInsecureForTests: true });
  app.listen(0, '127.0.0.1'); await once(app, 'listening');
  t.after(async () => { app.closeAllConnections(); backend.closeAllConnections(); await Promise.all([new Promise(r => app.close(r)), new Promise(r => backend.close(r))]); });
  const base = `http://127.0.0.1:${app.address().port}/backend/api/v1/organizations/${org}/training`;
  const call = (method, path, body) => fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  return { state, get: path => call('GET', path), post: (path, body = {}) => call('POST', path, body) };
}

const evaluation = { id: attemptId, versionId, attemptType: 'FINAL', bankVersion: 1, attemptNumber: 1, passingScore: 80, createdAt: stamp,
  questions: [{ id: q1, lessonId, questionText: '¿Qué es inocuo?', options: ['a', 'b', 'c', 'd', 'e'], displayOrder: 1 }, { id: q2, lessonId: null, questionText: 'Otra', options: ['a', 'b', 'c', 'd', 'e'], displayOrder: 2 }] };

test('banco: iniciar la evaluación valida el cuerpo, conserva 201/200 y nunca deja pasar claves', async t => {
  const { state, post } = await fixture(t);
  state.status = 201;
  state.payload = { data: { evaluation: { ...evaluation, questions: evaluation.questions.map(q => ({ ...q, correctOption: 1, explanation: 'secreto', itemCode: 'M01-03', bloom: 'Aplicar' })) } } };
  const response = await post(`/courses/${courseId}/evaluations`, { id: attemptId, attemptType: 'FINAL' });
  assert.equal(response.status, 201);
  const body = await response.json();
  assert.equal(body.data.evaluation.questions.length, 2);
  for (const forbidden of ['correctOption', 'explanation', 'itemCode', 'bloom']) assert.equal(JSON.stringify(body).includes(forbidden), false, forbidden);
  assert.equal(state.requests[0].path, `/api/v1/organizations/${org}/training/courses/${courseId}/evaluations`);
  assert.deepEqual(JSON.parse(state.requests[0].body), { id: attemptId, attemptType: 'FINAL' });
  state.status = 200;
  assert.equal((await post(`/courses/${courseId}/evaluations`, { id: attemptId, attemptType: 'DIAGNOSTIC' })).status, 503, 'el tipo devuelto debe coincidir con el pedido');
  // Entradas inválidas no llegan al backend.
  const before = state.requests.length;
  for (const bad of [{ id: 'x', attemptType: 'FINAL' }, { id: attemptId, attemptType: 'MODULE' }, { id: attemptId }, { id: attemptId, attemptType: 'FINAL', extra: 1 }])
    assert.equal((await post(`/courses/${courseId}/evaluations`, bad)).status, 400);
  assert.equal(state.requests.length, before);
  // Respuestas mal formadas del backend.
  for (const data of [{ evaluation: { ...evaluation, id: operationId } }, { evaluation: { ...evaluation, questions: [] } }, { evaluation: { ...evaluation, questions: [evaluation.questions[0], evaluation.questions[0]] } },
    { evaluation: { ...evaluation, questions: [{ ...evaluation.questions[0], options: ['solo'] }] } }]) {
    state.payload = { data }; assert.equal((await post(`/courses/${courseId}/evaluations`, { id: attemptId, attemptType: 'FINAL' })).status, 503);
  }
});

test('banco: la evaluación sin iniciar y un curso sin banco devuelven sus códigos de dominio', async t => {
  const { state, post } = await fixture(t);
  state.status = 409; state.payload = { errors: [{ code: 'TRAINING_EVALUATION_NOT_STARTED', message: 'x' }] };
  const notStarted = await post(`/courses/${courseId}/attempts`, { id: attemptId, clientOperationId: operationId, versionId, attemptType: 'FINAL', startedAt: stamp, clientCreatedAt: stamp, answers: [{ questionId: q1, selectedOption: 1 }] });
  assert.equal(notStarted.status, 409);
  assert.equal((await notStarted.json()).errors[0].code, 'TRAINING_EVALUATION_NOT_STARTED');
  state.status = 422; state.payload = { errors: [{ code: 'TRAINING_NO_QUESTION_BANK', message: 'x' }] };
  const noBank = await post(`/courses/${courseId}/evaluations`, { id: attemptId, attemptType: 'FINAL' });
  assert.equal(noBank.status, 422);
  assert.equal((await noBank.json()).errors[0].code, 'TRAINING_NO_QUESTION_BANK');
});

test('banco: el detalle trae questionBank y review, y un backend anterior los omite', async t => {
  const { state, get } = await fixture(t);
  const base = { course, version: { id: versionId, versionNumber: 3, passingScore: 80 }, lessons: [{ id: lessonId, title: 'Unidad 1', content: 'x', displayOrder: 1, video: null, externalVideo: null, keyPoints: [] }],
    questions: [evaluation.questions[0]], progress, certificate: null };
  state.payload = { data: { ...base, questionBank: { version: 1, itemsPerAttempt: 5, poolSize: 20 }, review: { dueCount: 2, questionIds: [q1], extra: 'x' } } };
  const full = await (await get(`/courses/${courseId}`)).json();
  assert.deepEqual(full.data.questionBank, { version: 1, itemsPerAttempt: 5, poolSize: 20 });
  assert.deepEqual(full.data.review, { dueCount: 2, questionIds: [q1] });
  state.payload = { data: base };
  const old = await (await get(`/courses/${courseId}`)).json();
  assert.equal('questionBank' in old.data, false);
  assert.equal('review' in old.data, false);
  state.payload = { data: { ...base, review: { dueCount: 1, questionIds: ['no-uuid'] } } };
  assert.equal((await get(`/courses/${courseId}`)).status, 503);
});

test('banco: la práctica devuelve ítem, fuentes y XP; la evaluación terminada trae el ítem y sus fuentes', async t => {
  const { state, post } = await fixture(t);
  state.payload = { data: { correct: true, correctOption: 1, explanation: 'Porque sí', itemCode: 'M01-03', bloom: 'Aplicar', sources: [source], xpEarned: 2 } };
  const study = await (await post(`/courses/${courseId}/study/answers`, { questionId: q1, selectedOption: 1 })).json();
  assert.deepEqual(study.data, { correct: true, correctOption: 1, explanation: 'Porque sí', itemCode: 'M01-03', sources: [source], xpEarned: 2 });
  state.payload = { data: { correct: false, correctOption: 2, explanation: 'x' } };
  assert.deepEqual((await (await post(`/courses/${courseId}/study/answers`, { questionId: q1, selectedOption: 1 })).json()).data, { correct: false, correctOption: 2, explanation: 'x' });
  state.payload = { data: { correct: true, correctOption: 1, explanation: 'x', itemCode: 'M99-01', sources: [] } };
  assert.equal((await post(`/courses/${courseId}/study/answers`, { questionId: q1, selectedOption: 1 })).status, 503);
  state.payload = { data: { correct: true, correctOption: 1, explanation: 'x', itemCode: 'M01-01', sources: [{ ...source, url: 'http://inseguro.example' }] } };
  assert.equal((await post(`/courses/${courseId}/study/answers`, { questionId: q1, selectedOption: 1 })).status, 503);

  state.status = 201;
  const item = (id, picked, correctPos, extra = {}) => ({ questionId: id, selectedOption: picked, correct: picked === correctPos, correctOption: correctPos, explanation: 'Justificación', itemCode: 'M01-03', bloom: 'Aplicar',
    bankVersion: 1, questionText: '¿Qué?', options: ['a', 'b', 'c', 'd', 'e'], sources: [source], ...extra });
  state.payload = { data: { attempt: { id: attemptId, attemptType: 'FINAL', score: 50, passed: false, completedAt: stamp }, results: [item(q1, 1, 1), item(q2, 0, 3)], progress, certificate: null, achievements: [], nextCourse: null } };
  const attempt = { id: attemptId, clientOperationId: operationId, versionId, attemptType: 'FINAL', startedAt: stamp, clientCreatedAt: stamp, answers: [{ questionId: q1, selectedOption: 1 }, { questionId: q2, selectedOption: 0 }] };
  const outcome = await (await post(`/courses/${courseId}/attempts`, attempt)).json();
  assert.equal(outcome.data.results[0].itemCode, 'M01-03');
  assert.deepEqual(outcome.data.results[0].sources, [source]);
  assert.equal(outcome.data.results[0].options.length, 5);
  assert.equal(outcome.data.results[1].correct, false);
  // Posiciones fuera de las alternativas mostradas se rechazan.
  state.payload = { data: { attempt: { id: attemptId, attemptType: 'FINAL', score: 50, passed: false, completedAt: stamp }, results: [item(q1, 1, 1), item(q2, 0, 3, { options: ['a', 'b'] })], progress, certificate: null } };
  assert.equal((await post(`/courses/${courseId}/attempts`, attempt)).status, 503);
});

test('banco: el banco del módulo es solo lectura, valida el curso y el nivel de Bloom', async t => {
  const { state, get, post } = await fixture(t);
  const item = { code: 'M01-03', bloom: 'Comprender', questionText: '¿Qué diferencia un peligro de un riesgo?', options: ['a', 'b', 'c', 'd', 'e'], correctOption: 1, key: 'B', justification: 'El peligro es…', sources: [source], recommended: true, lessonTitle: 'Inocuidad' };
  state.payload = { data: { bank: { course, module: 'M01', bankVersion: 1, passingScore: 80, items: [item] } } };
  const response = await get(`/courses/${courseId}/bank`);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  const body = await response.json();
  assert.equal(body.data.bank.items[0].key, 'B');
  assert.equal(body.data.bank.items[0].recommended, true);
  assert.equal((await post(`/courses/${courseId}/bank`, {})).status, 405);
  for (const bad of [{ ...item, bloom: 'Crear' }, { ...item, code: 'X01-03' }, { ...item, key: 'Z' }, { ...item, correctOption: 9 }]) {
    state.payload = { data: { bank: { course, module: 'M01', bankVersion: 1, passingScore: 80, items: [bad] } } };
    assert.equal((await get(`/courses/${courseId}/bank`)).status, 503);
  }
  state.payload = { data: { bank: { course: { ...course, id: versionId }, module: 'M01', bankVersion: 1, passingScore: 80, items: [item] } } };
  assert.equal((await get(`/courses/${courseId}/bank`)).status, 503);
});

test('banco: el borrador muestra el código de los ítems oficiales y no lo reenvía al guardar', async t => {
  const { state, get } = await fixture(t);
  state.payload = { data: { course, version: { id: versionId, versionNumber: 4, passingScore: 80, rowVersion: 'v1' }, lessons: [{ id: lessonId, title: 'U1', content: 'x', displayOrder: 1, videoMediaId: null, externalVideo: null, keyPoints: [] }],
    questions: [{ id: q1, lessonId, questionText: 'P', options: ['a', 'b'], correctOption: 1, explanation: 'e', displayOrder: 1, itemCode: 'M01-03' }] } };
  const draft = await (await get(`/courses/${courseId}/draft`)).json();
  assert.equal(draft.data.questions[0].itemCode, 'M01-03');
});
