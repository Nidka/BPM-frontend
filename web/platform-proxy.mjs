// Proxy de la introducción vista (/me/onboarding) y del contacto de soporte (/platform/support). Contrato cerrado.
import { object, check, exact, text, error, timestamp, readPayload, guard, forward } from './proxy-common.mjs';

const routes = { '/backend/api/v1/me/onboarding': 'onboarding', '/backend/api/v1/platform/support': 'support' };
export const platformRoute = path => Object.hasOwn(routes, path);

const email = value => { check(typeof value === 'string' && value.length <= 254 && /^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$/.test(value)); return value; };
const whatsapp = value => { if (value === null || value === undefined) return null; check(typeof value === 'string' && /^[0-9]{8,15}$/.test(value)); return value; };
const seenAt = value => value === null || value === undefined ? null : timestamp(value);

export async function proxyPlatform(request, url, upstream, send, response) {
  const resource = routes[url.pathname]; if (!resource) return send(404, error('NOT_FOUND', 'Recurso no disponible.'));
  const method = request.method;
  const allowed = resource === 'onboarding' ? ['PUT', 'DELETE'].includes(method) : ['GET', 'PUT'].includes(method);
  if (!allowed) return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));
  if (url.search) return send(400, error('INVALID_REQUEST', 'Revisa la solicitud.'));
  const writing = resource === 'support' && method === 'PUT';
  // PUT /me/onboarding no lleva cuerpo; se trata como una acción sin datos (con control de origen).
  const headers = guard(request, send, writing); if (!headers) return;
  if (resource === 'onboarding' && method === 'PUT' && request.headers.origin) {
    let origin; try { origin = new URL(request.headers.origin); } catch {}
    if (!origin || !['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password || origin.host !== request.headers.host || origin.origin !== request.headers.origin) return send(403, error('ORIGIN_NOT_ALLOWED', 'Origen no permitido.'));
  }
  let body;
  if (writing) {
    try {
      const value = await readPayload(request, 4096);
      exact(value, ['supportEmail', 'supportWhatsapp']);
      const phone = value.supportWhatsapp;
      check(phone === undefined || phone === null || (typeof phone === 'string' && phone.length <= 30));
      body = JSON.stringify({ supportEmail: text(value.supportEmail, 254, 1), supportWhatsapp: phone ?? null });
    } catch { return send(400, error('INVALID_REQUEST', 'Revisa los datos de la solicitud.')); }
    headers['Content-Type'] = 'application/json';
  }
  let result;
  try {
    const path = resource === 'onboarding' ? '/api/v1/me/onboarding' : '/api/v1/platform/support';
    result = await forward({ upstream, path, method, headers, body, response, send, fields: ['supportEmail', 'supportWhatsapp'] });
    if (!result) return;
    check(result.status === 200 && /^application\/json(?:\s*;[^\r\n]*)?$/i.test(result.headers.get('content-type') || ''));
    const payload = await readPayload(result.body, 16384); check(object(payload) && object(payload.data));
    const v = payload.data;
    const data = resource === 'onboarding' ? { onboardingSeenAt: seenAt(v.onboardingSeenAt) }
      : { supportEmail: email(v.supportEmail), supportWhatsapp: whatsapp(v.supportWhatsapp), ...(v.updatedAt ? { updatedAt: timestamp(v.updatedAt) } : {}) };
    return send(200, JSON.stringify({ data }));
  } catch { return send(503, error('BACKEND_UNAVAILABLE', 'No pudimos confirmar la operación.')); }
  finally { if (result && !result.bodyUsed) await result.body?.cancel().catch(() => {}); }
}
