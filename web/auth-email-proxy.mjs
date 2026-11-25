// Proxy del correo propio de BPM (verificación y nueva contraseña). Lista cerrada: dos rutas POST, cuerpos mínimos y respuestas sin detalles.
// El código `MAIL_UNAVAILABLE` pasa tal cual: indica a la app que use el envío de Firebase. No se registran correos, tokens ni cuerpos.
import { object, check, exact, text, error, readPayload, forward } from './proxy-common.mjs';

const VERIFICATION = '/backend/api/v1/auth/email/verification';
const RESET = '/backend/api/v1/auth/email/password-reset';

export const authEmailRoute = path => path === VERIFICATION || path === RESET;

function sameOrigin(request) {
  if (!request.headers.origin) return true;
  let origin; try { origin = new URL(request.headers.origin); } catch {}
  return Boolean(origin && ['http:', 'https:'].includes(origin.protocol) && !origin.username && !origin.password &&
    origin.host === request.headers.host && origin.origin === request.headers.origin);
}

export async function proxyAuthEmail(request, url, upstream, send, response) {
  const verification = url.pathname === VERIFICATION;
  if (request.method !== 'POST') return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));
  if (url.search) return send(400, error('INVALID_REQUEST', 'Revisa la solicitud.'));
  let headers, body;
  if (verification) {
    // La verificación pertenece a la sesión recién creada: Bearer obligatorio; cualquier cuerpo se ignora y nunca se reenvía.
    if (!sameOrigin(request)) return send(403, error('ORIGIN_NOT_ALLOWED', 'Origen no permitido.'));
    const authorization = request.headers.authorization;
    if (typeof authorization !== 'string' || authorization.length > 8192 || !/^Bearer [A-Za-z0-9._-]+$/.test(authorization)) return send(401, error('UNAUTHENTICATED', 'Inicia sesión para continuar.'));
    headers = { Accept: 'application/json', Authorization: authorization };
  } else {
    if (!sameOrigin(request)) return send(403, error('ORIGIN_NOT_ALLOWED', 'Origen no permitido.'));
    if (!/^application\/json(?:\s*;[^\r\n]*)?$/i.test(request.headers['content-type'] || '')) return send(415, error('UNSUPPORTED_MEDIA_TYPE', 'Envía una solicitud JSON.'));
    try {
      const value = await readPayload(request, 2048);
      exact(value, ['email']);
      body = JSON.stringify({ email: text(value.email, 254, 3) });
    } catch { return send(400, error('INVALID_REQUEST', 'Revisa los datos de la solicitud.')); }
    headers = { Accept: 'application/json', 'Content-Type': 'application/json' };
  }
  let result;
  try {
    result = await forward({ upstream, path: verification ? '/api/v1/auth/email/verification' : '/api/v1/auth/email/password-reset',
      method: 'POST', headers, body, response, send, timeoutMs: 15000, codes: ['MAIL_UNAVAILABLE'], fields: ['email'] });
    if (!result) return;
    check(result.status === 202);
    const payload = await readPayload(result.body, 4096);
    check(object(payload) && object(payload.data) && ['SENT', 'ALREADY_VERIFIED', 'ACCEPTED'].includes(payload.data.status));
    return send(202, JSON.stringify({ data: { status: payload.data.status } }));
  } catch { return send(503, error('BACKEND_UNAVAILABLE', 'No pudimos confirmar la operación.')); }
  finally { if (result && !result.bodyUsed) await result.body?.cancel().catch(() => {}); }
}
