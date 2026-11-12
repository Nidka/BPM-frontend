import { forwardSessionHeaders } from './session-headers.mjs';

// Proxy del estudio CAP (consentimiento, encuesta, investigación). Solo reenvía las rutas del
// contrato, valida cuerpos y consultas de entrada y proyecta respuestas públicas con listas de
// campos permitidos. La respuesta del participante nunca incluye puntajes ni respuestas correctas;
// el CSV anonimizado pasa en streaming con límite de tamaño y tipo text/csv.

async function readPayload(body, maximum = 2 * 1024 * 1024) {
  const chunks = []; let size = 0;
  for await (const chunk of body) {
    size += chunk.length;
    if (size > maximum) throw new Error('JSON demasiado grande');
    chunks.push(chunk);
  }
  return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)));
}

const idPattern = '[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}';
const orgRoute = new RegExp('^/backend/api/v1/organizations/([A-Za-z0-9_-]{1,64})/research/(participation|consent|submissions)$');
const platformRoute = new RegExp(`^/backend/api/v1/research/(access|access-grants|consent-documents|instruments|instruments/import|organizations|results|exports/responses\\.csv|needs|access-grants/(${idPattern})|instruments/(${idPattern})(?:/(draft|versions|publish))?)$`);
export const researchRoute = path => orgRoute.test(path) || platformRoute.test(path);

const uuid = value => typeof value === 'string' && new RegExp(`^${idPattern}$`).test(value) && value !== '00000000-0000-0000-0000-000000000000';
const same = (a, b) => typeof a === 'string' && typeof b === 'string' && a.toLowerCase() === b.toLowerCase();
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const need = value => { if (!value) throw new Error('Datos de investigación no válidos'); };
const length = value => [...value].length;
const string = (value, max, min = 0) => typeof value === 'string' && length(value) <= max && length(value.trim()) >= min && !/[\uD800-\uDFFF]/u.test(value);
const integer = (value, min, max) => Number.isSafeInteger(value) && value >= min && value <= max;
const nullable = (value, check) => value === null || value === undefined || check(value);
const error = (code, message, fields = {}) => JSON.stringify({ errors: [{ code, message, fields }] });
const unavailable = send => send(503, error('BACKEND_UNAVAILABLE', 'No pudimos confirmar la operación del estudio.'));
const exact = (value, required, optional = []) => need(object(value) && required.every(key => key in value) && Object.keys(value).every(key => required.includes(key) || optional.includes(key)));
const unique = (items, key) => need(new Set(items.map(item => String(item[key]).toLowerCase())).size === items.length);

function instant(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/.test(value) || value.startsWith('0000')) return false;
  const day = new Date(value.slice(0, 10) + 'T00:00:00Z');
  return Number.isFinite(day.getTime()) && day.toISOString().slice(0, 10) === value.slice(0, 10) && Number.isFinite(Date.parse(value));
}
const stamp = value => { need(instant(value)); return value; };
const optionalStamp = value => value == null ? null : stamp(value);

const moments = ['PRETEST', 'POSTTEST'];
const types = ['MULTIPLE_CHOICE', 'LIKERT', 'FREQUENCY'];
const dimensions = { MULTIPLE_CHOICE: 'KNOWLEDGE', LIKERT: 'ATTITUDE', FREQUENCY: 'PRACTICE' };
const categories = ['TRAINING', 'PROCESS', 'INFRASTRUCTURE', 'DOCUMENTATION', 'TECHNOLOGY', 'SUPPLIERS', 'OTHER'];
const versionText = value => typeof value === 'string' && /^[A-Za-z0-9._-]{1,40}$/.test(value);
const codeText = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,50}$/.test(value);
const maxCsvBytes = 16 * 1024 * 1024;

// ---- Proyecciones de salida ---------------------------------------------------------------

function consentDocument(value, withStatus = false) {
  need(object(value) && versionText(value.version) && string(value.title, 200, 1) && string(value.text, 20000, 1) && string(value.responsibleInvestigator, 200, 1));
  const output = { version: value.version, title: value.title, text: value.text, responsibleInvestigator: value.responsibleInvestigator, publishedAt: stamp(value.publishedAt) };
  if (withStatus) { need(['ACTIVE', 'ARCHIVED'].includes(value.status)); output.status = value.status; }
  return output;
}

