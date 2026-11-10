import { forwardSessionHeaders } from './session-headers.mjs';

// Proxy de Academia BPM (capacitación). Solo reenvía las rutas del contrato, valida cuerpos de
// entrada y proyecta respuestas públicas. Los videos (hasta 1 GiB) viajan directo a R2 con URL
// firmada: este proxy nunca recibe ni reenvía bytes de video.

async function readPayload(body, maximum = 4 * 1024 * 1024) {
  const chunks = []; let size = 0;
  for await (const chunk of body) {
    size += chunk.length;
    if (size > maximum) throw new Error('JSON demasiado grande');
    chunks.push(chunk);
  }
  return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)));
}

const idPattern = '[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}';
const route = new RegExp(`^/backend/api/v1/organizations/([A-Za-z0-9_-]{1,64})/training/(?:(courses)(?:/(${idPattern})(?:/(draft|publish|progress|study/answers|attempts|lessons/(${idPattern})/video))?)?|videos/(?:(uploads)|(${idPattern})/(confirm)))$`);
const uuid = value => typeof value === 'string' && new RegExp(`^${idPattern}$`).test(value) && value !== '00000000-0000-0000-0000-000000000000';
const same = (a, b) => typeof a === 'string' && typeof b === 'string' && a.toLowerCase() === b.toLowerCase();
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const need = value => { if (!value) throw new Error('Datos de capacitación no válidos'); };
const length = value => [...value].length;
const string = (value, max, min = 0) => typeof value === 'string' && length(value) <= max && length(value.trim()) >= min && !/[\uD800-\uDFFF]/u.test(value);
const exact = (value, fields) => need(object(value) && Object.keys(value).every(key => fields.includes(key)) && fields.every(key => key in value));
const integer = (value, min, max) => Number.isSafeInteger(value) && value >= min && value <= max;
const error = (code, message, fields = {}) => JSON.stringify({ errors: [{ code, message, fields }] });
const unavailable = send => send(503, error('BACKEND_UNAVAILABLE', 'No pudimos confirmar la operación de capacitación.'));

const progressStates = ['NOT_STARTED', 'IN_PROGRESS', 'COMPLETED'];
const courseStates = ['DRAFT', 'PUBLISHED', 'ARCHIVED'];
const attemptTypes = ['DIAGNOSTIC', 'FINAL'];
const videoTypes = ['video/mp4', 'video/webm'];
const maxVideoBytes = 1024 * 1024 * 1024;

function instant(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/.test(value) || value.startsWith('0000')) return false;
  const day = new Date(value.slice(0, 10) + 'T00:00:00Z');
  return Number.isFinite(day.getTime()) && day.toISOString().slice(0, 10) === value.slice(0, 10) && Number.isFinite(Date.parse(value));
}
const stamp = value => { need(instant(value)); return value; };
const optionalStamp = value => value == null ? null : stamp(value);

// ---- Proyecciones de salida ---------------------------------------------------------------

function courseInfo(value) {
  need(object(value) && uuid(value.id) && /^[A-Za-z0-9._-]{1,32}$/.test(value.code) && string(value.title, 200, 1) && string(value.description ?? '', 2000) && integer(value.estimatedDurationMinutes ?? 0, 0, 10080));
  return { id: value.id, code: value.code, title: value.title, description: value.description ?? '', estimatedDurationMinutes: value.estimatedDurationMinutes ?? 0 };
}
function progress(value) {
  need(object(value) && progressStates.includes(value.status) && (value.lastLessonId == null || uuid(value.lastLessonId)) &&
    (value.bestScore == null || integer(value.bestScore, 0, 100)) && integer(value.experiencePoints, 0, 1e9));
  return { status: value.status, lastLessonId: value.lastLessonId ?? null, bestScore: value.bestScore ?? null, experiencePoints: value.experiencePoints, completedAt: optionalStamp(value.completedAt) };
}
function certificate(value) {
  if (value == null) return null;
  need(object(value) && uuid(value.id) && typeof value.verificationCode === 'string' && /^[A-Za-z0-9-]{16,64}$/.test(value.verificationCode));
  return { id: value.id, verificationCode: value.verificationCode, issuedAt: stamp(value.issuedAt) };
}
function lessonVideo(value) {
  if (value == null) return null;
  need(object(value) && uuid(value.mediaId) && videoTypes.includes(value.mimeType) && integer(value.sizeBytes, 1, maxVideoBytes));
  return { mediaId: value.mediaId, mimeType: value.mimeType, sizeBytes: value.sizeBytes };
}
const unique = (items, key = 'id') => need(new Set(items.map(item => item[key].toLowerCase())).size === items.length);

