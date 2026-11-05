import { pageQuery, publicPagination } from './pagination.mjs';
import { forwardSessionHeaders } from './session-headers.mjs';

const idPattern = '[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}';
const route = new RegExp(`^/backend/api/v1/organizations/([A-Za-z0-9_-]{1,64})/(alert-rules|alerts|notifications/devices)(?:/(${idPattern}|unread-count)(?:/(view|attend))?)?$`);
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const require = value => { if (!value) throw new Error('Contrato de alertas inválido'); };
const exact = (value, fields) => require(object(value) && Object.keys(value).every(key => fields.includes(key)));
const positive = value => Number.isSafeInteger(value) && value > 0;
const uuid = value => typeof value === 'string' && new RegExp(`^${idPattern}$`).test(value) && value !== '00000000-0000-0000-0000-000000000000';
const identifier = value => { require(uuid(value)); return value.toLowerCase(); };
const text = (value, maximum, minimum = 0) => { require(typeof value === 'string' && [...value].length <= maximum && [...value.trim()].length >= minimum && !/[\uD800-\uDFFF]/u.test(value)); return value; };
const actor = value => { text(value, 128); require([...value].length > 0 && !/\p{Cc}/u.test(value)); };
const types = ['CHECKLIST_MISSING', 'TEMPERATURE_DANGER', 'ORGANIZATION_INACTIVE', 'TRAINING_ASSIGNED'];
const statuses = ['PENDING', 'SENT', 'VIEWED', 'ATTENDED', 'CANCELLED'];
const sources = ['CHECKLIST_ASSIGNMENT', 'RECEIPT_ITEM', 'ORGANIZATION', 'TRAINING_ASSIGNMENT'];
const states = ['ALL', 'UNREAD', 'VIEWED', 'ATTENDED'];
const ruleFields = ['locationId', 'name', 'type', 'configuration', 'active', 'emailEnabled', 'pushEnabled'];
const deviceFields = ['id', 'installationId', 'platform', 'pushToken'];
const error = (code, message, fields = {}) => JSON.stringify({ errors: [{ code, message, fields }] });
const unavailable = send => send(503, error('BACKEND_UNAVAILABLE', 'No pudimos confirmar la operación.'));

