import { Readable } from 'node:stream';
import { forwardSessionHeaders } from './session-headers.mjs';

// Proxy de reportes, constancias de cumplimiento y registro de uso (RF-09/16/17/27/28/29/30/39).
// Reenvía solo las rutas del contrato, valida consultas y cuerpos de entrada, reduce errores a
// códigos públicos y entrega el CSV en streaming sin almacenarlo. La verificación pública de
// constancias no envía ni exige credenciales.

const idPattern = '[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}';
const orgPattern = '[A-Za-z0-9_-]{1,64}';
const route = new RegExp(`^/backend/api/v1/organizations/(${orgPattern})/(?:reports/(compliance/export\\.csv|compliance/export\\.pdf|compliance|summary|kpis|settings|training/courses|training/people|certificates)|usage/(events))$`);
const publicRoute = /^\/backend\/api\/v1\/compliance-certificates\/([A-Z2-7]{24})$/;

export const reportsRoute = path => route.test(path) || publicRoute.test(path);

const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const need = value => { if (!value) throw new Error('Datos de reportes no válidos'); };
const uuid = value => typeof value === 'string' && new RegExp(`^${idPattern}$`).test(value) && value !== '00000000-0000-0000-0000-000000000000';
const error = (code, message, fields) => JSON.stringify({ errors: [{ code, message, fields: fields ?? {} }] });
const unavailable = send => send(503, error('BACKEND_UNAVAILABLE', 'No pudimos completar la consulta de reportes.'));

/** Valida AAAA-MM-DD como fecha real de calendario. */
export function isoDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}

async function readPayload(body, maximum) {
  const chunks = []; let size = 0;
  for await (const chunk of body) {
    size += chunk.length;
    if (size > maximum) throw new Error('JSON demasiado grande');
    chunks.push(chunk);
  }
  return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)));
}

function originAllowed(request) {
  if (!request.headers.origin) return true;
  try {
    const origin = new URL(request.headers.origin);
    return ['http:', 'https:'].includes(origin.protocol) && !origin.username && !origin.password && origin.host === request.headers.host && origin.origin === request.headers.origin;
  } catch { return false; }
}

/** Parámetros estrictos: sin repetidos, sin desconocidos y con el formato de cada uno. */
function query(url, allowed, required = []) {
  const entries = [...url.searchParams];
  const names = entries.map(([key]) => key);
  need(new Set(names).size === names.length && names.every(name => Object.hasOwn(allowed, name)));
  required.forEach(name => need(names.includes(name)));
  entries.forEach(([name, value]) => need(allowed[name](value)));
  return entries.length ? '?' + new URLSearchParams(entries) : '';
}

const periodQuery = { from: isoDate, to: isoDate, locationId: uuid };
const pageQuery = {
  pageSize: v => /^\d{1,3}$/.test(v) && Number(v) >= 1 && Number(v) <= 100,
  pageToken: v => v.length > 0 && v.length <= 512 && /^[A-Za-z0-9_=.-]+$/.test(v),
};

function operation(match, method) {
  const [, , resource, usage] = match;
  if (usage) return method === 'POST' ? { kind: 'usage' } : null;
  const table = {
    'compliance': { GET: 'json' }, 'summary': { GET: 'json' }, 'kpis': { GET: 'json' },
    'compliance/export.csv': { GET: 'csv' }, 'compliance/export.pdf': { GET: 'pdf' }, 'settings': { GET: 'json', PUT: 'settings' },
    'training/courses': { GET: 'json' }, 'training/people': { GET: 'json' },
    'certificates': { GET: 'json', POST: 'certificate' },
  };
  const kind = table[resource]?.[method];
  return kind ? { kind, resource } : null;
}

function queryFor(op, url) {
  switch (op.resource) {
    case 'compliance': case 'summary': case 'compliance/export.csv': case 'compliance/export.pdf': return query(url, periodQuery, ['from', 'to']);
    case 'training/people': return query(url, { ...pageQuery, courseId: uuid });
    case 'certificates': return query(url, pageQuery);
    default: need(!url.search); return '';
  }
}