/** Vista de aprendizaje: nunca contiene correctOption ni explanation aunque el servidor los enviara. */
function learningCourse(value, courseId) {
  need(object(value) && Array.isArray(value.lessons) && value.lessons.length <= 50 && Array.isArray(value.questions) && value.questions.length <= 100 && object(value.version));
  const course = courseInfo(value.course);
  need(same(course.id, courseId));
  need(uuid(value.version.id) && integer(value.version.versionNumber, 1, 1e9) && integer(value.version.passingScore, 0, 100));
  const lessons = value.lessons.map(lesson => {
    need(object(lesson) && uuid(lesson.id) && string(lesson.title, 200, 1) && string(lesson.content ?? '', 20000) && integer(lesson.displayOrder, 0, 1e6));
    return { id: lesson.id, title: lesson.title, content: lesson.content ?? '', displayOrder: lesson.displayOrder, video: lessonVideo(lesson.video) };
  });
  unique(lessons);
  const questions = value.questions.map(question => {
    need(object(question) && uuid(question.id) && (question.lessonId == null || uuid(question.lessonId)) && string(question.questionText, 300, 1) &&
      Array.isArray(question.options) && question.options.length >= 2 && question.options.length <= 6 && question.options.every(option => string(option, 300, 1)) && integer(question.displayOrder, 0, 1e6));
    need(question.lessonId == null || lessons.some(lesson => same(lesson.id, question.lessonId)));
    return { id: question.id, lessonId: question.lessonId ?? null, questionText: question.questionText, options: [...question.options], displayOrder: question.displayOrder };
  });
  unique(questions);
  return { course, version: { id: value.version.id, versionNumber: value.version.versionNumber, passingScore: value.version.passingScore }, lessons, questions, progress: progress(value.progress), certificate: certificate(value.certificate) };
}

function adminItem(value, status) {
  need(object(value) && uuid(value.id) && /^[A-Za-z0-9._-]{1,32}$/.test(value.code) && string(value.title, 200, 1) && courseStates.includes(value.status) &&
    (value.publishedVersionNumber == null || integer(value.publishedVersionNumber, 1, 1e9)) && typeof value.hasDraft === 'boolean' && (!status || value.status === status));
  return { id: value.id, code: value.code, title: value.title, status: value.status, publishedVersionNumber: value.publishedVersionNumber ?? null, hasDraft: value.hasDraft };
}

function draftBody(value, courseId, withCourse) {
  need(object(value) && object(value.version) && Array.isArray(value.lessons) && value.lessons.length <= 50 && Array.isArray(value.questions) && value.questions.length <= 100);
  const version = value.version;
  need(uuid(version.id) && integer(version.versionNumber, 1, 1e9) && integer(version.passingScore, 0, 100) && typeof version.rowVersion === 'string' && /^[\x21-\x7e]{1,64}$/.test(version.rowVersion));
  const lessons = value.lessons.map(lesson => {
    need(object(lesson) && uuid(lesson.id) && string(lesson.title, 200, 1) && string(lesson.content ?? '', 20000) && integer(lesson.displayOrder, 0, 1e6) && (lesson.videoMediaId == null || uuid(lesson.videoMediaId)));
    return { id: lesson.id, title: lesson.title, content: lesson.content ?? '', displayOrder: lesson.displayOrder, videoMediaId: lesson.videoMediaId ?? null };
  });
  unique(lessons);
  const questions = value.questions.map(question => {
    need(object(question) && uuid(question.id) && (question.lessonId == null || uuid(question.lessonId)) && string(question.questionText, 300, 1) && Array.isArray(question.options) &&
      question.options.length >= 2 && question.options.length <= 6 && question.options.every(option => string(option, 300, 1)) && integer(question.correctOption, 0, question.options.length - 1) &&
      string(question.explanation ?? '', 1000) && integer(question.displayOrder, 0, 1e6));
    need(question.lessonId == null || lessons.some(lesson => same(lesson.id, question.lessonId)));
    need(new Set(question.options.map(option => option.trim())).size === question.options.length);
    return { id: question.id, lessonId: question.lessonId ?? null, questionText: question.questionText, options: [...question.options], correctOption: question.correctOption, explanation: question.explanation ?? '', displayOrder: question.displayOrder };
  });
  unique(questions);
  const output = { version: { id: version.id, versionNumber: version.versionNumber, passingScore: version.passingScore, rowVersion: version.rowVersion }, lessons, questions };
  if (withCourse) {
    const course = courseInfo(value.course);
    need(same(course.id, courseId));
    return { course, ...output };
  }
  return output;
}

