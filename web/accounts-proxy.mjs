import { forwardSessionHeaders } from './session-headers.mjs';

// Proxy cerrado de cuentas: edición de organización/sede/miembro, invitaciones y dispositivos propios.
// Solo reenvía las rutas del contrato (ORGANIZATIONS.md del backend), valida cuerpos de entrada y proyecta
// respuestas públicas. El tenant y el rol siguen siendo autoridad del backend; nunca se registran tokens.

const id = '[A-Za-z0-9_-]{1,64}';
const route = new RegExp(`^/backend/api/v1/(?:organizations/(${id})(?:/(?:locations/(${id})|members/(${id})(?:/(deactivate|reactivate|transfer-admin))?|invitations(?:/(${id})/(resend|revoke))?))?|(invitations/accept)|me/devices(?:/(${id}))?)$`);
export const accountsRoute = path => {
  const match = path.match(route);
  // `check` y `search` pertenecen al proxy de workspace.
  if (!match) return false;
  if (match[3] === 'check' || match[3] === 'search') return false;
  // `/organizations/{org}` solo existe aquí para PATCH; el resto de rutas sin sufijo es de workspace.
  return true;
};

const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const need = value => { if (!value) throw new Error('Datos de cuentas no válidos'); };
const error = (code, message, fields = {}) => JSON.stringify({ errors: [{ code, message, fields }] });
const length = value => [...value].length;
const text = (value, max, min = 0) => typeof value === 'string' && length(value) <= max && length(value.trim()) >= min && !/[\u0000-\u001f\u007f]/u.test(value);
const idText = value => typeof value === 'string' && new RegExp(`^${id}$`).test(value);
const version = value => Number.isSafeInteger(value) && value >= 1;
const roles = ['ADMIN', 'CHEF', 'WORKER'];
const businessTypes = ['RESTAURANT', 'CAFETERIA', 'CATERING', 'OTHER_FOOD_SERVICE'];
const recordStatuses = ['ACTIVE', 'INACTIVE'];
const invitationStatuses = ['PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED'];
const platforms = ['ANDROID', 'IOS', 'WEB'];

function instant(value) {
  need(typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/.test(value) && Number.isFinite(Date.parse(value)));
  return value;
}
const exact = (value, fields, optional = []) => need(object(value) && Object.keys(value).every(key => fields.includes(key) || optional.includes(key)) && fields.every(key => key in value));
const idList = value => { need(Array.isArray(value) && value.length <= 100 && value.every(idText) && new Set(value).size === value.length); return value; };
const cursor = value => { need(typeof value === 'string' && value.length <= 2048 && /^[A-Za-z0-9_-]*$/.test(value)); return value; };

// ---- Entradas ---------------------------------------------------------------------------------

const inputs = {
  organization: v => { exact(v, ['legalName', 'tradeName', 'taxId', 'businessType', 'contactName', 'contactPhone', 'contactEmail', 'rowVersion']);
    need(text(v.legalName, 200, 2) && text(v.tradeName, 200) && text(v.taxId, 20) && businessTypes.includes(v.businessType) && text(v.contactName, 160) && text(v.contactPhone, 30) && text(v.contactEmail, 320) && version(v.rowVersion)); return v; },
  location: v => { exact(v, ['name', 'address', 'district', 'contactPhone', 'timezone', 'status', 'rowVersion']);
    need(text(v.name, 160, 2) && text(v.address, 300, 3) && text(v.district, 120) && text(v.contactPhone, 30) && text(v.timezone, 100, 1) && !/\s/.test(v.timezone) && recordStatuses.includes(v.status) && version(v.rowVersion)); return v; },
  member: v => { exact(v, ['role', 'locationIds', 'rowVersion']); need(roles.includes(v.role) && version(v.rowVersion)); idList(v.locationIds); return v; },
  version: v => { exact(v, ['rowVersion']); need(version(v.rowVersion)); return v; },
  transfer: v => { exact(v, ['rowVersion'], ['actorRole', 'actorLocationIds']);
    need(version(v.rowVersion) && ('actorRole' in v) === ('actorLocationIds' in v)); if ('actorRole' in v) { need(['CHEF', 'WORKER'].includes(v.actorRole)); idList(v.actorLocationIds); need(v.actorLocationIds.length >= 1); } return v; },
  invite: v => { exact(v, ['id', 'email', 'role', 'locationIds']); need(idText(v.id) && text(v.email, 320, 3) && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.email) && roles.includes(v.role)); idList(v.locationIds); return v; },
  accept: v => { exact(v, ['token']); need(typeof v.token === 'string' && /^[A-Za-z0-9_-]{43}$/.test(v.token)); return v; },
};

