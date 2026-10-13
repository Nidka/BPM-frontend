# Railway y dominio

Repositorio independiente: https://github.com/Nidka/BPM-frontend
Rama: main. Root Directory: /. Dockerfile en la raíz.

El contenedor compila la web KMP y sirve los archivos con Node 24 sin privilegios.
PORT lo inyecta Railway. No usa mounts de caché dependientes del servicio.

En el proyecto Railway bpm, crear un servicio BPM-frontend desde GitHub.
El backend BPM-service y Postgres siguen siendo servicios independientes.

Variables del servicio frontend:
```env
API_BASE_URL=https://bpm-service-production.up.railway.app
NODE_ENV=production
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

La primera pantalla no exige Firebase porque solo consulta salud; los módulos
autenticados lo incorporarán con ID token y autorización real del backend.

El dominio necesita acceso DNS a la zona Cloudflare o que su administrador
publique los registros. Crear el dominio en Railway no modifica el DNS externo.

## Servicio creado

- Proyecto: bpm / production. Servicio: BPM-frontend.
- URL web: https://bpm-frontend-production.up.railway.app
- appbpm.com ya está registrado en Railway, pendiente de DNS y certificado.

Registros solicitados por Railway (consultar `railway domain status appbpm.com --json`
antes de modificarlos si se vuelve a crear el dominio):

| Tipo | Nombre | Valor |
|---|---|---|
| CNAME | @ | jt7bsxt7.up.railway.app |
| TXT | _railway-verify | railway-verify=0cafc3593eba3b615fd9dbfb8c657a9aead2db83a7e4cb49f2734fd1a4d7ea08 |

DNS observado el 12/10/2026: nameservers `verify-contact-details.namecheap.com`
y `failed-whois-verification.namecheap.com`. Esto apunta a verificación pendiente
del titular. El titular confirmó después la verificación exitosa del correo;
Namecheap indicó reactivación usual en 24–48 horas. Reconsultar DNS antes de activar.
[Proceso oficial de verificación](https://www.namecheap.com/support/knowledgebase/article.aspx/9305/5/whois-verification-process/).
Una zona creada en Cloudflare solo será autoritativa después de configurar sus
nameservers en el registrador. No se han cambiado registros DNS externos.
