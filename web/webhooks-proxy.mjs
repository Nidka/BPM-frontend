// Proxy de webhooks de la empresa (RF-48). El secreto solo atraviesa al crear o rotar; no se registra.
import { idPattern, object, check, exact, uuid, text, error, timestamp, readPayload, guard, forward } from './proxy-common.mjs';

const route = new RegExp(`^/backend/api/v1/organizations/([A-Za-z0-9_-]{1,64})/webhooks(?:/(${idPattern})(?:/(rotate-secret|test))?)?$`);
const types = ['CHECKLIST_MISSING', 'TEMPERATURE_DANGER', 'ORGANIZATION_INACTIVE', 'TRAINING_ASSIGNED'];

function matchRoute(path) { const m = path.match(route); return m ? { org: m[1], id: m[2], action: m[3] } : undefined; }
export const webhooksRoute = path => Boolean(matchRoute(path));

function publicWebhook(value, withSecret) {
  check(object(value) && uuid(value.id) && typeof value.active === 'boolean' && Array.isArray(value.eventTypes) && value.eventTypes.length >= 1 && value.eventTypes.every(t => types.includes(t)));
  const out = { id: value.id, url: text(value.url, 500, 9), eventTypes: value.eventTypes, active: value.active,
    lastStatus: value.lastStatus == null ? null : (check(Number.isSafeInteger(value.lastStatus) && value.lastStatus >= 100 && value.lastStatus <= 599), value.lastStatus),
    lastError: text(value.lastError ?? '', 200), lastDeliveryAt: value.lastDeliveryAt == null ? null : timestamp(value.lastDeliveryAt) };
  check(out.url.startsWith('https://'));
  if (withSecret) { check(typeof value.secret === 'string' && /^whsec_[A-Za-z0-9_-]{32,100}$/.test(value.secret)); out.secret = value.secret; } else check(value.secret === undefined);
  return out;
}

export async function proxyWebhooks(request, url, upstream, send, response) {
  const match = matchRoute(url.pathname); if (!match) return send(404, error('NOT_FOUND', 'Recurso no disponible.'));
  const { org, id, action } = match, method = request.method;
  const allowed = action ? method === 'POST' : id ? ['PUT', 'DELETE'].includes(method) : ['GET', 'POST'].includes(method);
  if (!allowed) return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));
  if (url.search) return send(400, error('INVALID_REQUEST', 'Revisa la solicitud.'));
  const writing = method === 'POST' || method === 'PUT';
  const headers = guard(request, send, writing); if (!headers) return;
  let body;
  if (writing) {
    try {
      const value = await readPayload(request, 8192);
      if (action) { exact(value, []); body = {}; }
      else {
        exact(value, ['url', 'eventTypes', 'active']);
        check(Array.isArray(value.eventTypes) && value.eventTypes.length >= 1 && value.eventTypes.length <= 10 && value.eventTypes.every(t => types.includes(t)) && new Set(value.eventTypes).size === value.eventTypes.length);
        check(value.active === undefined || typeof value.active === 'boolean');
        body = { url: text(value.url, 500, 9), eventTypes: value.eventTypes, active: value.active ?? true }; check(body.url.startsWith('https://'));
      }
    } catch { return send(400, error('INVALID_REQUEST', 'Revisa los datos de la solicitud.')); }
    headers['Content-Type'] = 'application/json'; body = JSON.stringify(body);
  }
  let result;
  try {
    const path = `/api/v1/organizations/${org}/webhooks${id ? '/' + id : ''}${action ? '/' + action : ''}`;
    result = await forward({ upstream, path, method, headers, body, response, send, codes: ['WEBHOOK_LIMIT', 'WEBHOOK_NOT_FOUND'], fields: ['url', 'eventTypes'], timeoutMs: action === 'test' ? 20000 : 10000 });
    if (!result) return;
    if (method === 'DELETE') { check(result.status === 204); return send(204, ''); }
    check([200, 201].includes(result.status) && /^application\/json(?:\s*;[^\r\n]*)?$/i.test(result.headers.get('content-type') || ''));
    const payload = await readPayload(result.body, 256 * 1024); check(object(payload) && object(payload.data));
    let data;
    if (action === 'test') { check(typeof payload.data.delivered === 'boolean' && Number.isSafeInteger(payload.data.status) && payload.data.status >= 0 && payload.data.status <= 599); data = { delivered: payload.data.delivered, status: payload.data.status }; }
    else if (!id && method === 'GET') {
      const v = payload.data; check(Array.isArray(v.items) && v.items.length <= 10 && Array.isArray(v.eventTypes) && v.eventTypes.every(t => types.includes(t)) && Number.isSafeInteger(v.maxEndpoints));
      data = { items: v.items.map(w => publicWebhook(w, false)), eventTypes: v.eventTypes, maxEndpoints: v.maxEndpoints };
    } else { data = publicWebhook(payload.data, method === 'POST' && !id || action === 'rotate-secret'); if (id) check(data.id === id); }
    return send(result.status, JSON.stringify({ data }));
  } catch { return send(503, error('BACKEND_UNAVAILABLE', 'No pudimos confirmar la operación.')); }
  finally { if (result && !result.bodyUsed) await result.body?.cancel().catch(() => {}); }
}
