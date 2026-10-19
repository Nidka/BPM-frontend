import { publicItem, readPayload } from './checklists-proxy.mjs';
import { forwardSessionHeaders } from './session-headers.mjs';

const route = /^\/backend\/api\/v1\/organizations\/([A-Za-z0-9_-]{1,64})\/checklist-runs(?:\/([A-Za-z0-9_-]{1,64})(?:\/(answers|submit))?)?$/;
export const checklistRunRoute = path => route.test(path);
const error = (code, message) => JSON.stringify({ errors: [{ code, message, fields: {} }] });
const text = (v, max = 2000) => typeof v === 'string' && v.length <= max;
const identifier = v => typeof v === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(v);

function answer(value) {
  if (!value || !identifier(value.itemId) || !text(value.complianceResult ?? '', 20) || !text(value.textValue ?? '') || !text(value.observation ?? '') ||
      value.numericValue != null && !Number.isFinite(value.numericValue)) throw new Error('Respuesta inválida');
  return { itemId: value.itemId, complianceResult: value.complianceResult ?? '', textValue: value.textValue ?? '',
    observation: value.observation ?? '', numericValue: value.numericValue ?? null };
}
function run(value, org, id) {
  if (!value || !identifier(value.id) || value.organizationId !== org || id && value.id !== id || !identifier(value.locationId) || !identifier(value.templateVersionId) ||
      !text(value.performedBy, 128) || !value.performedBy || !['DRAFT', 'SUBMITTED'].includes(value.status) || !Number.isInteger(value.rowVersion) || value.rowVersion < 1 ||
      !Array.isArray(value.items) || value.items.length < 1 || value.items.length > 100 || !Array.isArray(value.answers) || value.answers.length > 100 ||
      value.compliancePercent != null && (!Number.isFinite(value.compliancePercent) || value.compliancePercent < 0 || value.compliancePercent > 100)) throw new Error('Control inválido');
  return { id: value.id, organizationId: org, locationId: value.locationId, templateVersionId: value.templateVersionId, performedBy: value.performedBy,
    status: value.status, rowVersion: value.rowVersion, items: value.items.map(publicItem), answers: value.answers.map(answer), compliancePercent: value.compliancePercent ?? null };
}

/** Proxy explícito de ejecuciones: no acepta actores, permisos ni destinos aportados por el cliente. */
export async function proxyChecklistRun(request, url, upstream, send, response) {
  const match = url.pathname.match(route);
  if (!match || url.search) return send(404, error('NOT_FOUND', 'Recurso no disponible.'));
  const [, org, id, action] = match;
  const method = action === 'answers' ? 'PUT' : action === 'submit' || !id ? 'POST' : 'GET';
  if (request.method !== method) return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));
  const authorization = request.headers.authorization;
  if (typeof authorization !== 'string' || authorization.length > 8192 || !/^Bearer [A-Za-z0-9._-]+$/.test(authorization)) return send(401, error('UNAUTHENTICATED', 'Inicia sesión para continuar.'));
  const headers = { Accept: 'application/json', Authorization: authorization };
  let body;
  if (method !== 'GET') {
    if (request.headers.origin) {
      let origin; try { origin = new URL(request.headers.origin); } catch {}
      if (!origin || !['https:', 'http:'].includes(origin.protocol) || origin.username || origin.password || origin.host !== request.headers.host)
        return send(403, error('ORIGIN_NOT_ALLOWED', 'Origen no permitido.'));
    }
    if (!/^application\/json(?:;|$)/i.test(request.headers['content-type'] || '')) return send(415, error('UNSUPPORTED_MEDIA_TYPE', 'Envía una solicitud JSON.'));
    if (action) {
      const key = request.headers['idempotency-key'];
      if (typeof key !== 'string' || !/^[\x21-\x7E]{8,100}$/.test(key)) return send(400, error('INVALID_REQUEST', 'La operación no tiene una clave válida.'));
      headers['Idempotency-Key'] = key;
    }
    try {
      const value = await readPayload(request, 512 * 1024);
      const fields = !id ? ['id', 'locationId', 'templateVersionId', 'clientOperationId', 'clientCreatedAt'] : action === 'answers' ? ['rowVersion', 'answers'] : ['rowVersion'];
      if (!value || Array.isArray(value) || Object.keys(value).some(k => !fields.includes(k))) throw new Error('Campos no permitidos');
      if (!id) {
        if (!['id', 'locationId', 'templateVersionId', 'clientOperationId'].every(k => identifier(value[k])) || !text(value.clientCreatedAt, 40) || !Number.isFinite(Date.parse(value.clientCreatedAt))) throw new Error('Alta inválida');
      } else if (!Number.isInteger(value.rowVersion) || value.rowVersion < 1) throw new Error('Versión inválida');
      if (action === 'answers') {
        if (!Array.isArray(value.answers) || value.answers.length > 100) throw new Error('Respuestas inválidas');
        for (const item of value.answers) if (Object.keys(item).some(k => !['itemId', 'complianceResult', 'textValue', 'numericValue', 'observation'].includes(k))) throw new Error('Campos no permitidos');
        value.answers = value.answers.map(answer);
      }
      body = JSON.stringify(value); headers['Content-Type'] = 'application/json';
    } catch { return send(400, error('INVALID_REQUEST', 'Revisa los datos del control.')); }
  }
  try {
    const result = await fetch(new URL(url.pathname.slice('/backend'.length), upstream), { method, headers, body, redirect: 'error', signal: AbortSignal.timeout(10000) });
    forwardSessionHeaders(result, response);
    if (result.ok) {
      const payload = await readPayload(result.body);
      return send(200, JSON.stringify({ data: run(payload.data, org, id || JSON.parse(body).id) }));
    }
    const failures = { 400: ['INVALID_REQUEST', 'Revisa los datos.'], 401: ['UNAUTHENTICATED', 'Inicia sesión nuevamente.'], 403: ['ACCESS_DENIED', 'No tienes permiso para este control.'],
      404: ['NOT_FOUND', 'El control no está disponible.'], 409: ['VERSION_CONFLICT', 'El control cambió. Revisa la versión guardada antes de continuar.'],
      422: ['VALIDATION_ERROR', 'Revisa las respuestas y los campos obligatorios.'], 429: ['RATE_LIMITED', 'Espera un momento antes de reintentar.'] };
    const failure = failures[result.status];
    return failure ? send(result.status, error(...failure)) : send(503, error('BACKEND_UNAVAILABLE', 'No pudimos confirmar la operación.'));
  } catch { return send(503, error('BACKEND_UNAVAILABLE', 'No pudimos confirmar la operación.')); }
}