/** Vista del participante: ni respuestas correctas ni puntajes aunque el servidor los enviara. */
function participation(data) {
  need(object(data) && typeof data.consentValid === 'boolean');
  const participant = nullable(data.participant, p => object(p) && /^P-[A-Z0-9]{8}$/.test(p.code) && ['ACTIVE', 'WITHDRAWN'].includes(p.status)) ? data.participant : need(false);
  const consent = data.consent == null ? null : (() => {
    const c = data.consent;
    need(object(c) && versionText(c.documentVersion) && typeof c.accepted === 'boolean' && string(c.responsibleInvestigator ?? '', 200));
    return { documentVersion: c.documentVersion, accepted: c.accepted, recordedAt: stamp(c.recordedAt), responsibleInvestigator: c.responsibleInvestigator ?? '' };
  })();
  let survey = null;
  if (data.survey != null) {
    const s = data.survey;
    need(data.consentValid && object(s) && string(s.instrumentCode, 50, 1) && string(s.name, 400, 1) && uuid(s.instrumentVersionId) && integer(s.versionNumber, 1, 1e9) && Array.isArray(s.questions) && s.questions.length >= 1 && s.questions.length <= 60);
    const questions = s.questions.map(q => {
      need(object(q) && uuid(q.id) && integer(q.order, 0, 1e6) && types.includes(q.type) && q.dimension === dimensions[q.type] && string(q.text, 1000, 1) &&
        Array.isArray(q.options) && q.options.every(option => string(option, 300, 1)) && (q.type === 'MULTIPLE_CHOICE' ? q.options.length >= 2 && q.options.length <= 6 : q.options.length === 5));
      return { id: q.id, order: q.order, dimension: q.dimension, type: q.type, text: q.text, options: [...q.options] };
    });
    unique(questions, 'id');
    survey = { instrumentCode: s.instrumentCode, name: s.name, instrumentVersionId: s.instrumentVersionId, versionNumber: s.versionNumber, questions };
  }
  need(Array.isArray(data.submissions) && data.submissions.length <= 100);
  const submissions = data.submissions.map(item => { need(object(item) && moments.includes(item.moment) && uuid(item.instrumentVersionId)); return { moment: item.moment, instrumentVersionId: item.instrumentVersionId, submittedAt: stamp(item.submittedAt) }; });
  return { participant: participant ?? null, consentDocument: data.consentDocument == null ? null : consentDocument(data.consentDocument), consent, consentValid: data.consentValid, survey, submissions };
}

function receipt(data, input) {
  need(object(data) && same(data.id, input.id) && data.moment === input.moment && same(data.instrumentVersionId, input.instrumentVersionId));
  return { id: data.id, moment: data.moment, instrumentVersionId: data.instrumentVersionId, submittedAt: stamp(data.submittedAt) };
}

function grant(value) {
  need(object(value) && uuid(value.userId) && string(value.email, 254, 3) && string(value.displayName ?? '', 200) && typeof value.active === 'boolean');
  return { userId: value.userId, email: value.email, displayName: value.displayName ?? '', grantedAt: stamp(value.grantedAt), revokedAt: optionalStamp(value.revokedAt), active: value.active };
}

function instrumentSummary(value) {
  need(object(value) && uuid(value.id) && codeText(value.code) && string(value.name, 200, 1) && string(value.description ?? '', 2000) && typeof value.isCap === 'boolean' && string(value.status, 30, 1));
  need(nullable(value.publishedVersionNumber, n => integer(n, 1, 1e9)) && (value.hasDraft === undefined || typeof value.hasDraft === 'boolean'));
  return { id: value.id, code: value.code, name: value.name, description: value.description ?? '', isCap: value.isCap, status: value.status, createdAt: stamp(value.createdAt), publishedVersionNumber: value.publishedVersionNumber ?? null, hasDraft: value.hasDraft ?? false };
}