function inputFor(op, payload) {
  const keys = (value, fields) => need(object(value) && Object.keys(value).length === fields.length && fields.every(key => key in value));
  if (op.kind === 'settings') {
    keys(payload, ['weeklyEmailEnabled', 'complianceThreshold']);
    need(typeof payload.weeklyEmailEnabled === 'boolean' && typeof payload.complianceThreshold === 'number' &&
      payload.complianceThreshold >= 1 && payload.complianceThreshold <= 100 && Math.abs(payload.complianceThreshold * 100 - Math.round(payload.complianceThreshold * 100)) < 1e-6);
    return payload;
  }
  if (op.kind === 'certificate') {
    keys(payload, ['from', 'to']);
    need(isoDate(payload.from) && isoDate(payload.to) && payload.from <= payload.to);
    return payload;
  }
  keys(payload, ['events']);
  need(Array.isArray(payload.events) && payload.events.length >= 1 && payload.events.length <= 50);
  const types = ['SESSION_START', 'SESSION_END', 'MODULE_VISIT', 'ALERT_VIEWED', 'ALERT_ATTENDED'];
  const modules = ['APP', 'HOME', 'CHECKLISTS', 'OPERATIONS', 'INCIDENTS', 'ALERTS', 'TRAINING', 'REPORTS', 'SETTINGS'];
  const ids = new Set();
  const events = payload.events.map(event => {
    need(object(event) && Object.keys(event).every(key => ['id', 'sessionId', 'type', 'module', 'occurredAt', 'durationSeconds'].includes(key)));
    need(uuid(event.id) && uuid(event.sessionId) && types.includes(event.type) && !ids.has(event.id.toLowerCase()));
    ids.add(event.id.toLowerCase());
    need(typeof event.occurredAt === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(event.occurredAt) && !Number.isNaN(Date.parse(event.occurredAt)));
    need(event.module === undefined || modules.includes(event.module));
    need(event.durationSeconds === undefined || (Number.isInteger(event.durationSeconds) && event.durationSeconds >= 0 && event.durationSeconds <= 86400));
    return event;
  });
  return { events };
}

const failures = {
  400: ['INVALID_REQUEST', 'Revisa los datos de la solicitud.'], 401: ['UNAUTHENTICATED', 'Inicia sesión nuevamente.'],
  403: ['ACCESS_DENIED', 'No tienes permiso para esta operación.'], 404: ['NOT_FOUND', 'El recurso no está disponible.'],
  409: ['RESOURCE_CONFLICT', 'La operación entra en conflicto con otra ya registrada.'], 422: ['VALIDATION_ERROR', 'Revisa los campos indicados.'],
  429: ['RATE_LIMITED', 'Espera un momento antes de reintentar.'], 500: ['INTERNAL_ERROR', 'No se pudo completar la solicitud.'],
};
const domainCodes = {
  400: { INVALID_PAGINATION: 'La paginación no es válida.' },
  404: { REPORT_LOCATION_NOT_FOUND: 'La sede no está disponible.', TRAINING_COURSE_NOT_FOUND: 'El curso no está disponible.', CERTIFICATE_NOT_FOUND: 'No encontramos esa constancia.' },
  409: { IDEMPOTENCY_CONFLICT: 'Este evento ya se registró con otros datos.', USAGE_SESSION_CONFLICT: 'La sesión de uso no es válida.' },
  422: { REPORT_NO_DATA: 'No hay controles finalizados en ese período.', REPORT_THRESHOLD_NOT_MET: 'El cumplimiento no alcanza el umbral.', EXPORT_TOO_LARGE: 'El período tiene demasiados registros.' },
};

/** Convierte el error del backend en un código público; `fields` se limita a textos cortos. */
async function mapFailure(result, send) {
  if (!failures[result.status]) return unavailable(send);
  let payload;
  try { payload = await readPayload(result.body, 65536); } catch { return send(result.status, error(...failures[result.status])); }
  const first = payload?.errors?.[0];
  const code = typeof first?.code === 'string' ? first.code : '';
  if (result.status === 401 && code === 'SESSION_EXPIRED') return send(401, error('SESSION_EXPIRED', 'Tu sesión venció. Inicia sesión nuevamente.'));
  const domain = domainCodes[result.status]?.[code];
  if (domain) {
    let fields = {};
    if (code === 'REPORT_THRESHOLD_NOT_MET' && object(first.fields)) {
      for (const key of ['averageCompliance', 'threshold']) {
        const value = first.fields[key];
        if (typeof value === 'string' && /^\d{1,3}\.\d{2}$/.test(value)) fields[key] = value;
      }
    }
    return send(result.status, error(code, domain, fields));
  }
  let fields = {};
  if (result.status === 422 && object(first?.fields)) {
    for (const [key, value] of Object.entries(first.fields).slice(0, 20)) {
      if (/^[A-Za-z0-9_.\[\]]{1,64}$/.test(key) && typeof value === 'string') fields[key] = value.slice(0, 200);
    }
  }
  return send(result.status, error(...failures[result.status], fields));
}

/** Proyecta la verificación pública: solo campos publicables y tipos exactos. */
function publicCertificate(data) {
  need(object(data) && data.valid === true && typeof data.organizationName === 'string' && data.organizationName.length <= 200 &&
    isoDate(data.periodStart) && isoDate(data.periodEnd) && typeof data.averageCompliance === 'number' && typeof data.threshold === 'number' &&
    typeof data.issuedAt === 'string' && /^[A-Z2-7]{24}$/.test(data.verificationCode));
  return { valid: true, organizationName: data.organizationName, periodStart: data.periodStart, periodEnd: data.periodEnd,
    averageCompliance: data.averageCompliance, threshold: data.threshold, issuedAt: data.issuedAt, verificationCode: data.verificationCode };
}

