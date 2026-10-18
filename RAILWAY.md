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
1. Añadir appbpm.com en Settings → Networking → Custom Domain.
2. Crear en Cloudflare los registros exactos CNAME y TXT que entregue Railway.
3. Para el CNAME del dominio raíz, usar nombre @; Cloudflare puede aplanarlo.
4. Comenzar con DNS only hasta verificar certificado/HTTPS.
5. Probar https://appbpm.com y el mensaje de conexión exitosa.

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
- appbpm.com ya está registrado en Railway. El 13/10/2026 seguía pendiente de
  verificación de propiedad y certificado; usar la URL Railway mientras tanto.

Registros solicitados por Railway (consultar `railway domain status appbpm.com --json`
antes de modificarlos si se vuelve a crear el dominio):

| Tipo | Nombre | Valor |
|---|---|---|
| CNAME | @ | jt7bsxt7.up.railway.app |
| TXT | _railway-verify | railway-verify=0cafc3593eba3b615fd9dbfb8c657a9aead2db83a7e4cb49f2734fd1a4d7ea08 |

Estado observado el 13/10/2026: el dominio ya resuelve por Cloudflare y tiene
certificado TLS en el borde, pero `railway domain status appbpm.com --json` aún
informa CNAME raíz `REQUIRES_UPDATE` y `verified: false`. El dominio propio no
responde; no confundir TLS del borde con verificación del origen Railway.

En Cloudflare → DNS, reemplazar **solo** el A de `@` que apunta al parking de
Namecheap por el CNAME `@ → jt7bsxt7.up.railway.app`. Configurarlo inicialmente
como **Solo DNS** (nube gris). Añadir el TXT `_railway-verify` de la tabla si
no existe. Mantener intactos MX, SPF y otros subdominios. Luego esperar la
propagación y comprobar que Railway muestre `verified: true`, certificado listo
y `https://appbpm.com/healthz` responda 200. Solo entonces evaluar activar el
proxy naranja de Cloudflare.