const rowVersion = value => { need((typeof value === 'string' && /^[\x21-\x7e]{1,64}$/.test(value)) || integer(value, 0, Number.MAX_SAFE_INTEGER)); return value; };

function versionInfo(value) {
  need(object(value) && uuid(value.id) && integer(value.versionNumber, 1, 1e9) && string(value.status, 30, 1));
  return { id: value.id, versionNumber: value.versionNumber, status: value.status, rowVersion: rowVersion(value.rowVersion), publishedAt: optionalStamp(value.publishedAt) };
}

function draft(data) {
  need(object(data) && object(data.instrument) && object(data.version) && Array.isArray(data.questions) && data.questions.length <= 60);
  const instrument = instrumentSummary({ ...data.instrument, hasDraft: true });
  const questions = data.questions.map(q => {
    need(object(q) && types.includes(q.type) && string(q.text, 500, 0) && Array.isArray(q.options) && q.options.length <= 6 && q.options.every(option => string(option, 300)) && nullable(q.correctOption, n => integer(n, 0, q.options.length - 1)));
    return { type: q.type, text: q.text, options: [...q.options], correctOption: q.correctOption ?? null };
  });
  return { instrument: { id: instrument.id, code: instrument.code, name: instrument.name, description: instrument.description, isCap: instrument.isCap, status: instrument.status, createdAt: instrument.createdAt }, version: versionInfo(data.version), questions };
}

const stat = (value, keys) => {
  need(object(value) && integer(value.n, 0, 1e9));
  const output = { n: value.n };
  keys.forEach(key => { need(value[key] == null || (typeof value[key] === 'number' && Number.isFinite(value[key]))); output[key] = value[key] ?? null; });
  return output;
};

function resultRow(value) {
  need(object(value) && uuid(value.organizationId) && string(value.organizationName, 300, 1) && integer(value.participants, 0, 1e9));
  return { organizationId: value.organizationId, organizationName: value.organizationName, participants: value.participants,
    pretest: stat(value.pretest, ['knowledgeMean', 'attitudeMean', 'practiceMean']), posttest: stat(value.posttest, ['knowledgeMean', 'attitudeMean', 'practiceMean']),
    paired: stat(value.paired, ['knowledgeDelta', 'attitudeDelta', 'practiceDelta']) };
}

function needItem(value) {
  need(object(value) && uuid(value.id) && uuid(value.organizationId) && string(value.organizationName, 300, 1) && categories.includes(value.category) && string(value.description, 2000, 1) && string(value.investigatorName ?? '', 300));
  return { id: value.id, organizationId: value.organizationId, organizationName: value.organizationName, category: value.category, description: value.description,
    detectedAt: stamp(value.detectedAt), investigatorName: value.investigatorName ?? '', createdAt: stamp(value.createdAt) };
}

function listBody(root, project, max = 100) {
  need(object(root) && Array.isArray(root.data) && root.data.length <= max && object(root.pagination) && typeof (root.pagination.nextPageToken ?? '') === 'string' && /^[A-Za-z0-9_-]{0,2048}$/.test(root.pagination.nextPageToken ?? ''));
  return { data: root.data.map(project), pagination: { nextPageToken: root.pagination.nextPageToken ?? '' } };
}

// ---- Entradas ------------------------------------------------------------------------------

function questionInput(q) {
  exact(q, ['type', 'text'], ['options', 'correctOption']);
  need(types.includes(q.type) && string(q.text, 500, 0));
  const options = q.options ?? [];
  need(Array.isArray(options) && options.length <= 6 && options.every(option => string(option, 300)) && nullable(q.correctOption, n => integer(n, 0, 5)));
  return { type: q.type, text: q.text, options: [...options], correctOption: q.correctOption ?? null };
}