// ---- Proyecciones de salida -------------------------------------------------------------------

const organization = v => { need(object(v) && idText(v.id) && text(v.legalName, 200, 1) && text(v.tradeName ?? '', 200) && text(v.taxId ?? '', 20) && (businessTypes.includes(v.businessType)) && text(v.contactName ?? '', 160) && text(v.contactPhone ?? '', 30) && text(v.contactEmail ?? '', 320) && version(v.rowVersion));
  return { id: v.id, legalName: v.legalName, tradeName: v.tradeName ?? '', taxId: v.taxId ?? '', businessType: v.businessType, contactName: v.contactName ?? '', contactPhone: v.contactPhone ?? '', contactEmail: v.contactEmail ?? '', rowVersion: v.rowVersion }; };
const location = v => { need(object(v) && idText(v.id) && text(v.name, 160, 1) && text(v.address, 300, 1) && text(v.district ?? '', 120) && text(v.contactPhone ?? '', 30) && text(v.timezone, 100, 1) && recordStatuses.includes(v.status) && version(v.rowVersion));
  return { id: v.id, name: v.name, address: v.address, district: v.district ?? '', contactPhone: v.contactPhone ?? '', timezone: v.timezone, status: v.status, rowVersion: v.rowVersion }; };
const member = v => { need(object(v) && idText(v.id) && text(v.name, 200) && text(v.email, 320) && roles.includes(v.role) && recordStatuses.includes(v.status) && version(v.rowVersion));
  return { id: v.id, name: v.name, email: v.email, role: v.role, locationIds: idList(v.locationIds ?? []), status: v.status, rowVersion: v.rowVersion }; };
// El token de invitación nunca se proyecta, aunque el servidor lo enviara.
const invitation = v => { need(object(v) && idText(v.id) && text(v.email, 320) && roles.includes(v.role) && invitationStatuses.includes(v.status) && Number.isSafeInteger(v.sendCount) && v.sendCount >= 0 && version(v.rowVersion));
  return { id: v.id, email: v.email, role: v.role, locationIds: idList(v.locationIds ?? []), status: v.status, expiresAt: instant(v.expiresAt), lastSentAt: instant(v.lastSentAt), createdAt: instant(v.createdAt), sendCount: v.sendCount, rowVersion: v.rowVersion }; };
const device = v => { need(object(v) && idText(v.id) && idText(v.organizationId) && text(v.organizationName ?? '', 200) && platforms.includes(v.platform) && recordStatuses.includes(v.status));
  return { id: v.id, organizationId: v.organizationId, organizationName: v.organizationName ?? '', platform: v.platform, status: v.status, lastSeenAt: v.lastSeenAt == null ? null : instant(v.lastSeenAt), createdAt: instant(v.createdAt) }; };
const list = (data, map) => { need(Array.isArray(data) && data.length <= 100); return data.map(map); };