// JSON.parse no detecta claves repetidas; recorrer los tokens conserva esa distinción.
function strictJSON(value) {
  let index = 0;
  const space = () => { while (/[\t\n\r ]/.test(value[index] || '\0')) index++; };
  const string = () => {
    const match = /"(?:[^"\\\u0000-\u001f]|\\(?:["\\/bfnrt]|u[\da-fA-F]{4}))*"/y;
    match.lastIndex = index; const found = match.exec(value); require(found); index = match.lastIndex; return JSON.parse(found[0]);
  };
  const token = depth => {
    require(depth <= 64); space(); const start = value[index++];
    if (start === '{') {
      const seen = new Set(); space(); if (value[index] === '}') { index++; return; }
      while (true) { space(); const key = string(); require(!seen.has(key)); seen.add(key); space(); require(value[index++] === ':'); token(depth + 1); space(); const end = value[index++]; if (end === '}') return; require(end === ','); }
    }
    if (start === '[') {
      space(); if (value[index] === ']') { index++; return; }
      while (true) { token(depth + 1); space(); const end = value[index++]; if (end === ']') return; require(end === ','); }
    }
    index--; if (start === '"') { string(); return; }
    const match = /(?:true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/y;
    match.lastIndex = index; require(match.exec(value)); index = match.lastIndex;
  };
  token(0); space(); require(index === value.length); return JSON.parse(value);
}
async function readPayload(body, maximum) {
  require(body); const chunks = []; let size = 0;
  for await (const chunk of body) { const bytes = Buffer.from(chunk); size += bytes.length; require(size <= maximum); chunks.push(bytes); }
  return strictJSON(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)));
}
function timestamp(value) {
  require(typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/.test(value) && !value.startsWith('0000'));
  const day = new Date(value.slice(0, 10) + 'T00:00:00Z');
  require(Number.isFinite(day.getTime()) && day.toISOString().slice(0, 10) === value.slice(0, 10) && Number.isFinite(Date.parse(value))); return value;
}
function configuration(type, value, input = false) {
  require(object(value)); let output;
  if (type === 'CHECKLIST_MISSING') { require(typeof value.afterLocalTime === 'string' && /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(value.afterLocalTime)); output = { afterLocalTime: value.afterLocalTime }; }
  else if (type === 'TEMPERATURE_DANGER') {
    const minimum = value.minimumTemperatureCelsius, maximum = value.maximumTemperatureCelsius;
    const decimal = number => Number.isFinite(number) && Math.abs(number) <= 9999.99 && Math.abs(number * 100 - Math.round(number * 100)) < 1e-7;
    require(decimal(minimum) && decimal(maximum) && minimum < maximum); output = { minimumTemperatureCelsius: minimum, maximumTemperatureCelsius: maximum };
  } else if (type === 'ORGANIZATION_INACTIVE') { require(Number.isInteger(value.inactivityDays) && value.inactivityDays >= 1 && value.inactivityDays <= 365); output = { inactivityDays: value.inactivityDays }; }
  else { require(type === 'TRAINING_ASSIGNED'); output = {}; }
  if (input) exact(value, Object.keys(output)); return output;
}
function publicRule(value) {
  require(object(value) && types.includes(value.type) && positive(value.rowVersion));
  for (const key of ['active', 'emailEnabled', 'pushEnabled']) require(typeof value[key] === 'boolean');
  const locationId = value.locationId == null ? null : identifier(value.locationId);
  require(value.type !== 'ORGANIZATION_INACTIVE' || locationId === null);
  return { id: identifier(value.id), locationId, locationName: text(value.locationName, 160), name: text(value.name, 160, 2), type: value.type, configuration: configuration(value.type, value.configuration), active: value.active, emailEnabled: value.emailEnabled, pushEnabled: value.pushEnabled, rowVersion: value.rowVersion };
}
function publicAlert(value, org) {
  require(object(value) && types.includes(value.type) && statuses.includes(value.status) && sources.includes(value.sourceType) && typeof value.sourceAvailable === 'boolean');
  require(value.sourceType === { CHECKLIST_MISSING: 'CHECKLIST_ASSIGNMENT', TEMPERATURE_DANGER: 'RECEIPT_ITEM', ORGANIZATION_INACTIVE: 'ORGANIZATION', TRAINING_ASSIGNED: 'TRAINING_ASSIGNMENT' }[value.type]);
  const sourceId = value.sourceType === 'ORGANIZATION' ? (require(value.sourceId === org), org) : identifier(value.sourceId);
  const output = { id: identifier(value.id), type: value.type, title: text(value.title, 160, 1), message: text(value.message, 4 * 1024 * 1024), status: value.status, createdAt: timestamp(value.createdAt), locationName: text(value.locationName, 160), sourceType: value.sourceType, sourceId, sourceAvailable: value.sourceAvailable };
  for (const key of ['viewedAt', 'attendedAt']) if (value[key] !== undefined) output[key] = value[key] === null ? null : timestamp(value[key]);
  for (const key of ['locationId', 'sourceParentId']) if (value[key] !== undefined) output[key] = value[key] === null ? null : identifier(value[key]);
  if (value.timezone !== undefined && value.timezone !== null && value.timezone !== '') { output.timezone = text(value.timezone, 100, 1); try { new Intl.DateTimeFormat('es', { timeZone: output.timezone }); } catch { require(false); } }
  if (value.recipientUserId !== undefined) actor(value.recipientUserId);
  require(value.status !== 'VIEWED' || output.viewedAt != null);
  require(value.status !== 'ATTENDED' || output.viewedAt != null && output.attendedAt != null);
  require(output.attendedAt == null || output.viewedAt != null);
  return output;
}
function publicDevice(value, deleting) {
  require(object(value) && value.status === (deleting ? 'INACTIVE' : 'ACTIVE'));
  const output = { id: identifier(value.id) };
  if (!deleting) { require(['ANDROID', 'IOS', 'WEB'].includes(value.platform)); output.platform = value.platform; }
  output.status = value.status;
  if (!deleting && value.lastSeenAt !== undefined && value.lastSeenAt !== null) output.lastSeenAt = timestamp(value.lastSeenAt);
  return output;
}
function ruleInput(value, updating) {
  exact(value, [...ruleFields, updating ? 'rowVersion' : 'id']);
  const projected = publicRule({ ...value, id: updating ? '10000000-0000-4000-8000-000000000001' : value.id, locationName: '', rowVersion: updating ? value.rowVersion : 1 });
  projected.configuration = configuration(value.type, value.configuration, true);
  delete projected.locationName; if (updating) delete projected.id; else delete projected.rowVersion;
  projected.name = projected.name.trim(); require(!/\p{Cc}/u.test(projected.name)); return projected;
}
function bind(value, org, id) {
  require(object(value)); if (value.organizationId !== undefined) require(value.organizationId === org);
  if (id) require(identifier(value.id) === id);
}
function matchRoute(path) {
  const match = path.match(route); if (!match) return;
  const [, org, resource, rawId, action] = match;
  if (rawId === 'unread-count' && (resource !== 'alerts' || action) || action && resource !== 'alerts') return;
  return { org, resource, id: rawId && rawId !== 'unread-count' ? rawId.toLowerCase() : undefined, count: rawId === 'unread-count', action };
}
export const alertsRoute = path => Boolean(matchRoute(path));