function inputFor(kind, value) {
  switch (kind) {
    case 'consent':
      exact(value, ['documentVersion', 'accepted']); need(versionText(value.documentVersion) && typeof value.accepted === 'boolean');
      return { documentVersion: value.documentVersion, accepted: value.accepted };
    case 'submission':
      exact(value, ['id', 'clientOperationId', 'instrumentVersionId', 'moment', 'clientCreatedAt', 'answers']);
      need(uuid(value.id) && uuid(value.clientOperationId) && uuid(value.instrumentVersionId) && moments.includes(value.moment) && instant(value.clientCreatedAt) &&
        Date.parse(value.clientCreatedAt) <= Date.now() + 5 * 60 * 1000 && Array.isArray(value.answers) && value.answers.length >= 1 && value.answers.length <= 60);
      value.answers.forEach(answer => { exact(answer, ['questionId', 'value']); need(uuid(answer.questionId) && integer(answer.value, 0, 5)); });
      unique(value.answers, 'questionId');
      return { id: value.id, clientOperationId: value.clientOperationId, instrumentVersionId: value.instrumentVersionId, moment: value.moment, clientCreatedAt: value.clientCreatedAt,
        answers: value.answers.map(answer => ({ questionId: answer.questionId, value: answer.value })) };
    case 'grant':
      exact(value, ['email']); need(string(value.email, 254, 3) && /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value.email));
      return { email: value.email };
    case 'consentDocument':
      exact(value, ['version', 'title', 'text', 'responsibleInvestigator']);
      need(versionText(value.version) && string(value.title, 200, 1) && string(value.text, 20000, 1) && string(value.responsibleInvestigator, 200, 1));
      return { version: value.version, title: value.title, text: value.text, responsibleInvestigator: value.responsibleInvestigator };
    case 'instrument': case 'import': {
      exact(value, ['code', 'name', 'questions'], kind === 'import' ? ['description', 'isCap', 'source', 'generalData'] : ['description', 'isCap']);
      need(codeText(value.code) && string(value.name, 200, 1) && string(value.description ?? '', 2000) && (value.isCap === undefined || typeof value.isCap === 'boolean') && Array.isArray(value.questions) && value.questions.length >= 1 && value.questions.length <= 60);
      const output = { code: value.code, name: value.name, questions: value.questions.map(questionInput) };
      if (value.description) output.description = value.description;
      if (value.isCap !== undefined) output.isCap = value.isCap;
      return output;
    }
    case 'draft':
      exact(value, ['rowVersion', 'questions']); rowVersion(value.rowVersion);
      need(Array.isArray(value.questions) && value.questions.length <= 60);
      return { rowVersion: value.rowVersion, questions: value.questions.map(questionInput) };
    case 'need':
      exact(value, ['organizationId', 'category', 'description'], ['detectedAt']);
      need(uuid(value.organizationId) && categories.includes(value.category) && string(value.description, 2000, 1) && (value.detectedAt === undefined || (instant(value.detectedAt) && Date.parse(value.detectedAt) <= Date.now() + 5 * 60 * 1000)));
      return value.detectedAt === undefined ? { organizationId: value.organizationId, category: value.category, description: value.description } : { organizationId: value.organizationId, category: value.category, description: value.description, detectedAt: value.detectedAt };
    default:
      need(object(value) && Object.keys(value).length === 0);
      return {};
  }
}

/** Resuelve operación y consultas permitidas; null si la ruta/método no existen. */
function operation(path, method) {
  const org = path.match(orgRoute);
  if (org) {
    const table = { participation: { GET: 'participation' }, consent: { POST: 'consent' }, submissions: { POST: 'submission' } }[org[2]];
    const kind = table[method];
    return kind ? { kind, params: [] } : null;
  }
  const m = path.match(platformRoute);
  const resource = m[1];
  const page = ['pageSize', 'pageToken'];
  const grantId = m[2], instrumentId = m[3], child = m[4];
  const pick = (map) => map[method] ? { kind: map[method][0], params: map[method][1] ?? [] } : null;
  if (grantId) return pick({ DELETE: ['revoke'] });
  if (instrumentId) {
    if (!child) return pick({ GET: ['instrumentDetail'] });
    if (child === 'draft') return pick({ GET: ['draftRead'], PUT: ['draft'] });
    return pick({ POST: [child === 'versions' ? 'newVersion' : 'publish'] });
  }
  switch (resource) {
    case 'access': return pick({ GET: ['access'] });
    case 'access-grants': return pick({ GET: ['grants', page], POST: ['grant'] });
    case 'consent-documents': return pick({ GET: ['documents', page], POST: ['consentDocument'] });
    case 'instruments': return pick({ GET: ['instruments', page], POST: ['instrument'] });
    case 'instruments/import': return pick({ POST: ['import'] });
    case 'organizations': return pick({ GET: ['organizations', page] });
    case 'results': return pick({ GET: ['results', [...page, 'instrumentVersionId']] });
    case 'exports/responses.csv': return pick({ GET: ['export', ['instrumentVersionId', 'organizationId']] });
    case 'needs': return pick({ GET: ['needs', [...page, 'organizationId']], POST: ['need'] });
    default: return null;
  }
}

