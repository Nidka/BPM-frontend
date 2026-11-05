import { pageQuery, publicPagination } from './pagination.mjs';
import { forwardSessionHeaders } from './session-headers.mjs';

// Decodificar de forma estricta evita aceptar bytes corruptos como texto de reemplazo.
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
const route = new RegExp(`^/backend/api/v1/organizations/([A-Za-z0-9_-]{1,64})/(?:incident-catalog(?:/(${idPattern})/(versions))?|incidents(?:/(${idPattern})(?:/(assignees|actions|history))?)?)$`);
const uuid = value => typeof value === 'string' && new RegExp(`^${idPattern}$`).test(value) && value !== '00000000-0000-0000-0000-000000000000';
const same = (a, b) => typeof a === 'string' && typeof b === 'string' && a.toLowerCase() === b.toLowerCase();
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const require = value => { if (!value) throw new Error('Datos de incidencia no válidos'); };
const string = (value, max, min = 0) => typeof value === 'string' && [...value].length <= max && [...value.trim()].length >= min && !/[\uD800-\uDFFF]/u.test(value);
const text = (value, key, max, min = 0) => { require(string(value[key], max, min)); return value[key]; };
const identifier = (value, key) => { require(uuid(value[key])); return value[key]; };
const actor = (value, key) => text(value, key, 128, 1);
const positive = value => Number.isSafeInteger(value) && value > 0;
const count = value => Number.isSafeInteger(value) && value >= 0;
const exact = (value, fields) => require(object(value) && Object.keys(value).every(key => fields.includes(key)));
const risk = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const statuses = ['OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'VERIFIED'];
const commands = ['ASSIGN', 'START', 'RESOLVE', 'VERIFY', 'RETURN'];
const catalogFields = ['id', 'code', 'name', 'area', 'normativeCriterion', 'riskLevel', 'evidenceRequired'];
const reportFields = ['id', 'locationId', 'catalogId', 'description', 'clientOperationId', 'clientCreatedAt'];
const commandFields = ['operationId', 'rowVersion', 'actionType', 'actionId', 'responsibleUserId', 'description', 'dueAt', 'note'];
const versionFields = ['id', 'expectedVersion', 'name', 'area', 'normativeCriterion', 'riskLevel', 'evidenceRequired'];
const error = (code, message, fields = {}) => JSON.stringify({ errors: [{ code, message, fields }] });
const unavailable = send => send(503, error('BACKEND_UNAVAILABLE', 'No pudimos confirmar la operación.'));
function instant(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/.test(value) || value.startsWith('0000')) return false;
  const day = new Date(value.slice(0, 10) + 'T00:00:00Z');
  return Number.isFinite(day.getTime()) && day.toISOString().slice(0, 10) === value.slice(0, 10) && Number.isFinite(Date.parse(value));
}
// PostgreSQL conserva microsegundos; Date.parse por sí solo perdería esa precisión.
const microseconds = value => BigInt(Math.floor(Date.parse(value) / 1000)) * 1000000n + BigInt((value.match(/\.(\d+)/)?.[1] || '').padEnd(6, '0').slice(0, 6));
const stamp = (value, key) => { require(instant(value[key])); return value[key]; };
function optionalStamp(value, key, output) { if (value[key] != null) output[key] = stamp(value, key); }
function catalog(value) {
  require(object(value) && /^[A-Z0-9_-]{1,40}$/.test(value.code) && risk.includes(value.riskLevel) && typeof value.evidenceRequired === 'boolean' && positive(value.versionNumber));
  return { id: identifier(value, 'id'), code: value.code, name: text(value, 'name', 200, 2), area: text(value, 'area', 100, 2), normativeCriterion: text(value, 'normativeCriterion', 4000, 1), riskLevel: value.riskLevel, evidenceRequired: value.evidenceRequired, versionNumber: value.versionNumber };
}
function summary(value) {
  require(object(value) && risk.includes(value.riskLevel) && statuses.includes(value.status) && positive(value.rowVersion));
  return { id: identifier(value, 'id'), locationId: identifier(value, 'locationId'), catalogId: identifier(value, 'catalogId'), name: text(value, 'name', 200, 2), area: text(value, 'area', 100, 2), riskLevel: value.riskLevel, status: value.status, description: text(value, 'description', 4000, 1), reportedByName: text(value, 'reportedByName', 200, 1), createdAt: stamp(value, 'createdAt'), rowVersion: value.rowVersion };
}
function action(value) {
  require(object(value) && ['PENDING', 'IN_PROGRESS', 'RESOLVED', 'VERIFIED', 'CANCELLED'].includes(value.status));
  const output = { id: identifier(value, 'id'), responsibleUserId: actor(value, 'responsibleUserId'), responsibleName: text(value, 'responsibleName', 200, 1), description: text(value, 'description', 2000, 1), dueAt: stamp(value, 'dueAt'), status: value.status, resolutionNote: text(value, 'resolutionNote', 2000) };
  optionalStamp(value, 'resolvedAt', output); optionalStamp(value, 'verifiedAt', output);
  if (value.verifiedBy != null) output.verifiedBy = actor(value, 'verifiedBy');
  return output;
}
function detail(value) {
  const output = { ...summary(value), reportedBy: actor(value, 'reportedBy'), catalogVersion: value.catalogVersion, normativeCriterion: text(value, 'normativeCriterion', 4000, 1), evidenceRequired: value.evidenceRequired, actions: [], allowedActions: [], beforeCount: value.beforeCount, afterCount: value.afterCount };
  require(positive(value.catalogVersion) && typeof value.evidenceRequired === 'boolean' && Array.isArray(value.actions) && Array.isArray(value.allowedActions) && value.allowedActions.every(item => commands.includes(item)) && new Set(value.allowedActions).size === value.allowedActions.length && count(value.beforeCount) && count(value.afterCount));
  output.actions = value.actions.map(action); require(new Set(output.actions.map(item => item.id.toLowerCase())).size === output.actions.length);
  output.allowedActions = [...value.allowedActions]; optionalStamp(value, 'closedAt', output); return output;
}
function event(value) {
  require(object(value) && (statuses.includes(value.fromStatus) || value.fromStatus === '') && statuses.includes(value.toStatus));
  return { id: identifier(value, 'id'), operationId: identifier(value, 'operationId'), fromStatus: value.fromStatus, toStatus: value.toStatus, actorName: text(value, 'actorName', 200, 1), note: text(value, 'note', 2000), createdAt: stamp(value, 'createdAt') };
}
function assignee(value) {
  require(object(value) && ['CHEF', 'WORKER'].includes(value.role));
  return { id: actor(value, 'id'), displayName: text(value, 'displayName', 200, 1), role: value.role };
}
function inputFor(value, resource, kind) {
  if (resource === 'catalog') {
    exact(value, kind === 'versions' ? versionFields : catalogFields);
    if (kind === 'versions') require(positive(value.expectedVersion));
    catalog({ ...value, code: kind === 'versions' ? 'VERSION' : value.code, versionNumber: 1 });
  } else if (kind === 'actions') {
    require(object(value) && commands.includes(value.actionType));
    const fields = value.actionType === 'ASSIGN' ? commandFields.filter(key => key !== 'note') : ['operationId', 'rowVersion', 'actionType', ...(value.actionType === 'START' ? [] : ['note'])];
    exact(value, fields); identifier(value, 'operationId'); require(positive(value.rowVersion));
    if (value.actionType === 'ASSIGN') { identifier(value, 'actionId'); actor(value, 'responsibleUserId'); text(value, 'description', 2000, 1); stamp(value, 'dueAt'); }
    else if (value.actionType !== 'START') text(value, 'note', 2000, 1);
  } else {
    exact(value, reportFields); ['id', 'locationId', 'catalogId', 'clientOperationId'].forEach(key => identifier(value, key)); text(value, 'description', 4000, 1); require(Date.parse(stamp(value, 'clientCreatedAt')) <= Date.now() + 300000);
  }
  return value;
}
function bind(value, { org, id, locationId, incidentId } = {}) {
  require(object(value));
  if (value.organizationId != null) require(value.organizationId === org);
  if (id) require(same(value.id, id)); if (locationId) require(same(value.locationId, locationId));
  if (incidentId && value.incidentId != null) require(same(value.incidentId, incidentId));
}
function confirm(input, output, resource, kind) {
  if (kind === 'actions') {
    require(output.rowVersion > input.rowVersion);
    const expected = { ASSIGN: ['ASSIGNED', 'PENDING'], START: ['IN_PROGRESS', 'IN_PROGRESS'], RESOLVE: ['RESOLVED', 'RESOLVED'], VERIFY: ['CLOSED', 'VERIFIED'], RETURN: ['IN_PROGRESS', 'IN_PROGRESS'] }[input.actionType];
    require(output.status === expected[0] && output.actions.length === 1 && output.actions[0].status === expected[1]);
    const target = output.actions[0];
    if (input.actionType === 'ASSIGN') require(same(target.id, input.actionId) && target.responsibleUserId === input.responsibleUserId && target.description === input.description.trim() && microseconds(target.dueAt) === microseconds(input.dueAt));
    if (input.actionType === 'RESOLVE') require(target.resolutionNote === input.note.trim());
    return;
  }
  const keys = resource === 'catalog' ? ['id', 'name', 'area', 'normativeCriterion', 'riskLevel', 'evidenceRequired', ...(kind === 'versions' ? [] : ['code'])] : ['id', 'locationId', 'catalogId', 'description'];
  for (const key of keys) require(uuid(input[key]) ? same(input[key], output[key]) : typeof input[key] === 'string' ? input[key].trim() === output[key] : input[key] === output[key]);
  if (resource === 'catalog') require(output.versionNumber === (kind === 'versions' ? input.expectedVersion + 1 : 1));
  else require(output.status === 'OPEN');
}
export const incidentsRoute = path => Boolean(path.match(route));