/** Frontera explícita: el backend revalida destinatario, permisos y fuente en cada acceso. */
export async function proxyAlerts(request, url, upstream, send, response) {
  const match = matchRoute(url.pathname); if (!match) return send(404, error('NOT_FOUND', 'Recurso no disponible.'));
  const { org, resource, id, count, action } = match, method = request.method;
  if (id && !uuid(id)) return send(400, error('INVALID_REQUEST', 'Identificador no válido.'));
  const rules = resource === 'alert-rules', devices = resource === 'notifications/devices';
  const permitted = rules ? id ? method === 'PUT' : ['GET', 'POST'].includes(method) : devices ? method === (id ? 'DELETE' : 'POST') : action ? method === 'POST' : method === 'GET';
  if (!permitted) return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));
  const writing = method === 'POST' || method === 'PUT', collection = method === 'GET' && !id && !count;
  let query = '', locationId, state = 'ALL';
  try {
    const filters = new URL(url);
    if (collection && rules && filters.searchParams.has('locationId')) { const values = filters.searchParams.getAll('locationId'); require(values.length === 1); locationId = identifier(values[0]); filters.searchParams.delete('locationId'); }
    if (collection && !rules && filters.searchParams.has('state')) { const values = filters.searchParams.getAll('state'); require(values.length === 1 && states.includes(values[0])); state = values[0]; filters.searchParams.delete('state'); }
    query = pageQuery(filters, { detail: !collection });
    const params = new URLSearchParams(query); if (locationId) params.set('locationId', locationId); if (collection && !rules && url.searchParams.has('state')) params.set('state', state); query = params.size ? '?' + params : '';
    if (!writing) require(!request.headers['transfer-encoding'] && (!request.headers['content-length'] || request.headers['content-length'] === '0'));
  } catch { return send(400, error(collection ? 'INVALID_PAGINATION' : 'INVALID_REQUEST', 'Revisa los filtros y los datos de la solicitud.')); }
  const authorization = request.headers.authorization;
  if (typeof authorization !== 'string' || authorization.length > 8192 || !/^Bearer [A-Za-z0-9._-]+$/.test(authorization)) return send(401, error('UNAUTHENTICATED', 'Inicia sesión para continuar.'));
  const headers = { Accept: 'application/json', Authorization: authorization }; let input, body, result;
  if (writing || method === 'DELETE') {
    if (request.headers.origin) {
      let origin; try { origin = new URL(request.headers.origin); } catch {}
      if (!origin || !['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password || origin.host !== request.headers.host || origin.origin !== request.headers.origin) return send(403, error('ORIGIN_NOT_ALLOWED', 'Origen no permitido.'));
    }
  }
  if (writing) {
    if (!/^application\/json(?:\s*;[^\r\n]*)?$/i.test(request.headers['content-type'] || '')) return send(415, error('UNSUPPORTED_MEDIA_TYPE', 'Envía una solicitud JSON.'));
    if (rules) {
      const key = request.headers['idempotency-key']; if (typeof key !== 'string' || !/^[\x21-\x7e]{8,100}$/.test(key)) return send(400, error('INVALID_REQUEST', 'La clave de la operación no es válida.'));
      headers['Idempotency-Key'] = key;
    }
    try {
      const value = await readPayload(request, 512 * 1024);
      if (rules) input = ruleInput(value, Boolean(id));
      else if (devices) { exact(value, deviceFields); require(['ANDROID', 'IOS', 'WEB'].includes(value.platform)); const pushToken = text(value.pushToken, 4096, 1); require(!/\p{Cc}/u.test(pushToken)); input = { id: identifier(value.id), installationId: identifier(value.installationId), platform: value.platform, pushToken }; }
      else { exact(value, []); input = {}; }
      body = JSON.stringify(input);
    } catch { return send(400, error('INVALID_REQUEST', 'Revisa los datos de la solicitud.')); }
    headers['Content-Type'] = 'application/json';
  }
  try {
    const path = `/api/v1/organizations/${org}/${resource}${count ? '/unread-count' : id ? '/' + id : ''}${action ? '/' + action : ''}`;
    result = await fetch(new URL(path + query, upstream), { method, headers, body, redirect: 'error', cache: 'no-store', credentials: 'omit', signal: AbortSignal.timeout(10000) });
    forwardSessionHeaders(result, response);
    if (!result.ok) {
      const failures = { 400: [collection ? 'INVALID_PAGINATION' : 'INVALID_REQUEST', 'Revisa los datos de la solicitud.'], 401: ['UNAUTHENTICATED', 'Inicia sesión nuevamente.'], 403: ['ACCESS_DENIED', 'No tienes permiso para esta operación.'], 404: ['NOT_FOUND', 'El recurso no está disponible.'], 409: ['RESOURCE_CONFLICT', 'El recurso cambió. Actualiza antes de continuar.'], 422: ['VALIDATION_ERROR', 'Revisa los campos indicados.'], 429: ['RATE_LIMITED', 'Espera un momento antes de reintentar.'], 500: ['INTERNAL_ERROR', 'No se pudo completar la solicitud.'] };
      if (!failures[result.status]) return unavailable(send);
      const payload = await readPayload(result.body, 65536), fields = {};
      require(object(payload) && Array.isArray(payload.errors) && payload.errors.length > 0 && payload.errors.length <= 100);
      for (const item of payload.errors) { require(object(item) && typeof item.code === 'string' && typeof item.message === 'string' && (item.fields === undefined || object(item.fields))); if (result.status === 422 && rules) for (const key of Object.keys(item.fields || {})) if (ruleFields.includes(key) || key === 'id' || key === 'rowVersion') fields[key] = 'Revisa este campo.'; }
      if (result.status === 401 && payload.errors[0].code === 'SESSION_EXPIRED') return send(401, error('SESSION_EXPIRED', 'Tu sesión venció. Inicia sesión nuevamente.'));
      return send(result.status, error(...failures[result.status], fields));
    }
    require([200, 201].includes(result.status) && /^application\/json(?:\s*;[^\r\n]*)?$/i.test(result.headers.get('content-type') || ''));
    const payload = await readPayload(result.body, 4 * 1024 * 1024); require(object(payload));
    if (count) { require(Number.isSafeInteger(payload.count) && payload.count >= 0); return send(200, JSON.stringify({ count: payload.count })); }
    if (collection) {
      require(Array.isArray(payload.data) && payload.data.length <= Number(url.searchParams.get('pageSize') || 25) && object(payload.pagination));
      const data = payload.data.map(value => { bind(value, org); const item = rules ? publicRule(value) : publicAlert(value, org); if (locationId) require(item.locationId === locationId); if (state === 'UNREAD') require(['PENDING', 'SENT'].includes(item.status) && item.viewedAt == null); if (['VIEWED', 'ATTENDED'].includes(state)) require(item.status === state); return item; });
      require(new Set(data.map(value => value.id)).size === data.length); return send(200, JSON.stringify({ data, pagination: publicPagination(payload.pagination) }));
    }
    bind(payload.data, org, id || input?.id); const data = rules ? publicRule(payload.data) : devices ? publicDevice(payload.data, method === 'DELETE') : publicAlert(payload.data, org);
    if (writing && rules) { for (const key of ruleFields) require(JSON.stringify(data[key]) === JSON.stringify(input[key])); require(data.rowVersion === (id ? input.rowVersion + 1 : 1)); }
    if (writing && devices) require(data.platform === input.platform);
    if (action) require(data.viewedAt != null && (action === 'attend' ? data.status === 'ATTENDED' && data.attendedAt != null : ['VIEWED', 'ATTENDED'].includes(data.status)));
    return send(rules && method === 'POST' ? 201 : 200, JSON.stringify({ data }));
  } catch { return unavailable(send); }
  finally { if (result && !result.bodyUsed) await result.body?.cancel().catch(() => {}); }
}