function queryFor(url, allowed) {
  const seen = new Set(); const output = new URLSearchParams();
  for (const [key, value] of url.searchParams) {
    need(allowed.includes(key) && !seen.has(key)); seen.add(key);
    if (key === 'pageSize') need(/^\d{1,3}$/.test(value) && Number(value) >= 1 && Number(value) <= 100);
    else if (key === 'pageToken') need(/^[A-Za-z0-9_-]{1,2048}$/.test(value));
    else need(uuid(value));
    output.set(key, value);
  }
  const text = output.toString();
  return text ? '?' + text : '';
}

function originAllowed(request) {
  if (!request.headers.origin) return true;
  try {
    const origin = new URL(request.headers.origin);
    return ['http:', 'https:'].includes(origin.protocol) && !origin.username && !origin.password && origin.host === request.headers.host && origin.origin === request.headers.origin;
  } catch { return false; }
}

const failures = {
  400: ['INVALID_REQUEST', 'Revisa los datos de la solicitud.'], 401: ['UNAUTHENTICATED', 'Inicia sesión nuevamente.'],
  403: ['ACCESS_DENIED', 'No tienes permiso para esta operación.'], 404: ['NOT_FOUND', 'El recurso no está disponible.'],
  409: ['RESOURCE_CONFLICT', 'El contenido cambió. Actualiza antes de continuar.'], 413: ['TOO_LARGE', 'La solicitud es demasiado grande.'],
  422: ['VALIDATION_ERROR', 'Revisa los campos indicados.'], 429: ['RATE_LIMITED', 'Espera un momento antes de reintentar.'], 500: ['INTERNAL_ERROR', 'No se pudo completar la solicitud.'],
};
// Códigos de dominio que la app distingue; el resto se reduce al código genérico del estado.
const domain = {
  400: { INVALID_PAGINATION: 'La paginación no es válida.' },
  403: { RESEARCH_ACCESS_DENIED: 'No tienes acceso de investigación.', RESEARCH_CONSENT_REQUIRED: 'Necesitas el consentimiento vigente.' },
  404: { RESEARCH_PARTICIPANT_NOT_FOUND: 'No estás inscrito en el estudio.', RESEARCH_SURVEY_UNAVAILABLE: 'No hay encuesta publicada.', RESEARCH_USER_NOT_FOUND: 'No existe esa cuenta.',
    RESEARCH_GRANT_NOT_FOUND: 'No existe esa concesión.', RESEARCH_INSTRUMENT_NOT_FOUND: 'El cuestionario no existe.', RESEARCH_VERSION_NOT_FOUND: 'La versión no existe.',
    RESEARCH_DRAFT_NOT_FOUND: 'No hay borrador abierto.', RESEARCH_ORGANIZATION_NOT_FOUND: 'La empresa no existe.' },
  409: { RESEARCH_CONSENT_DOCUMENT_UNAVAILABLE: 'No hay consentimiento publicado.', RESEARCH_CONSENT_VERSION_OUTDATED: 'El consentimiento se actualizó.', RESEARCH_VERSION_OUTDATED: 'La encuesta se actualizó.',
    RESEARCH_SUBMISSION_EXISTS: 'Ya existe esa entrega.', IDEMPOTENCY_CONFLICT: 'La operación ya se registró con otros datos.', RESEARCH_CONSENT_VERSION_TAKEN: 'Esa versión ya existe.',
    RESEARCH_INSTRUMENT_CODE_TAKEN: 'Ese código ya existe.', RESEARCH_CAP_EXISTS: 'Ya existe un cuestionario CAP.', RESEARCH_DRAFT_CONFLICT: 'El borrador cambió.', RESEARCH_DRAFT_EXISTS: 'Ya existe un borrador.' },
  422: { RESEARCH_ANSWERS_INVALID: 'Responde todas las preguntas una sola vez.', RESEARCH_INSTRUMENT_INCOMPLETE: 'El cuestionario está incompleto.', RESEARCH_EXPORT_TOO_LARGE: 'Hay demasiadas filas para exportar.' },
};

