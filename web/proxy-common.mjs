// Piezas comunes de los proxies de contrato cerrado (normativa y webhooks). No registra cuerpos ni tokens.
import { forwardSessionHeaders } from './session-headers.mjs';

export const idPattern = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
export const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
export const check = value => { if (!value) throw new Error('Contrato inválido'); };
export const exact = (value, fields) => check(object(value) && Object.keys(value).every(key => fields.includes(key)));
export const uuid = value => typeof value === 'string' && new RegExp(`^${idPattern}$`).test(value);
export const text = (value, maximum, minimum = 0) => {
  check(typeof value === 'string' && [...value].length <= maximum && [...value.trim()].length >= minimum && !/[\uD800-\uDFFF]/u.test(value));
  return value;
};
export const error = (code, message, fields = {}) => JSON.stringify({ errors: [{ code, message, fields }] });
export const timestamp = value => { check(typeof value === 'string' && value.length <= 40 && Number.isFinite(Date.parse(value))); return value; };

// JSON.parse no detecta claves repetidas; recorrer los tokens conserva esa distinción.
export function strictJSON(value) {
  let index = 0;
  const space = () => { while (/[\t\n\r ]/.test(value[index] || '\0')) index++; };
  const string = () => {
    const match = /"(?:[^"\\\u0000-\u001f]|\\(?:["\\/bfnrt]|u[\da-fA-F]{4}))*"/y;
    match.lastIndex = index; const found = match.exec(value); check(found); index = match.lastIndex; return JSON.parse(found[0]);
  };
  const token = depth => {
    check(depth <= 64); space(); const start = value[index++];
    if (start === '{') {
      const seen = new Set(); space(); if (value[index] === '}') { index++; return; }
      while (true) { space(); const key = string(); check(!seen.has(key)); seen.add(key); space(); check(value[index++] === ':'); token(depth + 1); space(); const end = value[index++]; if (end === '}') return; check(end === ','); }
    }
    if (start === '[') {
      space(); if (value[index] === ']') { index++; return; }
      while (true) { token(depth + 1); space(); const end = value[index++]; if (end === ']') return; check(end === ','); }
    }
    index--; if (start === '"') { string(); return; }
    const match = /(?:true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/y;
    match.lastIndex = index; check(match.exec(value)); index = match.lastIndex;
  };
  token(0); space(); check(index === value.length); return JSON.parse(value);
}

export async function readPayload(body, maximum) {
  check(body); const chunks = []; let size = 0;
  for await (const chunk of body) { const bytes = Buffer.from(chunk); size += bytes.length; check(size <= maximum); chunks.push(bytes); }
  return strictJSON(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)));
}

/** Valida sesión, origen y tipo de contenido; devuelve los encabezados hacia el backend o envía el error. */
export function guard(request, send, writing) {
  const authorization = request.headers.authorization;
  if (typeof authorization !== 'string' || authorization.length > 8192 || !/^Bearer [A-Za-z0-9._-]+$/.test(authorization)) { send(401, error('UNAUTHENTICATED', 'Inicia sesión para continuar.')); return; }
  if (writing || request.method === 'DELETE') {
    if (request.headers.origin) {
      let origin; try { origin = new URL(request.headers.origin); } catch {}
      if (!origin || !['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password || origin.host !== request.headers.host || origin.origin !== request.headers.origin) { send(403, error('ORIGIN_NOT_ALLOWED', 'Origen no permitido.')); return; }
    }
  }
  if (writing && !/^application\/json(?:\s*;[^\r\n]*)?$/i.test(request.headers['content-type'] || '')) { send(415, error('UNSUPPORTED_MEDIA_TYPE', 'Envía una solicitud JSON.')); return; }
  if (!writing && (request.headers['transfer-encoding'] || (request.headers['content-length'] && request.headers['content-length'] !== '0'))) { send(400, error('INVALID_REQUEST', 'Revisa la solicitud.')); return; }
  return { Accept: 'application/json', Authorization: authorization };
}

const failures = { 400: ['INVALID_REQUEST', 'Revisa los datos de la solicitud.'], 401: ['UNAUTHENTICATED', 'Inicia sesión nuevamente.'], 403: ['ACCESS_DENIED', 'No tienes permiso para esta operación.'], 404: ['NOT_FOUND', 'El recurso no está disponible.'], 409: ['RESOURCE_CONFLICT', 'El recurso cambió. Actualiza antes de continuar.'], 422: ['VALIDATION_ERROR', 'Revisa los campos indicados.'], 429: ['RATE_LIMITED', 'Espera un momento antes de reintentar.'], 500: ['INTERNAL_ERROR', 'No se pudo completar la solicitud.'], 503: ['BACKEND_UNAVAILABLE', 'El servicio no está disponible.'] };

/**
 * Llama al backend y traduce los errores a códigos públicos. [codes] son los códigos de dominio que pueden
 * pasar tal cual y [fields] los campos de validación que se conservan. Devuelve la respuesta 2xx o `undefined`
 * si ya envió el error.
 */
export async function forward({ upstream, path, method, headers, body, response, send, timeoutMs = 10000, codes = [], fields = [] }) {
  const result = await fetch(new URL(path, upstream), { method, headers, body, redirect: 'error', cache: 'no-store', credentials: 'omit', signal: AbortSignal.timeout(timeoutMs) });
  forwardSessionHeaders(result, response);
  if (result.ok) return result;
  try {
    const known = failures[result.status];
    if (!known) { send(503, error('BACKEND_UNAVAILABLE', 'No pudimos confirmar la operación.')); return; }
    const payload = await readPayload(result.body, 65536), kept = {};
    check(object(payload) && Array.isArray(payload.errors) && payload.errors.length > 0 && payload.errors.length <= 100);
    const first = payload.errors[0]; check(object(first) && typeof first.code === 'string');
    for (const item of payload.errors) if (object(item?.fields)) for (const key of Object.keys(item.fields)) if (fields.includes(key)) kept[key] = 'Revisa este campo.';
    if (result.status === 401 && first.code === 'SESSION_EXPIRED') { send(401, error('SESSION_EXPIRED', 'Tu sesión venció. Inicia sesión nuevamente.')); return; }
    send(result.status, error(codes.includes(first.code) ? first.code : known[0], known[1], kept));
  } catch { send(503, error('BACKEND_UNAVAILABLE', 'No pudimos confirmar la operación.')); }
  finally { if (!result.bodyUsed) await result.body?.cancel().catch(() => {}); }
}
