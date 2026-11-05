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

const uuidPattern = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
const uuid = value => typeof value === 'string' && new RegExp(`^${uuidPattern}$`, 'i').test(value);
const route = new RegExp(`^/backend/api/v1/organizations/([A-Za-z0-9_-]{1,64})/(?:checklist-runs/(${uuidPattern})/items/(${uuidPattern})/evidence|media/(?:(uploads)|(${uuidPattern})(?:/(confirm|download))?)|incidents/(${uuidPattern})/(?:evidence/(BEFORE)|actions/(${uuidPattern})/evidence/(AFTER)))$`, 'i');
export const mediaRoute = path => route.test(path);
const maxBytes = 25 * 1024 * 1024;
const mimeTypes = ['image/jpeg', 'image/png'];
const statuses = ['PENDING_UPLOAD', 'AVAILABLE', 'FAILED', 'DELETED'];
const object = value => value != null && typeof value === 'object' && !Array.isArray(value);
const text = (value, maximum) => typeof value === 'string' && value.length <= maximum;
const date = value => text(value, 40) && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(value) && Number.isFinite(Date.parse(value));
const name = value => typeof value === 'string' && value.trim() === value && value.length > 0 && Array.from(value).length <= 200 && !/[\/\\\u0000-\u001f\u007f-\u009f\uD800-\uDFFF]/u.test(value);
const size = value => Number.isInteger(value) && value > 0 && value <= maxBytes;
const error = (code, message) => JSON.stringify({ errors: [{ code, message, fields: {} }] });
const unavailable = send => send(503, error('MEDIA_UNAVAILABLE', 'No pudimos confirmar la operación de fotos.'));

function target(value, output = false) {
  if (!object(value)) throw new Error('Destino no válido');
  if (uuid(value.incidentId)) {
    if (!['BEFORE', 'AFTER'].includes(value.stage) || value.stage === 'BEFORE' && value.actionId !== undefined || value.stage === 'AFTER' && !uuid(value.actionId) ||
        (output ? value.runId !== '' || value.itemId !== '' : value.runId !== undefined || value.itemId !== undefined)) throw new Error('Destino no válido');
    return { incidentId: value.incidentId, ...(value.actionId ? { actionId: value.actionId } : {}), stage: value.stage };
  }
  if (![value.runId, value.itemId].every(uuid) || ['incidentId', 'actionId', 'stage'].some(key => value[key] !== undefined)) throw new Error('Destino no válido');
  return { runId: value.runId, itemId: value.itemId };
}

function publicFile(value, { org, id, runId, itemId, incidentId, actionId, stage, input, status } = {}) {
  const destination = target(value, true);
  if (!object(value) || !uuid(value.id) || !name(value.originalName) || !mimeTypes.includes(value.mimeType) || !size(value.sizeBytes) ||
      !statuses.includes(value.status) || !date(value.createdAt) || value.uploadedAt != null && (!date(value.uploadedAt) || Date.parse(value.uploadedAt) < Date.parse(value.createdAt)) ||
      value.status === 'AVAILABLE' && value.uploadedAt == null || id && value.id !== id || runId && value.runId !== runId || itemId && value.itemId !== itemId ||
      incidentId && value.incidentId !== incidentId || incidentId && value.actionId !== actionId || stage && value.stage !== stage ||
      value.organizationId != null && value.organizationId !== org || status && value.status !== status || input &&
      (value.id !== input.id || Object.entries(target(input)).some(([key, expected]) => value[key] !== expected) || value.originalName !== input.originalName || value.mimeType !== input.mimeType || value.sizeBytes !== input.sizeBytes))
    throw new Error('Archivo no válido');
  return { id: value.id, runId: value.runId, itemId: value.itemId, ...(value.incidentId ? destination : {}), originalName: value.originalName, mimeType: value.mimeType,
    sizeBytes: value.sizeBytes, status: value.status, createdAt: value.createdAt, uploadedAt: value.uploadedAt ?? null };
}