/** Proyecta solo el contrato público; la API reautoriza cada lectura, escritura y repetición. */
export async function proxyResearch(request, url, upstream, send, response) {
  if (!researchRoute(url.pathname)) return send(404, error('NOT_FOUND', 'Recurso no disponible.'));
  const op = operation(url.pathname, request.method);
  if (!op) return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));
  const writing = !['GET', 'HEAD'].includes(request.method);
  const bodyless = ['newVersion', 'publish'].includes(op.kind);
  let query = '';
  try {
    need(request.method !== 'HEAD');
    query = queryFor(url, op.params);
    if (!writing || op.kind === 'revoke') need(!request.headers['transfer-encoding'] && (!request.headers['content-length'] || request.headers['content-length'] === '0'));
  } catch { return send(400, error('INVALID_REQUEST', 'Revisa los filtros de la solicitud.')); }
  const authorization = request.headers.authorization;
  if (typeof authorization !== 'string' || authorization.length > 8192 || !/^Bearer [A-Za-z0-9._-]+$/.test(authorization)) return send(401, error('UNAUTHENTICATED', 'Inicia sesión para continuar.'));
  const csv = op.kind === 'export';
  const headers = { Accept: csv ? 'text/csv' : 'application/json', Authorization: authorization };
  let input, body;
  if (writing && op.kind !== 'revoke') {
    if (!originAllowed(request)) return send(403, error('ORIGIN_NOT_ALLOWED', 'Origen no permitido.'));
    if (!/^application\/json(?:\s*;[^\r\n]*)?$/i.test(request.headers['content-type'] || '')) return send(415, error('UNSUPPORTED_MEDIA_TYPE', 'Envía una solicitud JSON.'));
    try {
      input = inputFor(bodyless ? 'empty' : op.kind, await readPayload(request, op.kind === 'import' || op.kind === 'draft' || op.kind === 'instrument' ? 1024 * 1024 : 256 * 1024));
      body = bodyless ? '{}' : JSON.stringify(input);
      headers['Content-Type'] = 'application/json';
    } catch { return send(400, error('INVALID_REQUEST', 'Revisa los datos de la solicitud.')); }
  }
  response?.setHeader('Cache-Control', 'no-store');
  let result;
  try {
    result = await fetch(new URL(url.pathname.slice('/backend'.length) + query, upstream), { method: request.method, headers, body, redirect: 'error', cache: 'no-store', credentials: 'omit', signal: AbortSignal.timeout(csv ? 60000 : 10000) });
    forwardSessionHeaders(result, response);
    if (!result.ok) {
      if (!failures[result.status]) return unavailable(send);
      const payload = await readPayload(result.body, 65536);
      need(object(payload) && Array.isArray(payload.errors) && payload.errors.length > 0 && payload.errors.length <= 100);
      const code = payload.errors[0]?.code;
      need(payload.errors.every(item => object(item) && typeof item.code === 'string' && (item.fields === undefined || object(item.fields))));
      if (result.status === 401 && code === 'SESSION_EXPIRED') return send(401, error('SESSION_EXPIRED', 'Tu sesión venció. Inicia sesión nuevamente.'));
      if (domain[result.status]?.[code]) return send(result.status, error(code, domain[result.status][code]));
      return send(result.status, error(...failures[result.status]));
    }
    if (csv) return await streamCsv(result, send, response);
    if (op.kind === 'revoke') { need(result.status === 204); return send(204, ''); }
    const payload = await readPayload(result.body, 4 * 1024 * 1024);
    need(object(payload));
    let projected, status = 200;
    const data = payload.data;
    switch (op.kind) {
      case 'participation': case 'consent': need(object(data)); projected = { data: participation(data) }; break;
      case 'submission': status = result.status === 201 ? 201 : 200; projected = { data: receipt(data, input) }; break;
      case 'access': need(object(data) && typeof data.canResearch === 'boolean' && typeof data.canManageGrants === 'boolean'); projected = { data: { canResearch: data.canResearch, canManageGrants: data.canManageGrants } }; break;
      case 'grants': projected = listBody(payload, grant); break;
      case 'grant': projected = { data: grant(data) }; need(same(projected.data.email, input.email)); break;
      case 'documents': projected = listBody(payload, item => consentDocument(item, true)); break;
      case 'consentDocument': status = result.status === 201 ? 201 : 200; projected = { data: consentDocument(data, true) }; need(projected.data.version === input.version); break;
      case 'instruments': projected = listBody(payload, instrumentSummary); break;
      case 'instrument': case 'import': status = result.status === 201 ? 201 : 200; projected = { data: draft(data) }; break;
      case 'instrumentDetail': need(object(data) && Array.isArray(data.versions) && data.versions.length <= 500); projected = { data: { instrument: instrumentSummary(data.instrument), versions: data.versions.map(versionInfo) } }; break;
      case 'draftRead': case 'draft': projected = { data: draft(data) }; break;
      case 'newVersion': status = result.status === 201 ? 201 : 200; projected = { data: draft(data) }; break;
      case 'publish': need(object(data)); projected = { data: { version: versionInfo(data.version) } }; break;
      case 'organizations': projected = listBody(payload, item => { need(object(item) && uuid(item.id) && string(item.name, 300, 1)); return { id: item.id, name: item.name }; }); break;
      case 'results': {
        const list = listBody(payload, resultRow);
        need(object(payload.version) && uuid(payload.version.id) && integer(payload.version.versionNumber, 1, 1e9));
        unique(list.data, 'organizationId');
        projected = { ...list, version: { id: payload.version.id, versionNumber: payload.version.versionNumber } };
        break;
      }
      case 'needs': projected = listBody(payload, needItem); break;
      case 'need': status = result.status === 201 ? 201 : 200; projected = { data: needItem(data) }; need(same(projected.data.organizationId, input.organizationId)); break;
      default: throw new Error('Operación desconocida');
    }
    return send(status, JSON.stringify(projected));
  } catch { return unavailable(send);
  } finally { if (result && !result.bodyUsed) await result.body?.cancel().catch(() => {}); }
}

/** CSV anonimizado: tipo y encabezados verificados, tamaño acotado antes y durante el streaming. */
async function streamCsv(result, send, response) {
  const type = result.headers.get('content-type') || '';
  const declared = result.headers.get('content-length');
  const disposition = result.headers.get('content-disposition') || '';
  const name = disposition.match(/^attachment; filename="([A-Za-z0-9._-]{1,76}\.csv)"$/)?.[1];
  if (!/^text\/csv(?:\s*;\s*charset=utf-8)?$/i.test(type) || (declared && Number(declared) > maxCsvBytes) || !name || !response) return unavailable(send);
  response.writeHead(200, { 'Content-Type': 'text/csv; charset=utf-8', 'Content-Disposition': `attachment; filename="${name}"`, 'Cache-Control': 'no-store' });
  let size = 0;
  try {
    for await (const chunk of result.body) {
      size += chunk.length;
      if (size > maxCsvBytes) { response.destroy(); return; }
      if (!response.write(chunk)) await new Promise(resolve => response.once('drain', resolve));
    }
    response.end();
  } catch { response.destroy(); }
}
