# Identidad: login y registro

## Alcance

Correo/contraseña, registro, verificación de email, reenvío y recuperación
implementados para web, Android e iOS. Google implementado en web; sus adaptadores
nativos siguen pendientes de registrar apps/clientes OAuth en Firebase.

Proyecto Firebase: app-bpm-af59c. El cliente usa solo config/firebase.public.json
(o overrides públicos). La cuenta Admin pertenece exclusivamente al backend.

## Recorridos

- Entrar: Firebase → ID token → GET /api/v1/me → perfil confirmado y sesión.
  El refresh token se guarda protegido solo en móvil; web conserva todo en memoria.
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

Estilo BPM Aula lúdico profesional: petróleo/coral, superficies redondeadas,
selector Entrar/Crear cuenta, campos claros y acciones de mínimo 52 dp.
Usa ilustraciones originales empaquetadas con Compose, sin CDN visuales.
Al confirmar sesión se abre el nuevo workspace (WORKSPACE.md).

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

El puente abre el popup dentro del click y mantiene un bloqueo hasta terminar
la limpieza del SDK. Logout invalida resultados tardíos sin ejecutar signOut
concurrente con OAuth. GOOGLE_CANCELLED, GOOGLE_IN_PROGRESS,
GOOGLE_POPUP_BLOCKED, GOOGLE_NETWORK_ERROR y GOOGLE_UNAVAILABLE distinguen
cancelación, concurrencia, navegador, red y fallo del proveedor. La UI muestra
el código seguro; nunca el mensaje original de Google.

Solo USER_NOT_REGISTERED explícito permite intentar el alta BPM. Un 403
desconocido se convierte en ACCESS_DENIED: no crea usuarios ni modifica permisos.
Un nombre Google vacío, demasiado corto/largo o con controles usa Usuario BPM
para el alta mínima; los nombres existentes nunca se sobrescriben.

Si Firebase ya creó la cuenta y falla actualizar el nombre o enviar el correo,
REGISTRATION_INCOMPLETE lleva a verificación y habilita reenvío. El reintento
completa el nombre pendiente antes de enviar el enlace, sin repetir signUp.
El nombre/UID/token pendientes solo viven en memoria y se borran al salir.
No confundir esta cuenta pendiente con una sesión BPM válida.

GET /me también exige emailVerified: un perfil antiguo no evita verificar el
correo. La verificación se confía exclusivamente al token validado del backend.

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

La web usa SDK Firebase 13.0.0 fijado para Google e inMemoryPersistence. El puente
entrega ID token, refresh token, UID y vencimiento al adaptador Kotlin en memoria,
y limpia la sesión del SDK al terminar. La CSP permite gstatic, apis.google.com, Firebase
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

Regresiones cubiertas: alta Google nueva, nombre externo inválido, 403 desconocido,
registro parcial y reenvío sin duplicación, bloqueo durante limpieza Google,
logout durante OAuth, Origin malformado y correo sin verificar en perfil existente.
Las pruebas con dobles no sustituyen una prueba manual con una cuenta nueva real.

Prueba manual: crear cuenta propia → abrir enlace del correo → volver a entrar
→ comprobar perfil sin organizaciones → verificar sesión → cerrar sesión.
Probar además formulario vacío, contraseñas diferentes, popup cancelado y cuenta
inactiva. El titular introduce su contraseña y completa Google; no enviarlas al
equipo ni al chat. Las pruebas automatizadas no crean cuentas Firebase reales.

## Límites explícitos

La política de sesión coordinada con backend añade máximo absoluto de 8 horas
desde la autenticación Firebase y 15 minutos sin actividad. El servidor decide
con `auth_time` verificado y estado persistente; los tokens renovados no extienden
ese máximo. `GET /me` comprueba la sesión sin prolongarla. La UI notifica entradas
reales (incluidos diálogos, desplazamiento y teclado virtual), con un POST
`/auth/activity` como máximo por minuto; no genera actividad desde temporizadores.
Los endpoints empresariales también actualizan actividad en servidor.

Las cabeceras `X-BPM-Session-Expires-At` y `X-BPM-Session-Idle-Expires-At` permiten
avisar durante el último minuto y retirar acceso local al vencer. El reloj del
cliente solo mejora la experiencia; el backend impide operaciones fuera de plazo.
Un 401 `SESSION_EXPIRED` borra también credenciales móviles y exige autenticación
nueva. No se reintentan escrituras. Las respuestas tardías no restauran sesión.

Logout limpia las credenciales locales inmediatamente y solicita
`POST /auth/logout` para revocar el contexto de autenticación. Si no se confirma
el cierre remoto, informa del fallo. No equivale a revocar todos los dispositivos.
La revocación se comparte entre clientes que usan el mismo UID/auth_time.

El aviso no guarda borradores: los formularios actuales son temporales. La
persistencia offline necesita su implementación independiente y debe respetar
usuario/empresa y autenticación antes de sincronizar.

El frontend puede publicarse antes del backend nuevo: si no recibe cabeceras de
sesión no usa las rutas nuevas. El backend requiere la migración V2 previamente
aplicada; ver `Backend/BPM-service/specs/SESSIONS.md` en el workspace.

La sesión implementa renovación antes del vencimiento con una única operación
concurrente. El perfil se verifica cada minuto mientras existe sesión y desde
Mi cuenta. La renovación usa Secure Token API (formulario grant_type/refresh_token)
directamente desde el cliente; los tokens nunca pasan al proxy de negocio como
payload. Un 401 de una operación empresarial invalida solo el token actual y
limpia la UI. No se reenvían automáticamente escrituras tras un rechazo.

Android persiste el refresh token cifrado AES/GCM con Android Keystore en un
archivo atómico excluido de backups. iOS usa Keychain con
WhenUnlockedThisDeviceOnly y sincronización iCloud desactivada. La entrada incluye
origen API y UID; la restauración rechaza otro origen antes de llamar a Firebase.
Solo renovar y confirmar UID/permisos en /me permite publicar un perfil. Las
credenciales rotadas se conservan ante fallos temporales; la revocación las borra.
Cerrar el grafo conserva la entrada móvil; logout la elimina. Generaciones y
cancelación impiden restaurar una sesión anterior mediante respuestas tardías.

Web no persiste tokens y exige entrar tras recargar. No hay autenticación offline.
Registro, restauración y recuperación necesitan internet; los enlaces de email
siguen gestionados por las páginas de acción Firebase. Los enlaces nativos de
Android/iOS todavía no están registrados en el sistema operativo.

Si una creación Firebase parcial falla, no se borra la cuenta: iniciar sesión,
reenviar verificación o recuperar acceso. Una actualización de perfil posterior
y la edición/desactivación de membresías son recorridos futuros. El alta de
miembros y selección de organización ya están en features/workspace.

Pruebas adicionales: renovación simultánea, rotación, restauración y UID, otro
origen, revocación, indisponibilidad temporal, logout durante renovación e
invalidación visible ante rechazo de API. Los adaptadores nativos compilan en
Android/iOS; verificar además Keychain/Keystore en dispositivo antes del piloto.

Fuentes: [Firebase REST](https://firebase.google.com/docs/reference/rest/auth),
[Google web](https://firebase.google.com/docs/auth/web/google-signin),
[persistencia Firebase](https://firebase.google.com/docs/auth/web/auth-state-persistence).
