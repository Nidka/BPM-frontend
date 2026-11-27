// Proxy de «Avisar a mi jefe» (mensaje rápido y WhatsApp del jefe) y de la notificación de prueba. Contrato cerrado.
import { object, check, exact, uuid, text, error, timestamp, readPayload, guard, forward } from './proxy-common.mjs';

const route = /^\/backend\/api\/v1\/organizations\/([A-Za-z0-9_-]{1,64})\/(staff-messages|staff-messages\/contact|notifications\/test)$/;
export const teamRoute = path => route.test(path);
const kinds = ['SUPPLY', 'EQUIPMENT', 'HELP', 'OTHER'];
const digits = value => value === null || value === undefined ? null : (check(typeof value === 'string' && /^[0-9]{8,15}$/.test(value)), value);

export async function proxyTeam(request, url, upstream, send, response) {
  const match = url.pathname.match(route); if (!match) return send(404, error('NOT_FOUND', 'Recurso no disponible.'));
  const [, org, resource] = match, method = request.method;
  const allowed = resource === 'staff-messages/contact' ? method === 'GET' : method === 'POST';
  if (!allowed) return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));
  let query = '';
  if (resource === 'staff-messages/contact') {
    const ids = url.searchParams.getAll('locationId');
    if ([...url.searchParams.keys()].some(k => k !== 'locationId') || ids.length !== 1 || !uuid(ids[0])) return send(400, error('INVALID_REQUEST', 'Indica la sede.'));
    query = '?locationId=' + ids[0];
  } else if (url.search) return send(400, error('INVALID_REQUEST', 'Revisa la solicitud.'));
  const writing = resource === 'staff-messages';
  const headers = guard(request, send, writing); if (!headers) return;
  if (resource === 'notifications/test' && request.headers.origin) {
    let origin; try { origin = new URL(request.headers.origin); } catch {}
    if (!origin || !['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password || origin.host !== request.headers.host || origin.origin !== request.headers.origin) return send(403, error('ORIGIN_NOT_ALLOWED', 'Origen no permitido.'));
  }
  let body;
  if (writing) {
    const key = request.headers['idempotency-key'];
    if (typeof key !== 'string' || !/^[\x21-\x7e]{8,100}$/.test(key)) return send(400, error('INVALID_REQUEST', 'Falta la clave de la operación.'));
    headers['Idempotency-Key'] = key;
    try {
      const value = await readPayload(request, 8192);
      exact(value, ['id', 'locationId', 'kind', 'text']);
      check(uuid(value.id) && uuid(value.locationId) && kinds.includes(value.kind));
      body = JSON.stringify({ id: value.id, locationId: value.locationId, kind: value.kind, text: text(value.text ?? '', 500) });
    } catch { return send(400, error('INVALID_REQUEST', 'Revisa los datos del aviso.')); }
    headers['Content-Type'] = 'application/json';
  }
  let result;
  try {
    result = await forward({ upstream, path: `/api/v1/organizations/${org}/${resource}${query}`, method, headers, body, response, send, codes: ['FEATURE_UNAVAILABLE', 'NO_ACTIVE_DEVICE', 'RATE_LIMITED'], fields: ['id', 'locationId', 'kind', 'text'] });
    if (!result) return;
    check([200, 202].includes(result.status) && /^application\/json(?:\s*;[^\r\n]*)?$/i.test(result.headers.get('content-type') || ''));
    const payload = await readPayload(result.body, 16384); check(object(payload) && object(payload.data));
    const v = payload.data;
    let data;
    if (resource === 'notifications/test') { check(v.status === 'QUEUED'); data = { status: 'QUEUED' }; }
    else if (resource === 'staff-messages') { check(uuid(v.id) && uuid(v.locationId) && kinds.includes(v.kind)); data = { id: v.id, locationId: v.locationId, kind: v.kind, text: text(v.text ?? '', 500), createdAt: timestamp(v.createdAt) }; }
    else {
      const whatsapp = digits(v.whatsapp);
      data = whatsapp ? { name: text(v.name ?? '', 200), role: ['CHEF', 'ADMIN'].includes(v.role) ? v.role : (check(false), null), whatsapp } : { name: null, role: null, whatsapp: null };
    }
    return send(result.status, JSON.stringify({ data }));
  } catch { return send(503, error('BACKEND_UNAVAILABLE', 'No pudimos confirmar la operación.')); }
  finally { if (result && !result.bodyUsed) await result.body?.cancel().catch(() => {}); }
}