/** Operación del contrato para la ruta y el método, o null. */
function operation(match, method) {
  const [, org, locationId, memberId, memberAction, invitationId, invitationAction, accept, deviceId] = match;
  const url = match[0];
  const parts = url.split('/').length;
  if (accept) return method === 'POST' ? { kind: 'accept', body: inputs.accept, out: d => { need(object(d) && idText(d.organizationId) && roles.includes(d.role)); return { organizationId: d.organizationId, role: d.role, locationIds: idList(d.locationIds ?? []) }; } } : null;
  if (url.includes('/me/devices')) {
    if (deviceId) return method === 'DELETE' ? { kind: 'revoke', out: device } : null;
    return method === 'GET' ? { kind: 'devices', list: device, query: ['pageSize', 'pageToken'] } : null;
  }
  if (locationId) return method === 'PATCH' ? { kind: 'location', body: inputs.location, out: location } : null;
  if (memberId) {
    if (!memberAction) return method === 'PATCH' ? { kind: 'member', body: inputs.member, out: member } : null;
    if (method !== 'POST') return null;
    if (memberAction === 'transfer-admin') return { kind: 'transfer', body: inputs.transfer, out: d => { need(object(d)); return { newAdmin: member(d.newAdmin), formerAdmin: d.formerAdmin == null ? null : member(d.formerAdmin) }; } };
    return { kind: memberAction, body: inputs.version, out: member };
  }
  if (parts === 7 && url.endsWith('/invitations')) {
    if (method === 'GET') return { kind: 'invitations', list: invitation, query: ['status', 'query', 'pageSize', 'pageToken'] };
    return method === 'POST' ? { kind: 'invite', body: inputs.invite, out: invitation } : null;
  }
  if (invitationId) return method === 'POST' ? { kind: invitationAction, empty: true, out: invitation } : null;
  if (parts === 6) return method === 'PATCH' ? { kind: 'organization', body: inputs.organization, out: organization } : null;
  return null;
}

function queryFor(url, allowed) {
  const result = new URLSearchParams();
  for (const [key, value] of url.searchParams) {
    need(allowed.includes(key) && !result.has(key));
    if (key === 'pageSize') need(/^[1-9][0-9]{0,2}$/.test(value) && Number(value) <= 100);
    else if (key === 'pageToken') need(value.length <= 2048 && /^[A-Za-z0-9_-]*$/.test(value));
    else if (key === 'query') need([...value].length <= 160 && !/\p{Cc}/u.test(value));
    else if (key === 'status') need(invitationStatuses.includes(value));
    result.set(key, value);
  }
  return result.toString() ? '?' + result.toString() : '';
}

async function readBody(stream, max) {
  const chunks = []; let size = 0;
  for await (const chunk of stream) { size += chunk.length; if (size > max) throw new Error('Cuerpo demasiado grande'); chunks.push(chunk); }
  return Buffer.concat(chunks);
}

function originAllowed(request) {
  if (!request.headers.origin) return true;
  try {
    const origin = new URL(request.headers.origin);
    return ['http:', 'https:'].includes(origin.protocol) && !origin.username && !origin.password && origin.host === request.headers.host && origin.origin === request.headers.origin;
  } catch { return false; }
}

const failures = {
  400: ['INVALID_REQUEST', 'La solicitud no es válida.'], 401: ['UNAUTHENTICATED', 'Vuelve a iniciar sesión.'],
  403: ['ACCESS_DENIED', 'No tienes permisos para realizar esta acción.'], 404: ['RESOURCE_NOT_FOUND', 'El recurso no está disponible.'],
  409: ['RESOURCE_CONFLICT', 'El registro cambió. Actualiza antes de volver a intentar.'], 422: ['VALIDATION_ERROR', 'Revisa los campos indicados.'],
  429: ['RATE_LIMITED', 'Espera un momento antes de volver a intentar.'],
};
// Códigos de dominio que la app distingue; cualquier otro se reduce al genérico del estado.
const domain = {
  403: ['INVITATION_EMAIL_MISMATCH'],
  404: ['INVITATION_NOT_FOUND', 'MEMBER_NOT_AVAILABLE', 'RESOURCE_NOT_FOUND'],
  409: ['VERSION_CONFLICT', 'LAST_ADMIN_REQUIRED', 'MEMBER_ALREADY_EXISTS', 'MEMBER_INACTIVE', 'INVITATION_ALREADY_PENDING', 'INVITATION_EXPIRED',
    'INVITATION_ALREADY_USED', 'INVITATION_REVOKED', 'INVITATION_NOT_PENDING', 'RESOURCE_CONFLICT'],
  429: ['INVITATION_RESEND_TOO_SOON'],
};
const fieldNames = new Set(['legalName', 'tradeName', 'taxId', 'businessType', 'contactName', 'contactPhone', 'contactEmail', 'name', 'address', 'district', 'timezone',
  'status', 'email', 'role', 'locationIds', 'userId', 'actorRole', 'actorLocationIds', 'token']);