function videoTicket(value) {
  need(object(value) && typeof value.url === 'string' && value.url.length <= 8192 && value.url.trim() === value.url && !/[\u0000-\u001f\u007f]/.test(value.url) && videoTypes.includes(value.mimeType) && instant(value.expiresAt) && Date.parse(value.expiresAt) > Date.now());
  const target = new URL(value.url);
  need(target.protocol === 'https:' && (!target.port || target.port === '443') && !target.username && !target.password && !target.hash && /^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+r2\.cloudflarestorage\.com$/.test(target.hostname));
  return { url: value.url, mimeType: value.mimeType, expiresAt: value.expiresAt };
}

function uploadTicket(value, input) {
  need(object(value) && value.method === 'PUT' && typeof value.url === 'string' && value.url.length <= 8192 && value.url.trim() === value.url && !/[\u0000-\u001f\u007f]/.test(value.url) && instant(value.expiresAt) && Date.parse(value.expiresAt) > Date.now() && object(value.headers ?? {}));
  const target = new URL(value.url);
  need(target.protocol === 'https:' && (!target.port || target.port === '443') && !target.username && !target.password && !target.hash && /^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+r2\.cloudflarestorage\.com$/.test(target.hostname));
  const permitted = ['content-type', 'if-none-match', 'cache-control', 'content-disposition', 'x-amz-meta-sha256', 'host', 'content-length', 'x-amz-checksum-sha256', 'x-amz-content-sha256'];
  const entries = Object.entries(value.headers ?? {});
  const normalized = Object.fromEntries(entries.map(([key, values]) => [key.toLowerCase(), values]));
  need(entries.length <= 10 && Object.keys(normalized).length === entries.length && entries.every(([key, values]) => permitted.includes(key.toLowerCase()) &&
    Array.isArray(values) && values.length >= 1 && values.length <= 4 && values.every(item => typeof item === 'string' && item.length <= 4096 && !/[\u0000-\u001f\u007f-\u009f]/.test(item))));
  const exactHeader = (key, expected) => normalized[key]?.length === 1 && normalized[key][0] === expected;
  need(exactHeader('content-type', input.mimeType) && (!normalized['if-none-match'] || exactHeader('if-none-match', '*')) &&
    (!normalized['x-amz-meta-sha256'] || exactHeader('x-amz-meta-sha256', input.sha256)) && (!normalized.host || exactHeader('host', target.host)) &&
    (!normalized['content-length'] || exactHeader('content-length', String(input.sizeBytes))));
  return { method: 'PUT', url: value.url, headers: Object.fromEntries(entries.map(([key, values]) => [key, [...values]])), expiresAt: value.expiresAt };
}

function mediaFile(value, { id, input, status } = {}) {
  need(object(value) && uuid(value.id) && string(value.originalName, 200, 1) && videoTypes.includes(value.mimeType) && integer(value.sizeBytes, 1, maxVideoBytes) &&
    ['PENDING_UPLOAD', 'AVAILABLE', 'FAILED'].includes(value.status) && (!id || same(value.id, id)) && (!status || value.status === status) &&
    (!input || same(value.id, input.id) && value.originalName === input.originalName && value.mimeType === input.mimeType && value.sizeBytes === input.sizeBytes));
  return { id: value.id, originalName: value.originalName, mimeType: value.mimeType, sizeBytes: value.sizeBytes, status: value.status };
}

// ---- Validación de entradas ----------------------------------------------------------------

