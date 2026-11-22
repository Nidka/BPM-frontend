// Proxy del agente normativo (RF-46/47). Contrato cerrado: valida entradas y salidas; no registra preguntas.
import { idPattern, object, check, exact, uuid, text, error, timestamp, readPayload, guard, forward } from './proxy-common.mjs';

const route = new RegExp(`^/backend/api/v1/normative/(status|questions|documents)(?:/(${idPattern})(?:/(archive|restore))?)?$`);
const codes = ['NORMATIVE_ASSISTANT_DISABLED', 'NORMATIVE_NO_DOCUMENTS', 'NORMATIVE_RATE_LIMITED', 'NORMATIVE_TOO_LARGE', 'NORMATIVE_UNAVAILABLE', 'NORMATIVE_DOCUMENT_NOT_FOUND'];

function matchRoute(path) {
  const match = path.match(route); if (!match) return;
  const [, resource, id, action] = match;
  if ((resource !== 'documents' && (id || action))) return;
  return { resource, id, action };
}
export const normativeRoute = path => Boolean(matchRoute(path));

function publicDocument(value, withContent) {
  check(object(value) && uuid(value.id) && ['ACTIVE', 'ARCHIVED'].includes(value.status) && Number.isSafeInteger(value.characters) && value.characters >= 0);
  const out = { id: value.id, title: text(value.title, 200, 1), source: text(value.source ?? '', 300), status: value.status, characters: value.characters,
    createdAt: timestamp(value.createdAt), updatedAt: timestamp(value.updatedAt) };
  if (withContent) out.content = text(value.content, 400000, 1); else check(value.content === undefined);
  return out;
}
function publicAnswer(value) {
  check(object(value) && typeof value.supported === 'boolean' && Array.isArray(value.citations) && value.citations.length <= 100);
  return { answer: text(value.answer, 64 * 1024), supported: value.supported,
    citations: value.citations.map(c => { check(object(c) && uuid(c.documentId)); return { documentId: c.documentId, documentTitle: text(c.documentTitle, 200, 1), citedText: text(c.citedText, 20000) }; }) };
}

export async function proxyNormative(request, url, upstream, send, response) {
  const match = matchRoute(url.pathname); if (!match) return send(404, error('NOT_FOUND', 'Recurso no disponible.'));
  const { resource, id, action } = match, method = request.method;
  const allowed = resource === 'status' ? method === 'GET' : resource === 'questions' ? method === 'POST'
    : action ? method === 'POST' : id ? ['GET', 'PUT'].includes(method) : ['GET', 'POST'].includes(method);
  if (!allowed) return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));
  let query = '';
  if (resource === 'documents' && !id && method === 'GET') {
    const status = url.searchParams.getAll('status');
    if ([...url.searchParams.keys()].some(k => k !== 'status') || status.length > 1 || (status[0] && !['ACTIVE', 'ARCHIVED'].includes(status[0]))) return send(400, error('INVALID_REQUEST', 'Revisa los filtros.'));
    if (status[0]) query = '?status=' + status[0];
  } else if (url.search) return send(400, error('INVALID_REQUEST', 'Revisa la solicitud.'));
  const writing = method === 'POST' || method === 'PUT';
  const headers = guard(request, send, writing); if (!headers) return;
  let body;
  if (writing) {
    try {
      const value = await readPayload(request, resource === 'documents' && !action ? 1_700_000 : 8192);
      if (resource === 'questions') { exact(value, ['question']); body = { question: text(value.question, 1000, 3) }; }
      else if (action) { exact(value, []); body = {}; }
      else { exact(value, ['title', 'source', 'content']); body = { title: text(value.title, 200, 1), source: text(value.source ?? '', 300), content: text(value.content, 400000, 1) }; }
    } catch { return send(400, error('INVALID_REQUEST', 'Revisa los datos de la solicitud.')); }
    headers['Content-Type'] = 'application/json'; body = JSON.stringify(body);
  }
  let result;
  try {
    const path = `/api/v1/normative/${resource}${id ? '/' + id : ''}${action ? '/' + action : ''}${query}`;
    result = await forward({ upstream, path, method, headers, body, response, send, codes, fields: ['title', 'source', 'content', 'question'],
      timeoutMs: resource === 'questions' ? 130000 : 15000 });
    if (!result) return;
    check([200, 201].includes(result.status) && /^application\/json(?:\s*;[^\r\n]*)?$/i.test(result.headers.get('content-type') || ''));
    const payload = await readPayload(result.body, 2_500_000); check(object(payload));
    let data;
    if (resource === 'status') { const v = payload.data; check(object(v) && typeof v.enabled === 'boolean' && Number.isSafeInteger(v.documents) && v.documents >= 0 && Number.isSafeInteger(v.questionsPerHour) && v.questionsPerHour > 0); data = { enabled: v.enabled, documents: v.documents, questionsPerHour: v.questionsPerHour }; }
    else if (resource === 'questions') data = publicAnswer(payload.data);
    else if (!id && method === 'GET') { check(Array.isArray(payload.data) && payload.data.length <= 200); data = payload.data.map(d => publicDocument(d, false)); }
    else { data = publicDocument(payload.data, method === 'GET'); if (id) check(data.id === id); }
    return send(result.status, JSON.stringify({ data }));
  } catch { return send(503, error('BACKEND_UNAVAILABLE', 'No pudimos confirmar la operación.')); }
  finally { if (result && !result.bodyUsed) await result.body?.cancel().catch(() => {}); }
}
