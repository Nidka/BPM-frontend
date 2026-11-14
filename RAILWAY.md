# Railway y dominio

Actualización del 18/10/2026: `https://appbpm.com/healthz` y el dominio `www`
respondieron 200 con certificados válidos. Los diagnósticos del 13/10 incluidos
abajo son históricos; no deben usarse para reemplazar registros actuales sin
volver a consultar la consola. No se modificaron DNS en esta entrega.

La entrega de sesiones permite publicar el frontend antes del backend: las rutas
nuevas se usan solo cuando el servidor anuncia la política. El backend requiere
su migración V2 antes de publicarse. El contenedor frontend activa HSTS en
producción/Railway para el host actual, sin includeSubDomains ni preload; no lo
activa por defecto en HTTP local. Verificar la cabecera tras desplegar.

Repositorio independiente: https://github.com/Nidka/BPM-frontend
Rama: main. Root Directory: /. Dockerfile en la raíz.

El contenedor compila la web KMP y sirve los archivos con Node 24 sin privilegios.
PORT lo inyecta Railway. No usa mounts de caché dependientes del servicio.
La etapa final incluye los módulos del proxy, incluida `web/pagination.mjs`.
`scripts/container-runtime.test.mjs` copia los archivos declarados en esa etapa
y comprueba el arranque, `/healthz` y los scripts de autenticación en un directorio
temporal. CI ejecuta esta prueba antes de compilar; no necesita Docker ni servicios reales.

En el proyecto Railway bpm, crear un servicio BPM-frontend desde GitHub.
El backend BPM-service y Postgres siguen siendo servicios independientes.

Variables del servicio frontend:
```env
API_BASE_URL=https://bpm-service-production.up.railway.app
NODE_ENV=production
# FIREBASE_API_KEY se configura con la clave pública del proyecto Firebase.
```

API_BASE_URL se evalúa al iniciar el servidor web; es el origen del backend, sin
/api/v1 ni slash final. No copiar DATABASE_URL, Firebase Admin ni credenciales R2.

Settings:
- Rama main y autodeploy habilitado.
- Wait for CI habilitado para esperar Frontend CI.
- Healthcheck Path: /healthz; timeout: 30 segundos.
- Restart Policy: On Failure.
- Sin Build/Start Command personalizados; los define Dockerfile.

Generate Domain publica el frontend con HTTPS. Comprobar:
- /healthz: el servidor web sirve la distribución.
- /backend/health: proceso API disponible.
- /backend/ready: PostgreSQL disponible.
- /: pantalla Compose que confirma ambos estados.

La salud del contenedor frontend no depende del backend; si la API se cae,
la web continúa disponible y muestra el estado de error con reintento.

Dominio appbpm.com:
1. Registrar `appbpm.com` y `www.appbpm.com` en Settings → Networking → Custom Domain.
2. Publicar en Cloudflare los CNAME y TXT exactos que entregue Railway.
3. Para el CNAME del dominio raíz, usar nombre `@`; Cloudflare puede aplanarlo.
4. Mantener ambos CNAME en **Solo DNS** hasta verificar el certificado HTTPS.
5. Probar `https://appbpm.com/healthz` y `https://www.appbpm.com/healthz`.

No cambiar registros MX, correo ni otros subdominios. La web usa proxy del mismo
origen, por lo que cambiar al dominio propio no exige ampliar CORS en el backend.

El inicio muestra login; la comprobación de salud sigue accesible sin sesión.
Configurar Firebase según specs/IDENTITY.md para probar un acceso real.
Proyecto cliente configurado: app-bpm-af59c. La key pública predeterminada está
en config/firebase.public.json; FIREBASE_API_KEY permite cambiarla por entorno.

El dominio necesita acceso DNS a la zona Cloudflare o que su administrador
publique los registros. Crear el dominio en Railway no modifica el DNS externo.

## Servicio creado

- Proyecto: bpm / production. Servicio: BPM-frontend.
- URL web: https://bpm-frontend-production.up.railway.app
- Ambos dominios usan target port 8080, el PORT efectivo de Railway (no el 3000 local).
- `appbpm.com` y `www.appbpm.com` están registrados en Railway y apuntan al
  frontend. Sus certificados HTTPS están emitidos.

Registros solicitados por Railway (consultar `railway domain status appbpm.com --json`
antes de modificarlos si se vuelve a crear el dominio):

| Tipo | Nombre | Valor |
|---|---|---|
| CNAME | @ | jt7bsxt7.up.railway.app |
| TXT | _railway-verify | railway-verify=0cafc3593eba3b615fd9dbfb8c657a9aead2db83a7e4cb49f2734fd1a4d7ea08 |
| CNAME | www | nipc9gv4.up.railway.app |
| TXT | _railway-verify.www | railway-verify=a09aa8fef7145ac38bf17a3b9e551ad60d001fdb384c97d403b74f0837b41ee6 |

Estado comprobado el 13/10/2026: los cuatro registros están publicados en
Cloudflare. Railway marca ambos CNAME como `PROPAGATED`, los dos dominios como
`verified: true` y sus certificados como `VALID`. Tanto
`https://appbpm.com/healthz` como `https://www.appbpm.com/healthz` responden 200
con HTTPS válido. `https://appbpm.com/backend/health` también responde 200.
`www.appbpm.com` está añadido a Firebase Authentication → Dominios autorizados,
igual que el dominio raíz. Los CNAME continúan en **Solo DNS**.

Para futuras comprobaciones, usar `railway domain status appbpm.com --json` y
`railway domain status www.appbpm.com --json`. Mantener intactos MX, SPF y los
demás subdominios. Si se evalúa activar el proxy naranja de Cloudflare, revisar
primero su modo TLS y volver a probar HTTPS y el login en ambos dominios.
