import { pageQuery, publicPagination } from './pagination.mjs';
import { forwardSessionHeaders } from './session-headers.mjs';

const route = /^\/backend\/api\/v1\/organizations\/([A-Za-z0-9_-]{1,64})\/checklists(?:\/([A-Za-z0-9_-]{1,64})(?:\/(versions)(?:\/([A-Za-z0-9_-]{1,64}))?)?)?$/;
export const checklistCatalogRoute = path => route.test(path);
const error = (code, message, fields = {}) => JSON.stringify({ errors: [{ code, message, fields }] });

function text(value, key, maximum, allowEmpty = false) {
  const result = value?.[key];
  if (typeof result !== 'string' || result.length > maximum || (!allowEmpty && !result.trim()))
    throw new Error('Campo inválido');
  return result;
}

export function publicItem(value) {
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

export async function readPayload(body, maximum = 4 * 1024 * 1024) {
  const chunks = [];
  let size = 0;
  for await (const chunk of body) {
    size += chunk.length;
    if (size > maximum) throw new Error('Catálogo demasiado grande');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

/** Rutas cerradas de catálogo y publicación; el backend autoriza la operación y el tenant. */
export async function proxyChecklistCatalog(request, url, upstream, send, response) {
  if (!checklistCatalogRoute(url.pathname))
    return send(404, error('NOT_FOUND', 'Recurso no disponible.'));
  const [, , templateId, versions, versionId] = url.pathname.match(route);
  const versionList = Boolean(versions && !versionId);
  const detail = Boolean(templateId && !versionList);
  const writing = request.method === 'POST';
  if (!(request.method === 'GET' || writing && !detail)) return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));

  let query;
  try {
    query = pageQuery(url, { detail: detail || writing });
    if (versionList && url.searchParams.has('query')) throw new Error('Filtro no permitido');
  } catch { return send(404, error('NOT_FOUND', 'Recurso no disponible.')); }
  const authorization = request.headers.authorization;
  if (typeof authorization !== 'string' || authorization.length > 8192 || !/^Bearer [A-Za-z0-9._-]+$/.test(authorization))
    return send(401, error('UNAUTHENTICATED', 'Debes iniciar sesión.'));
  let body;
  if (writing) {
    if (request.headers.origin) {
      let origin; try { origin = new URL(request.headers.origin); } catch {}
      if (!origin || !['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password || origin.host !== request.headers.host)
        return send(403, error('ORIGIN_NOT_ALLOWED', 'Origen no permitido.'));
    }
    if (!/^application\/json(?:;|$)/i.test(request.headers['content-type'] || ''))
      return send(415, error('UNSUPPORTED_MEDIA_TYPE', 'Envía una solicitud JSON.'));
    try { body = JSON.stringify((versionList ? publicVersionDraft : publicDraft)(await readPayload(request, 65536))); if (Buffer.byteLength(body) > 65536) throw new Error('Plantilla demasiado grande'); }
    catch { return send(400, error('INVALID_REQUEST', 'Revisa los datos y el tamaño de la plantilla.')); }
  }
  let result;
  try {
    result = await fetch(new URL(url.pathname.slice('/backend'.length) + query, upstream), {
      method: request.method, body, headers: { Authorization: authorization, Accept: 'application/json', ...(writing ? { 'Content-Type': 'application/json' } : {}) },
      redirect: 'error', signal: AbortSignal.timeout(10000),
    });
  } catch { return send(503, error('BACKEND_UNAVAILABLE', 'No se pudo conectar con el servicio.')); }
  forwardSessionHeaders(result, response);
  if (!result.ok) {
    // Solo nombres de campos conocidos; nunca se reflejan mensajes internos del proveedor.
    let fields = {}, code = '';
    if (writing && versionList && result.status === 409) {
      try { code = (await readPayload(result.body, 65536)).errors?.[0]?.code; } catch {}
    } else if (writing && result.status === 422) {
      try {
        const invalid = await readPayload(result.body, 65536);
        for (const item of invalid.errors || []) for (const key of Object.keys(item.fields || {}))
          if (['id','name','area','description','items','versionId','baseVersionNumber','changeNote'].includes(key)) fields[key] = 'Revisa este campo y vuelve a intentar.';
      } catch {}
    } else await result.body?.cancel();
    if (code === 'VERSION_CONFLICT') return send(409, error(code, 'Otra persona publicó una versión nueva. Recarga la plantilla y vuelve a aplicar tus cambios.'));
    const failures = {
      400: [writing ? 'INVALID_REQUEST' : 'INVALID_PAGINATION', writing ? 'Revisa los datos de la plantilla.' : 'La lista cambió. Actualízala para volver a empezar.'],
      401: ['UNAUTHENTICATED', 'Vuelve a iniciar sesión.'],
      403: ['ACCESS_DENIED', 'No tienes permiso para esta operación en la organización.'],
      404: ['NOT_FOUND', 'La plantilla no está disponible.'],
      409: ['RESOURCE_CONFLICT', 'La plantilla ya existe. Comprueba su publicación antes de reintentar.'],
      422: ['VALIDATION_ERROR', 'Revisa los campos indicados.'],
      429: ['RATE_LIMITED', 'Espera un momento antes de volver a intentar.'],
    };
    return failures[result.status] ? send(result.status, error(...failures[result.status], fields))
      : send(503, error('BACKEND_UNAVAILABLE', 'El servicio no está disponible.'));
  }

  try {
    const payload = await readPayload(result.body);
    if (versionList && !writing) return send(200, JSON.stringify({ data: publicVersionList(payload.data), pagination: publicPagination(payload.pagination) }));
    if (versionId && payload.data?.id !== templateId) throw new Error('Plantilla distinta');
    return send(writing ? 201 : 200, JSON.stringify(detail || writing || versionId ? { data: publicCatalog([payload.data])[0] } : { data: publicCatalog(payload.data), pagination: publicPagination(payload.pagination) }));
  } catch { return send(502, error('INVALID_RESPONSE', 'Respuesta no válida del catálogo.')); }
}

/** Resumen de versión: solo metadatos; las preguntas se piden por separado. */
function publicVersionList(data) {
  if (!Array.isArray(data) || data.length > 100) throw new Error('Versiones inválidas');
  const list = data.map(value => {
    if (!Number.isInteger(value.versionNumber) || value.versionNumber < 1 || !['PUBLISHED', 'ARCHIVED'].includes(value.status) ||
        !Number.isInteger(value.itemCount) || value.itemCount < 1 || value.itemCount > 100 || !Number.isFinite(Date.parse(value.publishedAt)))
      throw new Error('Versión inválida');
    return { id: text(value, 'id', 64), templateId: text(value, 'templateId', 64), versionNumber: value.versionNumber, status: value.status,
      changeNote: text(value, 'changeNote', 500, true), itemCount: value.itemCount, publishedAt: value.publishedAt };
  });
  if (new Set(list.map(item => item.id)).size !== list.length) throw new Error('Versiones duplicadas');
  return list;
}

/** Cuerpo de una versión nueva: base obligatoria, nota opcional y preguntas con UUID propios. */
function publicVersionDraft(value) {
  const allowed = ['versionId', 'baseVersionNumber', 'changeNote', 'items'];
  const itemKeys = ['id', 'sectionName', 'label', 'responseType', 'unit', 'displayOrder', 'required', 'minimumValue', 'maximumValue', 'evidenceRequiredOnFailure'];
  if (!value || Array.isArray(value) || Object.keys(value).some(key => !allowed.includes(key)) || !Array.isArray(value.items) ||
      value.items.length < 1 || value.items.length > 100 || !Number.isInteger(value.baseVersionNumber) || value.baseVersionNumber < 1)
    throw new Error('Versión inválida');
  const changeNote = value.changeNote ?? '';
  if (typeof changeNote !== 'string' || [...changeNote].length > 500) throw new Error('Nota inválida');
  const items = value.items.map(item => {
    if (!item || Array.isArray(item) || Object.keys(item).some(key => !itemKeys.includes(key))) throw new Error('Pregunta inválida');
    return publicItem(item);
  });
  if (new Set(items.map(item => item.id)).size !== items.length || new Set(items.map(item => item.displayOrder)).size !== items.length) throw new Error('Preguntas duplicadas');
  return { versionId: text(value, 'versionId', 64), baseVersionNumber: value.baseVersionNumber, changeNote, items };
}

function publicDraft(value) {
  const allowed = ['id','name','description','area','versionId','items'];
  if (!value || Array.isArray(value) || Object.keys(value).some(key => !allowed.includes(key)) || !Array.isArray(value.items))
    throw new Error('Plantilla inválida');
  for (const item of value.items) {
    if (!item || Array.isArray(item) || Object.keys(item).some(key => !['id','sectionName','label','responseType','unit','displayOrder','required','minimumValue','maximumValue','evidenceRequiredOnFailure'].includes(key)) || item.evidenceRequiredOnFailure === true)
      throw new Error('Pregunta inválida');
  }
  const { versionNumber, ...draft } = publicCatalog([{...value, description:value.description ?? '', versionNumber:1}])[0];
  return draft;
}