export async function proxyReports(request, url, upstream, send, response) {
  const publicMatch = url.pathname.match(publicRoute);
  const match = publicMatch ? null : url.pathname.match(route);
  if (!publicMatch && !match) return send(404, error('NOT_FOUND', 'Recurso no disponible.'));
  const op = publicMatch ? (request.method === 'GET' ? { kind: 'public' } : null) : operation(match, request.method);
  if (!op) return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));
  const writing = request.method !== 'GET';
  let search = '';
  try {
    if (publicMatch) need(!url.search); else search = queryFor(op, url);
    if (!writing) need(!request.headers['transfer-encoding'] && (!request.headers['content-length'] || request.headers['content-length'] === '0'));
  } catch { return send(400, error('INVALID_REQUEST', 'Revisa los filtros de la solicitud.')); }
  const headers = { Accept: op.kind === 'csv' ? 'text/csv' : op.kind === 'pdf' ? 'application/pdf' : 'application/json' };
  if (!publicMatch) {
    const authorization = request.headers.authorization;
    if (typeof authorization !== 'string' || authorization.length > 8192 || !/^Bearer [A-Za-z0-9._-]+$/.test(authorization)) return send(401, error('UNAUTHENTICATED', 'Inicia sesión para continuar.'));
    headers.Authorization = authorization;
  }
  let body;
  if (writing) {
    if (!originAllowed(request)) return send(403, error('ORIGIN_NOT_ALLOWED', 'Origen no permitido.'));
    if (!/^application\/json(?:\s*;[^\r\n]*)?$/i.test(request.headers['content-type'] || '')) return send(415, error('UNSUPPORTED_MEDIA_TYPE', 'Envía una solicitud JSON.'));
    try {
      body = JSON.stringify(inputFor(op, await readPayload(request, op.kind === 'usage' ? 32 * 1024 : 1024)));
      headers['Content-Type'] = 'application/json';
    } catch { return send(400, error('INVALID_REQUEST', 'Revisa los datos de la solicitud.')); }
  }
  response?.setHeader('Cache-Control', 'no-store');
  try {
    const target = new URL(url.pathname.slice('/backend'.length) + search, upstream);
    const result = await fetch(target, { method: request.method, headers, body, redirect: 'error', cache: 'no-store', credentials: 'omit', signal: AbortSignal.timeout(op.kind === 'csv' || op.kind === 'pdf' ? 120000 : 10000) });
    if (!publicMatch) forwardSessionHeaders(result, response);
    if (!result.ok) return await mapFailure(result, send);
    if (op.kind === 'csv') {
      const type = result.headers.get('Content-Type') || '';
      if (!/^text\/csv\b/i.test(type) || !result.body) return unavailable(send);
      const from = url.searchParams.get('from'), to = url.searchParams.get('to');
      // El nombre se reconstruye con fechas ya validadas; no se reenvía el encabezado del backend.
      response.writeHead(200, {
        'Content-Type': 'text/csv; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
        'Content-Disposition': `attachment; filename="checklists_${from}_${to}.csv"`,
      });
      const stream = Readable.fromWeb(result.body);
      stream.on('error', () => response.destroy());
      response.on('close', () => stream.destroy());
      stream.pipe(response);
      return;
    }
    if (op.kind === 'pdf') {
      const type = result.headers.get('Content-Type') || '';
      if (!/^application\/pdf\b/i.test(type) || !result.body) return unavailable(send);
      const from = url.searchParams.get('from'), to = url.searchParams.get('to');
      const headersOut = {
        'Content-Type': 'application/pdf', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
        'Content-Disposition': `attachment; filename="checklists_${from}_${to}.pdf"`,
      };
      const length = result.headers.get('Content-Length');
      if (length && /^\d{1,9}$/.test(length)) headersOut['Content-Length'] = length;
      response.writeHead(200, headersOut);
      const stream = Readable.fromWeb(result.body);
      stream.on('error', () => response.destroy());
      response.on('close', () => stream.destroy());
      stream.pipe(response);
      return;
    }
    const payload = await readPayload(result.body, 2 * 1024 * 1024);
    need(object(payload) && payload.data !== undefined && payload.data !== null);
    let projected;
    if (op.kind === 'public') projected = { data: publicCertificate(payload.data) };
    else if (op.kind === 'usage') {
      need(object(payload.data) && Number.isInteger(payload.data.accepted) && Number.isInteger(payload.data.duplicates));
      projected = { data: { accepted: payload.data.accepted, duplicates: payload.data.duplicates } };
    } else {
      need(object(payload.data) || Array.isArray(payload.data));
      projected = { data: payload.data };
      if (payload.pagination !== undefined) {
        need(object(payload.pagination) && typeof (payload.pagination.nextPageToken ?? '') === 'string');
        projected.pagination = { nextPageToken: payload.pagination.nextPageToken ?? '' };
      }
    }
    return send(op.kind === 'certificate' && result.status === 201 ? 201 : 200, JSON.stringify(projected));
  } catch {
    if (response?.headersSent) { response.destroy(); return; }
    return unavailable(send);
  }
}
