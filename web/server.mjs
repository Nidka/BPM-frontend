import http from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const types = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.wasm': 'application/wasm',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon',
  '.ttf': 'font/ttf', '.woff2': 'font/woff2', '.map': 'application/json',
};
const errorBody = (code, message) => JSON.stringify({ errors: [{ code, message, fields: {} }] });

export function createAppServer({
  distDir = resolve('composeApp/build/dist/wasmJs/productionExecutable'),
  apiBaseUrl = process.env.API_BASE_URL || 'https://bpm-service-production.up.railway.app',
  firebaseApiKey = process.env.FIREBASE_API_KEY || '',
  allowInsecureForTests = false,
} = {}) {
  const upstream = new URL(apiBaseUrl);
  if ((!allowInsecureForTests && upstream.protocol !== 'https:') ||
      upstream.username || upstream.password || upstream.search || upstream.hash || upstream.pathname !== '/') {
    throw new Error('API_BASE_URL debe indicar el origen HTTPS del backend, sin credenciales ni rutas.');
  }
  const root = resolve(distDir);
  if (firebaseApiKey && !/^[A-Za-z0-9_-]{20,100}$/.test(firebaseApiKey)) {
    throw new Error('FIREBASE_API_KEY debe ser la clave pública del cliente Firebase.');
  }
  return http.createServer(async (request, response) => {
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('X-Frame-Options', 'DENY');
    response.setHeader('Referrer-Policy', 'no-referrer');
    response.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
    response.setHeader('Content-Security-Policy',
      "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self' https://identitytoolkit.googleapis.com; worker-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
    response.setHeader('Cache-Control', 'no-store');
    const send = (status, body, contentType = 'application/json; charset=utf-8') => {
      response.writeHead(status, { 'Content-Type': contentType });
      response.end(request.method === 'HEAD' ? undefined : body);
    };
    if (!['GET', 'HEAD'].includes(request.method)) {
      response.setHeader('Allow', 'GET, HEAD');
      return send(405, errorBody('METHOD_NOT_ALLOWED', 'Método no permitido.'));
    }
    try {
      const url = new URL(request.url, 'http://localhost');
      if (url.pathname === '/config.json' && !url.search) {
        // Lista explícita de configuración pública: nunca serializar process.env.
        return send(200, JSON.stringify({ firebaseApiKey }));
      }
      if (url.pathname === '/healthz') {
        await stat(resolve(root, 'index.html'));
        return send(200, JSON.stringify({ status: 'ok' }));
      }
      if (url.pathname.startsWith('/backend/')) {
        // Lista cerrada inicial. No aceptar destinos, tokens ni URLs desde el navegador.
        const path = url.pathname.slice('/backend'.length);
        if (!['/health', '/ready', '/api/v1/me'].includes(path) || url.search) {
          return send(404, errorBody('NOT_FOUND', 'Recurso no disponible.'));
        }
        const headers = { Accept: 'application/json' };
        if (path === '/api/v1/me') {
          const authorization = request.headers.authorization;
          if (typeof authorization !== 'string' || authorization.length > 8192 ||
              !/^Bearer [A-Za-z0-9._-]+$/.test(authorization)) {
            return send(401, errorBody('UNAUTHENTICATED', 'Debes iniciar sesión.'));
          }
          headers.Authorization = authorization;
        }
        try {
          const result = await fetch(new URL(path, upstream), {
            method: 'GET', redirect: 'error',
            headers,
            signal: AbortSignal.timeout(10_000),
          });
          if (!result.ok) {
            if (path === '/api/v1/me') {
              const data = await result.json().catch(() => ({}));
              const code = data.errors?.[0]?.code;
              const errors = {
                UNAUTHENTICATED: [401, 'Debes iniciar sesión nuevamente.'],
                USER_NOT_REGISTERED: [403, 'Tu cuenta no está habilitada en BPM.'],
                USER_INACTIVE: [403, 'Tu cuenta se encuentra inactiva.'],
                AUTHENTICATION_UNAVAILABLE: [503, 'El servicio de autenticación no está configurado.'],
              };
              if (Object.hasOwn(errors, code) && errors[code][0] === result.status) {
                return send(result.status, errorBody(code, errors[code][1]));
              }
              if (result.status === 429) return send(429, errorBody('RATE_LIMITED', 'Demasiadas solicitudes. Inténtalo más tarde.'));
            }
            if (!result.bodyUsed) await result.body?.cancel();
            return send(503, errorBody('BACKEND_UNAVAILABLE', 'El backend no está disponible.'));
          }
          const body = await result.text();
          if (body.length > 65_536) {
            return send(502, errorBody('INVALID_RESPONSE', 'Respuesta no válida del backend.'));
          }
          // Reenviar únicamente los campos públicos esperados.
          const data = JSON.parse(body);
          if (path === '/api/v1/me') {
            const profile = data.data;
            if (!profile || typeof profile.id !== 'string' || typeof profile.email !== 'string' ||
                typeof profile.displayName !== 'string' || !Array.isArray(profile.organizations)) {
              return send(502, errorBody('INVALID_RESPONSE', 'No se pudo verificar la sesión.'));
            }
            const organizations = profile.organizations.map(org => {
              if (typeof org.id !== 'string' || typeof org.legalName !== 'string' || typeof org.role !== 'string') {
                throw new Error('Invalid profile');
              }
              return { id: org.id, legalName: org.legalName, tradeName: org.tradeName, role: org.role };
            });
            return send(200, JSON.stringify({ data: {
              id: profile.id, email: profile.email, displayName: profile.displayName, organizations,
            } }));
          }
          if (path === '/health' && data.status === 'ok' && typeof data.version === 'string') {
            return send(200, JSON.stringify({ status: 'ok', version: data.version }));
          }
          if (path === '/ready' && data.status === 'ready') {
            return send(200, JSON.stringify({ status: 'ready' }));
          }
          return send(502, errorBody('INVALID_RESPONSE', 'Respuesta no válida del backend.'));
        } catch {
          return send(503, errorBody('BACKEND_UNAVAILABLE', 'No se pudo conectar con el backend.'));
        }
      }
      const decoded = decodeURIComponent(url.pathname);
      let file = resolve(root, '.' + decoded);
      if (file !== root && !file.startsWith(root + sep)) {
        return send(404, errorBody('NOT_FOUND', 'Recurso no disponible.'));
      }
      if (decoded.endsWith('/')) file = resolve(file, 'index.html');
      try {
        const contents = await readFile(file);
        return send(200, contents, types[extname(file)] || 'application/octet-stream');
      } catch {
        // Las rutas de navegación de la SPA vuelven al index; los assets faltantes son 404.
        if (extname(decoded)) return send(404, errorBody('NOT_FOUND', 'Recurso no disponible.'));
        return send(200, await readFile(resolve(root, 'index.html')), types['.html']);
      }
    } catch {
      return send(500, errorBody('INTERNAL_ERROR', 'No se pudo completar la solicitud.'));
    }
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT || 3000);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('PORT inválido.');
  const server = createAppServer({ distDir: process.env.DIST_DIR });
  server.listen(port, '0.0.0.0', () => console.info('BPM frontend disponible en el puerto ' + port));
  for (const signal of ['SIGTERM', 'SIGINT']) {
    process.on(signal, () => {
      server.close(() => process.exit(0));
      setTimeout(() => process.exit(1), 10_000).unref();
    });
  }
}