export async function proxyAccounts(request, url, upstream, send, response) {
  const match = url.pathname.match(route);
  if (!match) return send(404, error('NOT_FOUND', 'Recurso no disponible.'));
  const op = operation(match, request.method);
  if (!op) return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));
  const writing = request.method !== 'GET';
  let query = '';
  try { query = op.query ? queryFor(url, op.query) : (need(!url.search), ''); }
  catch { return send(400, error('INVALID_REQUEST', 'Revisa los filtros de la solicitud.')); }
  const authorization = request.headers.authorization;
  if (typeof authorization !== 'string' || authorization.length > 8192 || !/^Bearer [A-Za-z0-9._-]+$/.test(authorization)) return send(401, error('UNAUTHENTICATED', 'Inicia sesión para continuar.'));
  const headers = { Accept: 'application/json', Authorization: authorization };
  let body;
  if (writing) {
    if (!originAllowed(request)) return send(403, error('ORIGIN_NOT_ALLOWED', 'Origen no permitido.'));
    try {
      const raw = await readBody(request, 16 * 1024);
      if (op.body) {
        if (!/^application\/json(?:\s*;[^\r\n]*)?$/i.test(request.headers['content-type'] || '')) return send(415, error('UNSUPPORTED_MEDIA_TYPE', 'Envía una solicitud JSON.'));
        body = JSON.stringify(op.body(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(raw))));
        headers['Content-Type'] = 'application/json';
      } else need(raw.length === 0);
    } catch { return send(400, error('INVALID_REQUEST', 'Revisa los datos de la solicitud.')); }
  } else need(!request.headers['transfer-encoding'] && (!request.headers['content-length'] || request.headers['content-length'] === '0'));
  response?.setHeader('Cache-Control', 'no-store');
  try {
    const result = await fetch(new URL(url.pathname.slice('/backend'.length) + query, upstream), { method: request.method, headers, body, redirect: 'error', cache: 'no-store', credentials: 'omit', signal: AbortSignal.timeout(10000) });
    forwardSessionHeaders(result, response);
    const payload = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(await readBody(result.body, 1048576)));
    if (!result.ok) {
      if (!failures[result.status]) return send(503, error('BACKEND_UNAVAILABLE', 'El servicio no está disponible.'));
      const first = Array.isArray(payload?.errors) ? payload.errors[0] : undefined;
      const code = typeof first?.code === 'string' ? first.code : '';
      if (domain[result.status]?.includes(code)) return send(result.status, error(code, failures[result.status][1]));
      const fields = {};
      if (result.status === 422 && Array.isArray(payload?.errors)) for (const item of payload.errors) for (const key of Object.keys(object(item?.fields) ? item.fields : {})) if (fieldNames.has(key)) fields[key] = 'Revisa este campo y vuelve a intentar.';
      return send(result.status, error(...failures[result.status], fields));
    }
    need(object(payload));
    if (op.list) {
      const pagination = payload.pagination;
      return send(200, JSON.stringify({ data: list(payload.data, op.list), pagination: { nextPageToken: cursor(pagination?.nextPageToken ?? '') } }));
    }
    return send(200, JSON.stringify({ data: op.out(payload.data) }));
  } catch { return send(503, error('BACKEND_UNAVAILABLE', 'No pudimos confirmar la operación.')); }
}
