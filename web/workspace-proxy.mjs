// Proxy cerrado: el backend conserva la autoridad sobre tenant, identidad y roles.
import { forwardSessionHeaders } from './session-headers.mjs';
const routes = /^\/backend\/api\/v1\/organizations(?:\/([A-Za-z0-9_-]{1,64})\/(workspace|locations|members))?$/;
export const workspaceRoute = path => routes.test(path);
const orgFields = ['id', 'legalName', 'tradeName', 'businessType', 'taxId', 'contactName', 'contactPhone', 'contactEmail'];
const locationFields = ['id', 'name', 'address', 'district', 'contactPhone'];
const validationFields = new Set([...orgFields, ...locationFields, 'email', 'role', 'locationIds']);
const error = (code, message, fields = {}) => JSON.stringify({ errors: [{ code, message, fields }] });

async function jsonBody(stream, maxBytes) {
  let size = 0;
  const chunks = [];
  for await (const chunk of stream) {
    size += chunk.length;
    if (size > maxBytes) throw new Error('Oversized JSON');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}
function strings(value, keys) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid object');
  return Object.fromEntries(keys.map(key => {
    if (typeof value[key] !== 'string') throw new Error('Invalid field');
    return [key, value[key]];
  }));
}
function list(value, map) {
  if (!Array.isArray(value) || value.length > 100) throw new Error('Invalid list');
  return value.map(map);
}
function publicData(data, action, method) {
  if (action === 'members') return strings(data, ['status']);
  if (action === 'locations') return strings(data, locationFields);
  if (action !== 'workspace') return method === 'GET' ? list(data, x => strings(x, orgFields)) : strings(data, orgFields);
  return {
    organization: strings(data.organization, orgFields), ...strings(data, ['role']),
    locations: list(data.locations, x => strings(x, locationFields)),
    members: list(data.members, x => ({ ...strings(x, ['id', 'name', 'email', 'role']),
      locationIds: list(x.locationIds, id => { if (typeof id !== 'string') throw new Error('Invalid ID'); return id; }) })),
    ...Object.fromEntries(['courses', 'checklists', 'incidents'].map(key => [key,
      list(data[key], x => strings(x, ['id', 'title', 'detail', 'status']))])),
  };
}
export async function proxyWorkspace(request, url, upstream, send, response) {
  const match = url.pathname.match(routes);
  const action = match?.[2];
  const allowed = action === 'workspace' ? ['GET'] : action ? ['POST'] : ['GET', 'POST'];
  if (!match || url.search) return send(404, error('NOT_FOUND', 'Recurso no disponible.'));
  if (!allowed.includes(request.method)) return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));
  const authorization = request.headers.authorization;
  if (typeof authorization !== 'string' || authorization.length > 8192 || !/^Bearer [A-Za-z0-9._-]+$/.test(authorization))
    return send(401, error('UNAUTHENTICATED', 'Debes iniciar sesión.'));
  const headers = { Authorization: authorization, Accept: 'application/json' };
  let body;
  if (request.method === 'POST') {
    if (request.headers.origin) {
      let origin;
      try { origin = new URL(request.headers.origin); } catch {}
      if (!origin || !['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password || origin.host !== request.headers.host)
        return send(403, error('ORIGIN_NOT_ALLOWED', 'Origen no permitido.'));
    }
    if (!/^application\/json(?:;|$)/i.test(request.headers['content-type'] || ''))
      return send(415, error('UNSUPPORTED_MEDIA_TYPE', 'Envía una solicitud JSON.'));
    try {
      const value = await jsonBody(request, 16384);
      const fields = action === 'members' ? ['email', 'role', 'locationIds'] : action === 'locations' ? locationFields : orgFields;
      if (!value || Array.isArray(value) || Object.keys(value).some(key => !fields.includes(key))) throw new Error('Invalid body');
      body = JSON.stringify(value);
    } catch { return send(400, error('INVALID_REQUEST', 'La solicitud no es válida.')); }
    headers['Content-Type'] = 'application/json';
  }
  try {
    const result = await fetch(new URL(url.pathname.slice('/backend'.length), upstream), {
      method: request.method, body, headers, redirect: 'error', signal: AbortSignal.timeout(10000),
    });
    if (response) forwardSessionHeaders(result, response);
    const payload = await jsonBody(result.body, 1048576);
    if (result.ok) return send(200, JSON.stringify({ data: publicData(payload.data, action, request.method) }));
    const codes = {
      400: ['INVALID_REQUEST', 'La solicitud no es válida.'],
      401: ['UNAUTHENTICATED', 'Vuelve a iniciar sesión.'],
      403: ['ACCESS_DENIED', 'No tienes permisos para realizar esta acción.'],
      404: ['MEMBER_NOT_AVAILABLE', 'La persona debe tener una cuenta BPM activa.'],
      409: ['RESOURCE_CONFLICT', 'El registro ya existe. Actualiza antes de volver a intentar.'],
      422: ['VALIDATION_ERROR', 'Revisa los campos indicados.'],
      429: ['RATE_LIMITED', 'Espera un momento antes de volver a intentar.'],
    };
    if (codes[result.status]) {
      const fields = {};
      if (result.status === 422 && Array.isArray(payload.errors)) for (const item of payload.errors) {
        for (const key of Object.keys(item.fields || {})) if (validationFields.has(key)) fields[key] = 'Revisa este campo y vuelve a intentar.';
      }
      return send(result.status, error(...codes[result.status], fields));
    }
    return send(503, error('BACKEND_UNAVAILABLE', 'El servicio no está disponible.'));
  } catch { return send(503, error('BACKEND_UNAVAILABLE', 'No se pudo conectar con el servicio.')); }
}