function publicListing(value, context) {
  if (!object(value) || !Array.isArray(value.files) || !context?.incidentId && value.files.length > 3 || !size(value.maxBytes) || value.maxFiles !== 3 || typeof value.uploadAvailable !== 'boolean' ||
      !Array.isArray(value.allowedMimeTypes) || value.allowedMimeTypes.length < 1 || value.allowedMimeTypes.length > 2 ||
      value.allowedMimeTypes.some(mime => !mimeTypes.includes(mime)) || new Set(value.allowedMimeTypes).size !== value.allowedMimeTypes.length)
    throw new Error('Política no válida');
  const files = value.files.map(file => publicFile(file, context));
  if (new Set(files.map(file => file.id)).size !== files.length) throw new Error('Archivos duplicados');
  return { files, maxBytes: value.maxBytes, allowedMimeTypes: [...value.allowedMimeTypes], maxFiles: 3, uploadAvailable: value.uploadAvailable };
}

function publicTicket(value, input) {
  if (!object(value) || !text(value.url, 8192) || value.url.trim() !== value.url || /[\u0000-\u001f\u007f]/.test(value.url) || !date(value.expiresAt) || Date.parse(value.expiresAt) <= Date.now() || !object(value.headers))
    throw new Error('Ticket no válido');
  const target = new URL(value.url);
  if (target.protocol !== 'https:' || target.port && target.port !== '443' || target.username || target.password || target.hash ||
      !/^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+r2\.cloudflarestorage\.com$/.test(target.hostname))
    throw new Error('Destino no válido');
  const permitted = input ? ['content-type', 'if-none-match', 'cache-control', 'content-disposition', 'x-amz-meta-sha256', 'host', 'content-length', 'x-amz-checksum-sha256', 'x-amz-content-sha256'] : [];
  const entries = Object.entries(value.headers);
  const normalized = Object.fromEntries(entries.map(([key, values]) => [key.toLowerCase(), values]));
  if (entries.length > 10 || Object.keys(normalized).length !== entries.length || entries.some(([key, values]) => !permitted.includes(key.toLowerCase()) ||
      !Array.isArray(values) || values.length < 1 || values.length > 4 || values.some(v => !text(v, 4096) || /[\u0000-\u001f\u007f-\u009f]/.test(v))))
    throw new Error('Encabezados no válidos');
  if (input) {
    const exact = (key, expected) => normalized[key]?.length === 1 && normalized[key][0] === expected;
    if (!exact('content-type', input.mimeType) || !exact('if-none-match', '*') ||
        normalized['cache-control'] && !exact('cache-control', 'private, no-store') || normalized['content-disposition'] && !exact('content-disposition', 'attachment') ||
        normalized['x-amz-meta-sha256'] && !exact('x-amz-meta-sha256', input.sha256) || normalized.host && !exact('host', target.host) ||
        normalized['content-length'] && !exact('content-length', String(input.sizeBytes))) throw new Error('Firma no válida');
  }
  return { url: value.url, headers: Object.fromEntries(entries.map(([key, values]) => [key, [...values]])), expiresAt: value.expiresAt };
}

function reserveInput(value) {
  const destination = target(value);
  const fields = ['id', ...Object.keys(destination), 'originalName', 'mimeType', 'sizeBytes', 'sha256'];
  if (!object(value) || Object.keys(value).length !== fields.length || Object.keys(value).some(key => !fields.includes(key)) ||
      !uuid(value.id) || !name(value.originalName) || !mimeTypes.includes(value.mimeType) || !size(value.sizeBytes) ||
      typeof value.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(value.sha256)) throw new Error('Reserva no válida');
  return Object.fromEntries(fields.map(key => [key, value[key]]));
}

function originAllowed(request) {
  if (!request.headers.origin) return true;
  try {
    const origin = new URL(request.headers.origin);
    return ['http:', 'https:'].includes(origin.protocol) && !origin.username && !origin.password && origin.host === request.headers.host && origin.pathname === '/' && !origin.search && !origin.hash;
  } catch { return false; }
}