function inputFor(kind, value) {
  switch (kind) {
    case 'create':
      exact(value, ['code', 'title', 'description', 'estimatedDurationMinutes']);
      need(/^[A-Za-z0-9._-]{1,32}$/.test(value.code) && string(value.title, 200, 2) && string(value.description, 2000) && integer(value.estimatedDurationMinutes, 1, 10080));
      return { code: value.code, title: value.title.trim(), description: value.description, estimatedDurationMinutes: value.estimatedDurationMinutes };
    case 'progress':
      exact(value, ['lastLessonId']); need(uuid(value.lastLessonId));
      return { lastLessonId: value.lastLessonId };
    case 'study':
      exact(value, ['questionId', 'selectedOption']); need(uuid(value.questionId) && integer(value.selectedOption, 0, 5));
      return { questionId: value.questionId, selectedOption: value.selectedOption };
    case 'attempt': {
      exact(value, ['id', 'clientOperationId', 'versionId', 'attemptType', 'startedAt', 'clientCreatedAt', 'answers']);
      need(uuid(value.id) && uuid(value.clientOperationId) && uuid(value.versionId) && attemptTypes.includes(value.attemptType) && instant(value.startedAt) && instant(value.clientCreatedAt) &&
        Date.parse(value.startedAt) <= Date.parse(value.clientCreatedAt) && Date.parse(value.clientCreatedAt) <= Date.now() + 5 * 60 * 1000 &&
        Array.isArray(value.answers) && value.answers.length >= 1 && value.answers.length <= 100);
      value.answers.forEach(answer => { exact(answer, ['questionId', 'selectedOption']); need(uuid(answer.questionId) && integer(answer.selectedOption, 0, 5)); });
      unique(value.answers, 'questionId');
      return { id: value.id, clientOperationId: value.clientOperationId, versionId: value.versionId, attemptType: value.attemptType, startedAt: value.startedAt, clientCreatedAt: value.clientCreatedAt,
        answers: value.answers.map(answer => ({ questionId: answer.questionId, selectedOption: answer.selectedOption })) };
    }
    case 'draft':
      exact(value, ['version', 'lessons', 'questions']);
      exact(value.version, ['id', 'versionNumber', 'passingScore', 'rowVersion']);
      need(Array.isArray(value.lessons) && Array.isArray(value.questions));
      value.lessons.forEach(lesson => exact(lesson, ['id', 'title', 'content', 'displayOrder', 'videoMediaId']));
      value.questions.forEach(question => exact(question, ['id', 'lessonId', 'questionText', 'options', 'correctOption', 'explanation', 'displayOrder']));
      return draftBody(value, undefined, false);
    case 'upload':
      exact(value, ['id', 'originalName', 'mimeType', 'sizeBytes', 'sha256']);
      need(uuid(value.id) && string(value.originalName, 200, 1) && !/[\/\\\u0000-\u001f\u007f-\u009f]/u.test(value.originalName) && value.originalName.trim() === value.originalName &&
        videoTypes.includes(value.mimeType) && integer(value.sizeBytes, 1, maxVideoBytes) && typeof value.sha256 === 'string' && /^[0-9a-f]{64}$/.test(value.sha256));
      return { id: value.id, originalName: value.originalName, mimeType: value.mimeType, sizeBytes: value.sizeBytes, sha256: value.sha256 };
    default:
      need(object(value) && Object.keys(value).length === 0);
      return {};
  }
}

export const trainingRoute = path => route.test(path);

function originAllowed(request) {
  if (!request.headers.origin) return true;
  try {
    const origin = new URL(request.headers.origin);
    return ['http:', 'https:'].includes(origin.protocol) && !origin.username && !origin.password && origin.host === request.headers.host && origin.origin === request.headers.origin;
  } catch { return false; }
}

/** Resuelve qué operación del contrato corresponde a la ruta y al método, o null si no existe. */
function operation(match, method) {
  const [, , courses, courseId, child, lessonId, uploads, mediaId] = match;
  if (uploads) return method === 'POST' ? { kind: 'upload' } : null;
  if (mediaId) return method === 'POST' ? { kind: 'confirm' } : null;
  if (!courseId) return { GET: { kind: 'adminList' }, POST: { kind: 'create' } }[method] ?? null;
  if (!child) return method === 'GET' ? { kind: 'detail' } : null;
  if (lessonId) return method === 'POST' ? { kind: 'video' } : null;
  if (child === 'draft') return { GET: { kind: 'draftRead' }, PUT: { kind: 'draft' } }[method] ?? null;
  if (child === 'publish') return method === 'POST' ? { kind: 'publish' } : null;
  if (child === 'progress') return method === 'PUT' ? { kind: 'progress' } : null;
  if (child === 'study/answers') return method === 'POST' ? { kind: 'study' } : null;
  if (child === 'attempts') return method === 'POST' ? { kind: 'attempt' } : null;
  return null;
}