/** Proyecta solo el contrato público; la API reautoriza cada lectura, comando y replay. */
export async function proxyIncidents(request, url, upstream, send, response) {
  const match = url.pathname.match(route);
  if (!match) return send(404, error('NOT_FOUND', 'Recurso no disponible.'));
  const [, org, catalogId, version, incidentId, child] = match;
  const resource = url.pathname.split('/')[6] === 'incident-catalog' ? 'catalog' : 'incident';
  const kind = version || child, id = catalogId || incidentId, method = request.method, writing = method === 'POST';
  const permitted = kind === 'versions' || kind === 'actions' ? writing : id ? method === 'GET' : method === 'GET' || writing;
  if (!permitted) return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));
  const collection = method === 'GET' && (!id || kind === 'assignees' || kind === 'history');
  let query = '', locationId, status;
  try {
    const filters = new URL(url);
    if (collection && resource === 'incident' && !id) {
      const locations = filters.searchParams.getAll('locationId'); require(locations.length === 1 && uuid(locations[0])); locationId = locations[0]; filters.searchParams.delete('locationId');
      const states = filters.searchParams.getAll('status'); require(states.length <= 1 && (!states.length || statuses.includes(states[0]))); status = states[0]; filters.searchParams.delete('status');
    }
    if (kind === 'history') require(!filters.searchParams.has('query'));
    query = pageQuery(filters, { detail: !collection });
    if (locationId) { const params = new URLSearchParams(query); params.set('locationId', locationId); if (status) params.set('status', status); query = '?' + params; }
    if (!writing) require(!request.headers['transfer-encoding'] && (!request.headers['content-length'] || request.headers['content-length'] === '0'));
  } catch { return send(400, error(collection ? 'INVALID_PAGINATION' : 'INVALID_REQUEST', 'Revisa los filtros y los datos de la solicitud.')); }
  const authorization = request.headers.authorization;
  if (typeof authorization !== 'string' || authorization.length > 8192 || !/^Bearer [A-Za-z0-9._-]+$/.test(authorization)) return send(401, error('UNAUTHENTICATED', 'Inicia sesión para continuar.'));
  const headers = { Accept: 'application/json', Authorization: authorization };
  let input, body, result;
  if (writing) {
    if (request.headers.origin) {
      let origin; try { origin = new URL(request.headers.origin); } catch {}
      if (!origin || !['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password || origin.host !== request.headers.host || origin.origin !== request.headers.origin) return send(403, error('ORIGIN_NOT_ALLOWED', 'Origen no permitido.'));
    }
    if (!/^application\/json(?:\s*;[^\r\n]*)?$/i.test(request.headers['content-type'] || '')) return send(415, error('UNSUPPORTED_MEDIA_TYPE', 'Envía una solicitud JSON.'));
    const key = request.headers['idempotency-key'];
    if (typeof key !== 'string' || !/^[\x21-\x7e]{8,100}$/.test(key)) return send(400, error('INVALID_REQUEST', 'La clave de la operación no es válida.'));
    try { input = inputFor(await readPayload(request, 512 * 1024), resource, kind); body = JSON.stringify(input); }
    catch { return send(400, error('INVALID_REQUEST', 'Revisa los datos de la incidencia.')); }
    headers['Content-Type'] = 'application/json'; headers['Idempotency-Key'] = key;
  }
  try {
    result = await fetch(new URL(url.pathname.slice('/backend'.length) + query, upstream), { method, headers, body, redirect: 'error', cache: 'no-store', credentials: 'omit', signal: AbortSignal.timeout(10000) });
    forwardSessionHeaders(result, response);
    if (!result.ok) {
      const failures = { 400: [collection ? 'INVALID_PAGINATION' : 'INVALID_REQUEST', 'Revisa los datos de la solicitud.'], 401: ['UNAUTHENTICATED', 'Inicia sesión nuevamente.'], 403: ['ACCESS_DENIED', 'No tienes permiso para esta operación.'], 404: ['NOT_FOUND', 'El recurso no está disponible.'], 409: ['RESOURCE_CONFLICT', 'La incidencia cambió. Actualiza antes de continuar.'], 422: ['VALIDATION_ERROR', 'Revisa los campos indicados.'], 429: ['RATE_LIMITED', 'Espera un momento antes de reintentar.'], 500: ['INTERNAL_ERROR', 'No se pudo completar la solicitud.'] };
      if (!failures[result.status]) return unavailable(send);
      const payload = await readPayload(result.body, 65536), fields = {};
      require(object(payload) && Array.isArray(payload.errors) && payload.errors.length > 0 && payload.errors.length <= 100);
      for (const item of payload.errors) {
        require(object(item) && typeof item.code === 'string' && typeof item.message === 'string' && (item.fields === undefined || object(item.fields)));
        if (result.status === 422 && writing) for (const key of Object.keys(item.fields || {})) if ((resource === 'catalog' ? kind === 'versions' ? versionFields : catalogFields : kind === 'actions' ? commandFields : reportFields).includes(key)) fields[key] = 'Revisa este campo.';
      }
      if (result.status === 401 && payload.errors[0].code === 'SESSION_EXPIRED') return send(401, error('SESSION_EXPIRED', 'Tu sesión venció. Inicia sesión nuevamente.'));
      return send(result.status, error(...failures[result.status], fields));
    }
    const payload = await readPayload(result.body, 4 * 1024 * 1024); require(object(payload));
    if (collection) {
      require(Array.isArray(payload.data) && payload.data.length <= Number(url.searchParams.get('pageSize') || 25) && object(payload.pagination));
      const project = kind === 'assignees' ? assignee : kind === 'history' ? event : resource === 'catalog' ? catalog : summary;
      const data = payload.data.map(value => { bind(value, { org, locationId, incidentId }); const item = project(value); if (status) require(item.status === status); return item; });
      require(new Set(data.map(value => value.id.toLowerCase())).size === data.length);
      return send(200, JSON.stringify({ data, pagination: publicPagination(payload.pagination) }));
    }
    bind(payload.data, { org, id: resource === 'catalog' ? input?.id : incidentId || input?.id, locationId: input?.locationId });
    const data = resource === 'catalog' ? catalog(payload.data) : detail(payload.data);
    if (writing) confirm(input, data, resource, kind);
    return send(200, JSON.stringify({ data }));
  } catch { return unavailable(send); }
  finally { if (result && !result.bodyUsed) await result.body?.cancel().catch(() => {}); }
}
