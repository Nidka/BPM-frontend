import { pageQuery, publicPagination } from './pagination.mjs';
import { forwardSessionHeaders } from './session-headers.mjs';

const route = /^\/backend\/api\/v1\/organizations\/([A-Za-z0-9_-]{1,64})\/checklists(?:\/([A-Za-z0-9_-]{1,64}))?$/;
export const checklistCatalogRoute = path => route.test(path);
const error = (code, message) => JSON.stringify({ errors: [{ code, message, fields: {} }] });

function text(value, key, maximum, allowEmpty = false) {
  const result = value?.[key];
  if (typeof result !== 'string' || result.length > maximum || (!allowEmpty && !result.trim()))
    throw new Error('Campo inválido');
  return result;
}

function publicItem(value) {
  const responseType = text(value, 'responseType', 20);
  if (!['COMPLIANCE', 'TEMPERATURE', 'TEXT', 'NUMBER'].includes(responseType) ||
      !Number.isInteger(value.displayOrder) || value.displayOrder < 1 || typeof value.required !== 'boolean' ||
      (value.evidenceRequiredOnFailure !== undefined && typeof value.evidenceRequiredOnFailure !== 'boolean'))
    throw new Error('Pregunta inválida');
  const unit = value.unit === undefined ? '' : text(value, 'unit', 20, true);
  const minimumValue = value.minimumValue ?? null;
  const maximumValue = value.maximumValue ?? null;
  if (['TEMPERATURE', 'NUMBER'].includes(responseType)) {
    if (!Number.isFinite(minimumValue) || !Number.isFinite(maximumValue) || minimumValue > maximumValue)
      throw new Error('Criterio inválido');
  } else if (minimumValue !== null || maximumValue !== null || unit !== '') throw new Error('Criterio incompatible');
  return {
    id: text(value, 'id', 64), sectionName: text(value, 'sectionName', 120), label: text(value, 'label', 300),
    responseType, displayOrder: value.displayOrder, required: value.required, unit, minimumValue, maximumValue,
    evidenceRequiredOnFailure: value.evidenceRequiredOnFailure ?? false,
  };
}

function publicCatalog(data) {
  if (!Array.isArray(data) || data.length > 100) throw new Error('Catálogo inválido');
  const templates = data.map(value => {
    if (!Number.isInteger(value.versionNumber) || value.versionNumber < 1 ||
        !Array.isArray(value.items) || value.items.length < 1 || value.items.length > 100)
      throw new Error('Plantilla inválida');
    const items = value.items.map(publicItem);
    if (new Set(items.map(item => item.id)).size !== items.length ||
        new Set(items.map(item => item.displayOrder)).size !== items.length) throw new Error('Preguntas duplicadas');
    return {
      id: text(value, 'id', 64), name: text(value, 'name', 180), description: text(value, 'description', 500, true),
      area: text(value, 'area', 80), versionId: text(value, 'versionId', 64), versionNumber: value.versionNumber, items,
    };
  });
  if (new Set(templates.map(item => item.id)).size !== templates.length) throw new Error('Plantillas duplicadas');
  return templates;
}

async function readPayload(body) {
  const chunks = [];
  let size = 0;
  for await (const chunk of body) {
    size += chunk.length;
    if (size > 4 * 1024 * 1024) throw new Error('Catálogo demasiado grande');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

/** Solo lectura del catálogo: no amplía permisos ni habilita escrituras futuras. */
export async function proxyChecklistCatalog(request, url, upstream, send, response) {
  if (!checklistCatalogRoute(url.pathname))
    return send(404, error('NOT_FOUND', 'Recurso no disponible.'));
  if (request.method !== 'GET') return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));
  const detail = Boolean(url.pathname.match(route)?.[2]);
  let query;
  try { query = pageQuery(url, { detail }); } catch { return send(404, error('NOT_FOUND', 'Recurso no disponible.')); }
  const authorization = request.headers.authorization;
  if (typeof authorization !== 'string' || authorization.length > 8192 || !/^Bearer [A-Za-z0-9._-]+$/.test(authorization))
    return send(401, error('UNAUTHENTICATED', 'Debes iniciar sesión.'));
  let result;
  try {
    result = await fetch(new URL(url.pathname.slice('/backend'.length) + query, upstream), {
      method: 'GET', headers: { Authorization: authorization, Accept: 'application/json' },
      redirect: 'error', signal: AbortSignal.timeout(10000),
    });
  } catch { return send(503, error('BACKEND_UNAVAILABLE', 'No se pudo conectar con el servicio.')); }
  forwardSessionHeaders(result, response);
  if (!result.ok) {
    // Los errores del proveedor nunca se reflejan en el navegador.
    await result.body?.cancel();
    const failures = {
      400: ['INVALID_PAGINATION', 'La lista cambió. Actualízala para volver a empezar.'],
      401: ['UNAUTHENTICATED', 'Vuelve a iniciar sesión.'],
      403: ['ACCESS_DENIED', 'No tienes permiso para consultar estos controles.'],
      404: ['NOT_FOUND', 'El catálogo no está disponible.'],
      429: ['RATE_LIMITED', 'Espera un momento antes de volver a intentar.'],
    };
    return failures[result.status] ? send(result.status, error(...failures[result.status]))
      : send(503, error('BACKEND_UNAVAILABLE', 'El servicio no está disponible.'));
  }
  try {
    const payload = await readPayload(result.body);
    return send(200, JSON.stringify(detail ? { data: publicCatalog([payload.data])[0] } : { data: publicCatalog(payload.data), pagination: publicPagination(payload.pagination) }));
  } catch { return send(502, error('INVALID_RESPONSE', 'Respuesta no válida del catálogo.')); }
}
