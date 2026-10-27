import { readPayload } from './checklists-proxy.mjs';
import { pageQuery, publicPagination } from './pagination.mjs';
import { forwardSessionHeaders } from './session-headers.mjs';

const route = /^\/backend\/api\/v1\/organizations\/([A-Za-z0-9_-]{1,64})\/checklist-assignments(?:\/([A-Za-z0-9_-]{1,64})(?:\/(deactivate))?)?$/;
export const checklistAssignmentRoute = path => route.test(path);
const identifier = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(value);
const text = (value, max) => typeof value === 'string' && value.length <= max;
const date = value => text(value, 40) && Number.isFinite(Date.parse(value));
const time = value => typeof value === 'string' && /^(?:|(?:[01][0-9]|2[0-3]):[0-5][0-9])$/.test(value);
const frequencies = ['ONCE', 'DAILY', 'WEEKLY', 'MONTHLY'];
const error = (code, message) => JSON.stringify({ errors: [{ code, message, fields: {} }] });

function assignedRun(value) {
  if (!value || !identifier(value.id) || !text(value.performedBy, 128) || !value.performedBy ||
      !text(value.performedByName, 200) || !['DRAFT', 'SUBMITTED'].includes(value.status) || !date(value.createdAt) ||
      value.submittedAt != null && !date(value.submittedAt) || value.status === 'SUBMITTED' && value.submittedAt == null)
    throw new Error('Revisión de programación inválida');
  return { id: value.id, performedBy: value.performedBy, performedByName: value.performedByName,
    status: value.status, createdAt: value.createdAt, submittedAt: value.submittedAt ?? null };
}
function assignment(value, { id, period, active } = {}) {
  if (!value || ![value.id, value.templateId, value.templateVersionId, value.locationId].every(identifier) ||
      id && id !== value.id || active != null && value.active !== active || typeof value.active !== 'boolean' ||
      !text(value.templateName, 180) || !value.templateName.trim() || !text(value.locationName, 160) || !value.locationName.trim() ||
      !text(value.timezone, 100) || !value.timezone || !frequencies.includes(value.frequency) || !time(value.dueTime) || !date(value.createdAt) ||
      period && !date(value.periodStartsAt) || value.periodStartsAt != null && !date(value.periodStartsAt) ||
      value.periodEndsAt != null && (!date(value.periodEndsAt) || !date(value.periodStartsAt) || Date.parse(value.periodEndsAt) <= Date.parse(value.periodStartsAt)))
    throw new Error('Programación inválida');
  return { id: value.id, templateId: value.templateId, templateVersionId: value.templateVersionId, templateName: value.templateName,
    locationId: value.locationId, locationName: value.locationName, timezone: value.timezone, frequency: value.frequency,
    dueTime: value.dueTime, active: value.active, createdAt: value.createdAt, periodStartsAt: value.periodStartsAt ?? null,
    periodEndsAt: value.periodEndsAt ?? null, run: value.run == null ? null : assignedRun(value.run) };
}

/** Lista cerrada de operaciones; los permisos y períodos se resuelven únicamente en el backend. */
export async function proxyChecklistAssignment(request, url, upstream, send, response) {
  const match = url.pathname.match(route);
  if (!match) return send(404, error('NOT_FOUND', 'Programación no disponible.'));
  const [, , id, action] = match;
  const collection = !id && request.method === 'GET';
  let query;
  try { query = pageQuery(url, { detail: !collection }); }
  catch { return send(400, error('INVALID_PAGINATION', 'Revisa los filtros de la lista.')); }
  const method = action || !id && !collection ? 'POST' : 'GET';
  if (request.method !== method) return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));
  const authorization = request.headers.authorization;
  if (typeof authorization !== 'string' || authorization.length > 8192 || !/^Bearer [A-Za-z0-9._-]+$/.test(authorization))
    return send(401, error('UNAUTHENTICATED', 'Inicia sesión para continuar.'));
  const headers = { Accept: 'application/json', Authorization: authorization };
  let input, body;
  if (method === 'POST') {
    if (request.headers.origin) {
      let origin; try { origin = new URL(request.headers.origin); } catch {}
      if (!origin || !['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password || origin.host !== request.headers.host)
        return send(403, error('ORIGIN_NOT_ALLOWED', 'Origen no permitido.'));
    }
    if (!/^application\/json(?:;|$)/i.test(request.headers['content-type'] || ''))
      return send(415, error('UNSUPPORTED_MEDIA_TYPE', 'Envía una solicitud JSON.'));
    try {
      input = await readPayload(request, 4096);
      const fields = action ? [] : ['id', 'templateId', 'locationId', 'frequency', 'dueTime'];
      if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).some(key => !fields.includes(key)))
        throw new Error('Campos no permitidos');
      if (!action && (!['id', 'templateId', 'locationId'].every(key => identifier(input[key])) || !frequencies.includes(input.frequency) || !time(input.dueTime ?? '')))
        throw new Error('Programación inválida');
      body = JSON.stringify(input); headers['Content-Type'] = 'application/json';
    } catch { return send(400, error('INVALID_REQUEST', 'Revisa los datos de la programación.')); }
  }
  try {
    const result = await fetch(new URL(url.pathname.slice('/backend'.length) + query, upstream), {
      method, headers, body, redirect: 'error', signal: AbortSignal.timeout(10000),
    });
    forwardSessionHeaders(result, response);
    if (result.ok) {
      const payload = await readPayload(result.body);
      if (collection) {
        if (!Array.isArray(payload.data) || payload.data.length > 100 || !payload.pagination) throw new Error('Lista inválida');
        const data = payload.data.map(value => assignment(value, { period: true, active: true }));
        if (new Set(data.map(value => value.id)).size !== data.length) throw new Error('Programaciones duplicadas');
        return send(200, JSON.stringify({ data, pagination: publicPagination(payload.pagination) }));
      }
      const value = assignment(payload.data, { id: id || input.id, period: method === 'GET', active: action ? false : undefined });
      if (input && !action && (value.templateId !== input.templateId || value.locationId !== input.locationId ||
          value.frequency !== input.frequency || value.dueTime !== (input.dueTime ?? '') || !value.active)) throw new Error('Confirmación distinta');
      return send(200, JSON.stringify({ data: value }));
    }
    const failures = {
      400: ['INVALID_PAGINATION', 'Revisa los filtros de la lista.'], 401: ['UNAUTHENTICATED', 'Inicia sesión nuevamente.'],
      403: ['ACCESS_DENIED', 'No tienes permiso para esta programación.'], 404: ['NOT_FOUND', 'La programación no está disponible.'],
      409: ['RESOURCE_CONFLICT', 'Ya existe una programación para esa plantilla y sede, o cambió su estado.'],
      422: ['VALIDATION_ERROR', 'Revisa la sede, la frecuencia y la hora.'], 429: ['RATE_LIMITED', 'Espera un momento antes de reintentar.'],
    };
    const failure = failures[result.status];
    return failure ? send(result.status, error(...failure)) : send(503, error('BACKEND_UNAVAILABLE', 'No pudimos confirmar la programación.'));
  } catch { return send(503, error('BACKEND_UNAVAILABLE', 'No pudimos confirmar la programación.')); }
}
