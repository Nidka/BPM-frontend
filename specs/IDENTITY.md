# Login inicial

Código implementado para Android, iOS y web. El acceso real requiere configurar
Firebase y habilitar el UID en PostgreSQL. Sin proveedor se muestra
AUTH_NOT_CONFIGURED; no existen credenciales por defecto ni modo demo.

Proyecto registrado: `app-bpm-af59c`, app web `bpm`. La configuración pública
vive en `config/firebase.public.json`, compartida por el build KMP y el servidor
web. No contiene clave privada ni cuenta Admin. Authentication habilitado,
cuenta Admin del backend y alta del UID siguen requiriendo verificación.
La key fue comprobada contra Firebase con una cuenta ficticia inexistente:
respondió INVALID_LOGIN_CREDENTIALS. No se crearon usuarios ni sesiones reales.

## Recorrido y arquitectura

Formulario → IdentityViewModel → SignIn → IdentityRepository → Firebase Auth REST
→ ID token → GET /api/v1/me → perfil autorizado desde PostgreSQL.

Solo se muestra «Sesión verificada» si Firebase y la API responden correctamente
y los UID coinciden. Firebase autentica; el backend decide estado, roles y tenant.
La API Go no recibe contraseñas. No hay registro público ni autoalta en PostgreSQL.

`features/identity/domain`: modelos, contrato, SignIn, GetCurrentProfile y SignOut.
`data`: adaptador Firebase/perfil con Ktor. `presentation`: pantalla, estado y
ViewModel. AppGraph compone por constructor. El token es privado del repositorio.

Los errores son un arreglo con code, message y fields para fallos de formulario.
Se traducen códigos conocidos a mensajes seguros en español, nunca el error crudo
del proveedor. Correo inexistente, contraseña incorrecta y usuario Firebase
deshabilitado tienen el mismo mensaje para no revelar existencia de cuentas.

## Preparar Firebase

1. Crear un proyecto en https://console.firebase.google.com.
2. Authentication → Get started → Sign-in method → Email/Password: habilitar
   correo/contraseña. No habilitar acceso anónimo.
3. Project settings → General: registrar una app web, copiar `apiKey` (Web API
   Key pública) y Project ID. La API key no es la cuenta de servicio Admin.
4. Authentication → Users: crear una cuenta de prueba y copiar su UID exacto.
   El titular establece la contraseña allí; no enviarla por chat ni guardarla en Git.
5. Settings → Authorized domains: registrar appbpm.com y los dominios Railway
   utilizados. Revisar restricciones de la API key para permitir Identity Toolkit.

Fuentes: [Firebase Auth REST](https://firebase.google.com/docs/reference/rest/auth),
[API keys Firebase](https://firebase.google.com/docs/projects/api-keys).

## Variables de Railway

Servicio **BPM-frontend**:
```env
FIREBASE_API_KEY=<Web API Key pública>
API_BASE_URL=https://bpm-service-production.up.railway.app
```

La web obtiene exclusivamente firebaseApiKey desde `/config.json`, con no-store.
Se evalúa al iniciar Node. Android/iOS la reciben en el build con
`-PfirebaseApiKey=<clave-pública>` o la variable FIREBASE_API_KEY del entorno.
Si no existe override, web/Android/iOS usan config/firebase.public.json.
Una variable FIREBASE_API_KEY explícitamente vacía deshabilita el login. El .env
local está ignorado; cargarlo con `node --env-file=.env web/server.mjs`.
Este recorrido usa REST, no necesita agregar un SDK Firebase por plataforma.

Servicio **BPM-service**:
```env
FIREBASE_ENABLED=true
FIREBASE_PROJECT_ID=<ID del mismo proyecto>
FIREBASE_SERVICE_ACCOUNT_JSON_BASE64=<secreto sellado>
```

Crear la cuenta de servicio desde Project settings → Service accounts. El base64
de su JSON se configura como secreto sellado solo en el backend. El JSON contiene
clave privada: mantenerlo fuera del repositorio, frontend y chat. Para comprobar
revocación requiere permisos Firebase Auth de lectura. No modificar DATABASE_URL.

## Habilitar la cuenta en PostgreSQL

Ejecutar manualmente en pgAdmin, base bpm, sustituyendo los marcadores. No es una
migración ni se ejecuta al desplegar:

```sql
INSERT INTO application_users
    (id, email, display_name, platform_role, status, created_by, updated_by)
VALUES
    ('UID_FIREBASE', 'CORREO_DE_PRUEBA', 'NOMBRE_DE_PRUEBA',
     'USER', 'ACTIVE', 'ACTOR_ADMINISTRATIVO', 'ACTOR_ADMINISTRATIVO');
```

Usar el UID real, no un UUID inventado. No dar SUPER_ADMIN para esta prueba ni
reactivar/sobrescribir cuentas existentes. Una cuenta sin membresías puede
verificar su perfil, pero no operar en tenants. La gestión de membresías queda pendiente.

## Seguridad y límites

- Contraseña enmascarada, solo en memoria, sin rememberSaveable; se vacía al
  enviar/salir. Se transmite solo a Firebase por HTTPS, nunca al proxy Node.
- ID token solo en memoria; recargar/reiniciar exige nuevo login. No se retiene
  refreshToken. Al vencer/revocar el token, /me devuelve 401 y se exige login.
- Renovación automática y persistencia segura son recorridos posteriores.
- Cerrar sesión cancela solicitudes y borra el token local; no revoca otras sesiones.
- Login necesita red: no hay autenticación offline ni acceso ficticio.
- Proxy web: GET /backend/api/v1/me, solo Bearer; no reenvía cookies, tenant ni
  destinos arbitrarios. La CSP permite Firebase en identitytoolkit.googleapis.com.
- Fallos temporales durante verificación mantienen el perfil anterior con error;
  no significan que la sesión se haya revalidado. Un 401/403 borra el acceso.

## Verificación manual

Probar formulario inválido, credenciales incorrectas, usuario Firebase sin alta
PostgreSQL, usuario inactivo y usuario activo. Solo este último debe mostrar
«Sesión verificada». «Verificar sesión con la API» vuelve a consultar /me;
«Cerrar sesión» vuelve al formulario. Recargar exige login. Las pruebas
automatizadas usan dobles y credenciales ficticias, nunca cuentas reales.