/** Proyecta solo el contrato público; la API reautoriza cada lectura, escritura y repetición. */
export async function proxyTraining(request, url, upstream, send, response) {
  const match = url.pathname.match(route);
  if (!match) return send(404, error('NOT_FOUND', 'Recurso no disponible.'));
  const [, , , courseId, , , , mediaId] = match;
  const op = operation(match, request.method);
  if (!op) return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));
  const writing = request.method !== 'GET';
  // Solo el listado de administración admite filtro; ninguna otra ruta acepta consulta.
  let query = '';
  try {
    if (op.kind === 'adminList') {
      const params = [...url.searchParams];
      need(params.length <= 1 && params.every(([key, value]) => key === 'status' && courseStates.includes(value)));
      if (params.length) query = '?' + new URLSearchParams([['status', params[0][1]]]);
    } else need(!url.search);
    if (!writing) need(!request.headers['transfer-encoding'] && (!request.headers['content-length'] || request.headers['content-length'] === '0'));
  } catch { return send(400, error('INVALID_REQUEST', 'Revisa los filtros de la solicitud.')); }
  const authorization = request.headers.authorization;
  if (typeof authorization !== 'string' || authorization.length > 8192 || !/^Bearer [A-Za-z0-9._-]+$/.test(authorization)) return send(401, error('UNAUTHENTICATED', 'Inicia sesión para continuar.'));
  const headers = { Accept: 'application/json', Authorization: authorization };
  let input, body;
  if (writing) {
    if (!originAllowed(request)) return send(403, error('ORIGIN_NOT_ALLOWED', 'Origen no permitido.'));
    if (!/^application\/json(?:\s*;[^\r\n]*)?$/i.test(request.headers['content-type'] || '')) return send(415, error('UNSUPPORTED_MEDIA_TYPE', 'Envía una solicitud JSON.'));
    try {
      const kind = op.kind === 'confirm' || op.kind === 'publish' || op.kind === 'video' ? 'empty' : op.kind;
      input = inputFor(kind, await readPayload(request, op.kind === 'draft' ? 1024 * 1024 : 512 * 1024));
      body = JSON.stringify(input);
      headers['Content-Type'] = 'application/json';
    } catch { return send(400, error('INVALID_REQUEST', 'Revisa los datos de la solicitud.')); }
  }
  response?.setHeader('Cache-Control', 'no-store');
  let result;
  try {
    result = await fetch(new URL(url.pathname.slice('/backend'.length) + query, upstream), { method: request.method, headers, body, redirect: 'error', cache: 'no-store', credentials: 'omit', signal: AbortSignal.timeout(10000) });
    forwardSessionHeaders(result, response);
    if (!result.ok) {
      const failures = {
        400: ['INVALID_REQUEST', 'Revisa los datos de la solicitud.'], 401: ['UNAUTHENTICATED', 'Inicia sesión nuevamente.'],
        403: ['ACCESS_DENIED', 'No tienes permiso para esta operación.'], 404: ['NOT_FOUND', 'El recurso no está disponible.'],
        409: ['RESOURCE_CONFLICT', 'El contenido cambió. Actualiza antes de continuar.'], 413: ['MEDIA_TOO_LARGE', 'El video supera el tamaño permitido.'],
        422: ['VALIDATION_ERROR', 'Revisa los campos indicados.'], 429: ['RATE_LIMITED', 'Espera un momento antes de reintentar.'], 500: ['INTERNAL_ERROR', 'No se pudo completar la solicitud.'],
      };
      if (!failures[result.status]) return unavailable(send);
      const payload = await readPayload(result.body, 65536);
      need(object(payload) && Array.isArray(payload.errors) && payload.errors.length > 0 && payload.errors.length <= 100);
      const code = payload.errors[0]?.code;
      need(payload.errors.every(item => object(item) && typeof item.code === 'string' && (item.fields === undefined || object(item.fields))));
      if (result.status === 401 && code === 'SESSION_EXPIRED') return send(401, error('SESSION_EXPIRED', 'Tu sesión venció. Inicia sesión nuevamente.'));
      // Códigos de dominio que la app distingue; el resto se reduce al código genérico.
      const domain = {
        404: { TRAINING_COURSE_NOT_FOUND: 'El curso no tiene una versión publicada.', TRAINING_LESSON_NOT_FOUND: 'La lección no está disponible.', TRAINING_QUESTION_NOT_FOUND: 'La pregunta no está disponible.', TRAINING_VIDEO_NOT_FOUND: 'El video no está disponible.', TRAINING_DRAFT_NOT_FOUND: 'El borrador no está disponible.' },
        409: { TRAINING_VERSION_OUTDATED: 'El curso se actualizó. Cargamos la versión vigente.', TRAINING_COURSE_CODE_TAKEN: 'Ese código de curso ya existe.',
          TRAINING_DRAFT_CONFLICT: 'El borrador cambió. Recarga antes de guardar.', IDEMPOTENCY_CONFLICT: 'Esta operación ya se registró con otros datos.', UPLOAD_NOT_READY: 'El video todavía no está confirmado.', TRAINING_VIDEO_NOT_READY: 'El video todavía no está confirmado.', TRAINING_VIDEO_CONFLICT: 'El video ya existe con otros datos.' },
        422: { TRAINING_ANSWERS_INVALID: 'Responde todas las preguntas una sola vez.', TRAINING_DRAFT_INCOMPLETE: 'El borrador está incompleto.', MEDIA_CONTENT_INVALID: 'El video no se pudo validar.', TRAINING_VIDEO_INVALID: 'El video no se pudo validar.' },
      }[result.status];
      if (domain?.[code]) return send(result.status, error(code, domain[code]));
      return send(result.status, error(...failures[result.status]));
    }
    const payload = await readPayload(result.body, 4 * 1024 * 1024);
    need(object(payload) && object(payload.data));
    const data = payload.data;
    let projected, status = 200;
    switch (op.kind) {
      case 'detail': projected = learningCourse(data, courseId); break;
      case 'video': projected = videoTicket(data); break;
      case 'progress': projected = { progress: progress(data.progress) }; break;
      case 'study': {
        need(typeof data.correct === 'boolean' && integer(data.correctOption, 0, 5) && string(data.explanation ?? '', 2000) && data.correct === (input.selectedOption === data.correctOption));
        projected = { correct: data.correct, correctOption: data.correctOption, explanation: data.explanation ?? '' };
        break;
      }
      case 'attempt': {
        status = result.status === 201 ? 201 : 200;
        need(object(data.attempt) && Array.isArray(data.results) && data.results.length === input.answers.length);
        const attempt = data.attempt;
        need(same(attempt.id, input.id) && attempt.attemptType === input.attemptType && integer(attempt.score, 0, 100) && typeof attempt.passed === 'boolean');
        const results = data.results.map(item => {
          const answer = input.answers.find(candidate => same(candidate.questionId, item?.questionId));
          need(object(item) && answer && item.selectedOption === answer.selectedOption && typeof item.correct === 'boolean' && integer(item.correctOption, 0, 5) &&
            item.correct === (item.selectedOption === item.correctOption) && string(item.explanation ?? '', 2000));
          return { questionId: item.questionId, selectedOption: item.selectedOption, correct: item.correct, correctOption: item.correctOption, explanation: item.explanation ?? '' };
        });
        unique(results, 'questionId');
        need(attempt.score === Math.round(results.filter(item => item.correct).length * 100 / results.length));
        projected = { attempt: { id: attempt.id, attemptType: attempt.attemptType, score: attempt.score, passed: attempt.passed, completedAt: stamp(attempt.completedAt) }, results, progress: progress(data.progress), certificate: certificate(data.certificate) };
        break;
      }
      case 'adminList':
        need(Array.isArray(data.items) && data.items.length <= 100);
        projected = { items: data.items.map(item => adminItem(item, url.searchParams.get('status'))) };
        unique(projected.items);
        break;
      case 'create': {
        status = result.status === 201 ? 201 : 200;
        const course = courseInfo(data.course ?? data);
        need((data.status ?? data.course?.status) === 'DRAFT' && course.code === input.code && course.title === input.title);
        projected = { ...course, status: 'DRAFT' };
        break;
      }
      case 'draftRead': projected = draftBody(data, courseId, true); break;
      case 'draft': projected = draftBody(data, courseId, false); break;
      // El contrato no define el cuerpo de publicación: solo se confirma el nuevo estado.
      case 'publish': projected = { status: 'PUBLISHED' }; break;
      case 'upload': {
        status = result.status === 201 ? 201 : 200;
        const file = mediaFile(data.file, { input });
        need(['PENDING_UPLOAD', 'AVAILABLE'].includes(file.status));
        projected = { file, upload: file.status === 'AVAILABLE' || data.upload == null ? null : uploadTicket(data.upload, input) };
        need(file.status === 'AVAILABLE' || projected.upload);
        break;
      }
      case 'confirm': {
        const file = mediaFile(data.file ?? data, { id: mediaId });
        need(['AVAILABLE', 'FAILED'].includes(file.status));
        projected = { file };
        break;
      }
      default: throw new Error('Operación desconocida');
    }
    return send(status, JSON.stringify({ data: projected }));
  } catch { return unavailable(send);
  } finally { if (result && !result.bodyUsed) await result.body?.cancel().catch(() => {}); }
}
