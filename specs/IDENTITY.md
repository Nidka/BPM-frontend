# Identidad: login y registro

## Alcance

Correo/contraseña, registro, verificación de email, reenvío y recuperación
implementados para web, Android e iOS. Google implementado en web; sus adaptadores
nativos siguen pendientes de registrar apps/clientes OAuth en Firebase.

Proyecto Firebase: app-bpm-af59c. El cliente usa solo config/firebase.public.json
(o overrides públicos). La cuenta Admin pertenece exclusivamente al backend.

## Recorridos

- Entrar: Firebase → ID token → GET /api/v1/me → perfil y sesión en memoria.
- Crear cuenta: formulario validado → Firebase signUp → guardar nombre →
  enviar VERIFY_EMAIL → pantalla de instrucciones. Todavía no hay sesión BPM.
- Después de verificar email: iniciar sesión nuevamente → si falta perfil,
  POST /api/v1/auth/register con Bearer y displayName → perfil USER/ACTIVE.
- Google web: popup oficial → ID token Firebase → mismo recorrido de perfil.
  Una identidad nueva con email verificado puede completar el registro.
- Recuperar acceso: PASSWORD_RESET enviado directamente a Firebase; el mensaje
  siempre es neutral respecto a si el correo existe.
- Verificar sesión: consultar /me nuevamente. Logout cancela trabajo y borra
  tokens locales, incluyendo el token temporal para reenviar verificación.

Crear una cuenta no crea organizaciones, membresías, locales ni roles elevados.
Un usuario inactivo nunca se reactiva mediante login/registro. No se fusionan
UID por coincidencia de correo. Los permisos salen de PostgreSQL.

## Arquitectura y UI

features/identity/domain declara contratos y casos de uso. data contiene el
adaptador Ktor/Firebase y el puerto Google con implementaciones por plataforma.
presentation expone AuthMode/IdentityUiState y un ViewModel con casos de uso,
sin HTTP ni SDK. AppGraph inyecta todo por constructor.

Estilo inspirado en iOS: fondo gris agrupado, superficies blancas redondeadas,
azul de acción, selector Entrar/Crear cuenta, campos claros y controles de
mínimo 52 dp para acciones principales. No depende de imágenes ni CDN visuales.

La pantalla permite mostrar/ocultar contraseña, confirmar contraseña, recuperar
acceso, reenviar verificación, consultar perfil y cerrar sesión. Las contraseñas
no usan rememberSaveable y se vacían al enviar o cambiar de modo.

## Contratos y errores

Todos los fallos se representan con errors[]: code, message y fields. Los fallos
de formulario señalan displayName, email, password y confirmPassword. Nunca se
presentan excepciones, mensajes SQL, tokens o mensajes originales de Firebase.

Códigos importantes: VALIDATION_ERROR, INVALID_CREDENTIALS, EMAIL_NOT_VERIFIED,
PASSWORD_POLICY, REGISTRATION_REJECTED, REGISTRATION_CONFLICT, USER_INACTIVE,
AUTH_DOMAIN_NOT_AUTHORIZED, AUTH_NOT_CONFIGURED, RATE_LIMITED y NETWORK_ERROR.
Un popup cerrado no crea sesión. Solo un perfil autorizado con UID coincidente
permite entrar; nunca se simula éxito con una respuesta de salud.

## Configuración

En Firebase Authentication habilitar Email/Password y Google; seleccionar el
correo de asistencia. En Configuración → Dominios autorizados agregar:

- localhost
- bpm-frontend-production.up.railway.app
- appbpm.com
- Otros dominios Railway que realmente se utilicen, sin protocolos ni puertos.

En el backend Railway: FIREBASE_ENABLED=true, FIREBASE_PROJECT_ID=app-bpm-af59c
y FIREBASE_SERVICE_ACCOUNT_JSON_BASE64 como secreto sellado. El JSON permanece
fuera de Git y del frontend. DATABASE_URL sigue siendo la referencia Postgres.

En frontend Railway: API_BASE_URL con el origen HTTPS backend; FIREBASE_API_KEY
y FIREBASE_PROJECT_ID son públicos. /config.json expone únicamente esos datos y
el authDomain derivado, con no-store. Una API key explícitamente vacía desactiva
el acceso. Android/iOS reciben la key pública al compilar.

La web usa SDK Firebase 13.0.0 fijado para Google e inMemoryPersistence. El SDK se
cierra tras obtener el ID token. La CSP permite gstatic, apis.google.com, Firebase
Auth y el iframe del proyecto; no se piden scopes Drive/Gmail ni Analytics.

## Proxy y seguridad

El proxy Node tiene lista cerrada:
GET /backend/health, /backend/ready, /backend/api/v1/me;
POST /backend/api/v1/auth/register acepta solo displayName, JSON hasta 4 KiB y
Bearer. No reenvía contraseñas, cookies, tenant, roles ni destinos arbitrarios.
Sanitiza códigos y campos del backend y limita tiempos de espera.

El backend deriva UID/email/emailVerified del token revocable. Registro requiere
correo verificado y conserva cuentas existentes, con auditoría y unicidad de
correo normalizado. Un rechazo 403/409 nunca se convierte en sesión válida.

## Verificación

Pruebas compartidas: validación antes de red, orden signUp/update/VERIFY_EMAIL,
recuperación neutral, registro backend sin contraseña/roles, UID coincidente,
correo no verificado, errores y borrado de sesión. Node prueba el proxy y Google.
Go prueba el registro idempotente, email verificado, rechazos y PostgreSQL real.

Prueba manual: crear cuenta propia → abrir enlace del correo → volver a entrar
→ comprobar perfil sin organizaciones → verificar sesión → cerrar sesión.
Probar además formulario vacío, contraseñas diferentes, popup cancelado y cuenta
inactiva. El titular introduce su contraseña y completa Google; no enviarlas al
equipo ni al chat. Las pruebas automatizadas no crean cuentas Firebase reales.

## Límites explícitos

No hay persistencia ni autenticación offline, ni renovación automática del
ID token. Recargar/reiniciar exige entrar de nuevo. Registro y recuperación
necesitan internet; los enlaces se gestionan con las páginas de acción Firebase.
Si una creación Firebase parcial falla, no se borra la cuenta: iniciar sesión,
reenviar verificación o recuperar acceso. Una actualización de perfil posterior
y la administración de membresías son recorridos futuros.

Fuentes: [Firebase REST](https://firebase.google.com/docs/reference/rest/auth),
[Google web](https://firebase.google.com/docs/auth/web/google-signin),
[persistencia Firebase](https://firebase.google.com/docs/auth/web/auth-state-persistence).