/** Expone destinos explícitos por pregunta o incidencia; nunca sirve de proxy hacia R2. */
export async function proxyMedia(request, url, upstream, send, response) {
  const match = url.pathname.match(route);
  if (!match || url.search) return send(404, error('NOT_FOUND', 'Recurso no disponible.'));
  const [, org, runId, itemId, reserve, id, action, incidentId, before, actionId, after] = match;
  const stage = before || after;
  const method = runId || incidentId ? 'GET' : reserve || action ? 'POST' : 'DELETE';
  if (request.method !== method) return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));
  const authorization = request.headers.authorization;
  if (typeof authorization !== 'string' || authorization.length > 8192 || !/^Bearer [A-Za-z0-9._-]+$/.test(authorization))
    return send(401, error('UNAUTHENTICATED', 'Inicia sesión para continuar.'));
  if (method !== 'GET' && !originAllowed(request)) return send(403, error('ORIGIN_NOT_ALLOWED', 'Origen no permitido.'));
  const headers = { Accept: 'application/json', Authorization: authorization };
  let input, body;
  if (method === 'POST') {
    if (!/^application\/json(?:;|$)/i.test(request.headers['content-type'] || '')) return send(415, error('UNSUPPORTED_MEDIA_TYPE', 'Envía una solicitud JSON.'));
    try {
      const value = await readPayload(request, 512 * 1024);
      if (reserve) input = reserveInput(value);
      else if (!object(value) || Object.keys(value).length !== 0) throw new Error('Campos no permitidos');
      body = JSON.stringify(input ?? {});
      headers['Content-Type'] = 'application/json';
    } catch { return send(400, error('INVALID_REQUEST', 'Revisa los datos de la foto.')); }
  } else if (request.headers['transfer-encoding'] || request.headers['content-length'] != null && request.headers['content-length'] !== '0') {
    return send(400, error('INVALID_REQUEST', 'Esta operación no admite un cuerpo.'));
  }
  response?.setHeader('Cache-Control', 'no-store');
  let result;
  try {
    result = await fetch(new URL(url.pathname.slice('/backend'.length), upstream), {
      method, headers, body, redirect: 'error', cache: 'no-store', credentials: 'omit', signal: AbortSignal.timeout(10000),
    });
    forwardSessionHeaders(result, response);
    if (result.ok) {
      const payload = await readPayload(result.body, 4 * 1024 * 1024);
      if (!object(payload)) throw new Error('Respuesta no válida');
      let data;
      if (runId || incidentId) data = publicListing(payload.data, { org, runId, itemId, incidentId, actionId, stage });
      else if (reserve) {
        if (!object(payload.data)) throw new Error('Reserva no válida');
        const file = publicFile(payload.data.file, { org, input });
        if (!['PENDING_UPLOAD', 'AVAILABLE'].includes(file.status)) throw new Error('Reserva no válida');
        data = { file, upload: payload.data.upload == null ? null : publicTicket(payload.data.upload, input) };
        if (file.status === 'AVAILABLE') data.upload = null;
      } else if (action === 'download') data = publicTicket(payload.data);
      else data = publicFile(payload.data, { org, id, status: action === 'confirm' ? 'AVAILABLE' : 'DELETED' });
      return send(200, JSON.stringify({ data }));
    }
    const failures = {
      400: ['INVALID_REQUEST', 'Revisa los datos de la foto.'], 401: ['UNAUTHENTICATED', 'Inicia sesión nuevamente.'],
      403: ['ACCESS_DENIED', 'No tienes permiso para esta foto.'], 404: ['NOT_FOUND', 'La foto o su destino no está disponible.'],
      409: ['RESOURCE_CONFLICT', 'La operación o la foto cambió. Actualiza antes de continuar.'],
      413: ['MEDIA_TOO_LARGE', 'La foto supera el tamaño permitido.'], 422: ['VALIDATION_ERROR', 'Revisa el formato y los datos de la foto.'],
      429: ['RATE_LIMITED', 'Espera un momento antes de reintentar.'],
    };
    if (result.status === 401 || result.status === 409 || result.status === 422) {
      const payload = await readPayload(result.body, 8192);
      const code = payload?.errors?.[0]?.code;
      if (result.status === 401 && code === 'SESSION_EXPIRED') return send(401, error(code, 'Tu sesión venció. Inicia sesión nuevamente.'));
      if (result.status === 409 && code === 'UPLOAD_NOT_READY') return send(409, error(code, 'El envío todavía no está confirmado. Reintenta para comprobarlo.'));
      if (result.status === 422 && code === 'MEDIA_CONTENT_INVALID') return send(422, error(code, 'La foto no se pudo validar. Elige una imagen válida.'));
    }
    const failure = failures[result.status];
    return failure ? send(result.status, error(...failure)) : unavailable(send);
  } catch { return unavailable(send);
  } finally {
    if (result && !result.bodyUsed) await result.body?.cancel().catch(() => {});
  }
}
