# Identidad: login y registro

## Alcance

Correo/contraseña, registro, verificación de email, reenvío y recuperación
implementados para web, Android e iOS. Google implementado en web y con adaptador
Credential Manager para Android; su prueba real depende de registrar la firma
y verificar OAuth en Firebase. El adaptador Google de iOS sigue pendiente.

Proyecto Firebase: app-bpm-af59c. El cliente usa solo config/firebase.public.json
(o overrides públicos). La cuenta Admin pertenece exclusivamente al backend.

## Recorridos

- Entrar: Firebase → ID token → GET /api/v1/me → perfil confirmado y sesión.
  El refresh token se guarda protegido solo en móvil; web conserva todo en memoria.
- Crear cuenta: nombres y apellidos separados y validados → Firebase signUp → guardar nombre →
  enviar VERIFY_EMAIL → pantalla de instrucciones. Todavía no hay sesión BPM.
- Después de verificar email: volver a la app y pulsar «Ya confirmé mi correo» →
  renovar el token temporal con Secure Token → confirmar /me → si falta perfil,
  POST /api/v1/auth/register con Bearer y displayName → perfil USER/ACTIVE.
  Si la app se cerró o la credencial temporal venció, iniciar sesión de nuevo.
- Google web: popup oficial → ID token Firebase → mismo recorrido de perfil.
  Una identidad nueva con email verificado puede completar el registro.
- Recuperar acceso: PASSWORD_RESET enviado directamente a Firebase; el mensaje
  siempre es neutral respecto a si el correo existe.
- Verificar sesión: consultar /me nuevamente. Logout cancela trabajo y borra
  tokens locales, incluyendo el token temporal para reenviar verificación.

La verificación manual muestra «Verificando…», progreso y una confirmación
visible solo tras recibir un perfil válido. Si no hay membresías, aclara que la
sesión es válida pero falta asignar una organización. No crea empresas ni concede
roles. La confirmación se limpia al reintentar, fallar o salir y no se genera
desde el sondeo periódico; se conserva tras este solo si el perfil no cambió.

Crear una cuenta no crea organizaciones, membresías, locales ni roles elevados.
Un usuario inactivo nunca se reactiva mediante login/registro. No se fusionan
UID por coincidencia de correo. Los permisos salen de PostgreSQL.

## Arquitectura y UI

features/identity/domain declara los contratos `AccountAuthentication`,
`AccountRegistration`, `ProfileAccess` y `SessionLifecycle`. Cada caso de uso
depende de la capacidad que necesita. El adaptador implementa su composición
`IdentityRepository`; el dominio no contiene HTTP, DTO, SDK ni almacenamiento.

`FirebaseIdentityDataSource` administra los contratos de Firebase y la clave
pública; `BpmIdentityDataSource` interpreta perfil, alta mínima y rutas de sesión
de BPM. `RemoteIdentityRepository` coordina las credenciales, el alta pendiente,
la renovación y el almacenamiento por origen/UID. No interpreta DTO de los
proveedores ni construye solicitudes HTTP. AppGraph inyecta las fuentes y los
casos de uso explícitamente. La coordinación depende de los contratos internos
`FirebaseIdentityGateway` y `BpmIdentityGateway`, no de las clases de transporte.
`SessionStore` conserva los adaptadores cifrados.

presentation expone AuthMode/IdentityUiState y un ViewModel con casos de uso.
`SessionMonitor` concentra reloj, cuenta regresiva y actividad; su StateFlow
alimenta `SessionExpirationNotice`. La UI no consulta servicios directamente.

Estilo BPM lúdico profesional: petróleo/coral, superficies redondeadas,
selector Entrar/Crear cuenta, campos claros y acciones de mínimo 52 dp.
Usa ilustraciones originales empaquetadas con Compose, sin CDN visuales.
Al confirmar sesión se abre el nuevo workspace (WORKSPACE.md).

La pantalla usa dos paneles cuando dispone de al menos 900 dp de ancho y 600 dp
de alto. `IdentityWelcomePane` agrupa marca, presentación y mascota con ancho
de lectura limitado a 480 dp y centrado vertical. La ilustración se ajusta al
alto disponible entre 200 y 340 dp, respetando el ancho del panel; el contenido
puede desplazarse si aumenta el tamaño del texto. Ventanas estrechas o bajas
utilizan una columna. Es una composición de presentación sin red, estado de
negocio ni dependencias nuevas. No se añaden tarjetas ni promesas funcionales
para rellenar espacio. Referencia: [adaptación al espacio disponible en Compose](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-display-sizes).

El acceso habitual no ofrece «Comprobar conexión» ni añade un botón «Reintentar».
Ante un fallo de conexión se muestra un mensaje dentro del formulario y se libera
la acción existente para volver a iniciar sesión. No se reenvían credenciales
automáticamente ni se repite un alta de cuenta. Contraseña incorrecta y falta de
permisos conservan sus mensajes específicos. Las listas mantienen sus acciones
de recuperación propias; este ajuste no cambia sus contratos.
La ruta técnica `conexion` queda disponible para soporte por enlace directo;
su ViewModel solo se crea al abrirla y no consulta salud al mostrar el login.
Login y registro no muestran el aviso sobre asignación de empresa. Las cuentas
sin membresía reciben su explicación contextual dentro del workspace.

`GoogleSignInButton` pertenece a presentación de identidad. Usa superficie blanca,
texto oscuro, borde visible y el logotipo multicolor oficial sin tintado, con
descripción accesible en la etiqueta de la acción. Se empaquetan el subconjunto
oficial TrueType de Google Sans Medium para «Continuar con Google» (5 KiB) y su
licencia OFL. Si cambia esa etiqueta, debe regenerarse su subconjunto desde
`https://fonts.googleapis.com/css?family=Google+Sans:500&text=Continuar%20con%20Google`.
No se instalan fuentes en el sistema ni se solicitan logotipos o fuentes a
servicios externos al mostrar el botón. El bloqueo mientras existe
una solicitud pendiente conserva el contrato del ViewModel.
Fuentes: [guía de marca de Google](https://developers.google.com/identity/branding-guidelines),
[Google Sans](https://github.com/google/fonts/tree/main/ofl/googlesans) y
[manejo de errores de red](https://developer.android.com/topic/architecture/data-layer/offline-first#network-data-source).

### Google en Android

`MainActivity` compone `AndroidIdentityOwner` y adjunta la Activity visible a
`AndroidGoogleAuthProvider`. El propietario sobrevive a recreaciones; el proveedor
usa `MutableContextWrapper`, cancela el selector al destruir la Activity y retira
su referencia. No hay Activity estática ni service locator. `App`/`GraphOwner`/
`AppGraph` reciben el puerto existente; web conserva su adaptador de popup.

El botón compartido también aparece en Android. Se abre solo al pulsarlo mediante
`GetSignInWithGoogleOption`, con nonce aleatorio por intento; no se abre un popup
web, no se selecciona cuenta automáticamente y no se solicita acceso a Drive.
El adaptador nativo entrega una credencial federada efímera al repositorio.
`FirebaseIdentityDataSource` la canjea con `accounts:signInWithIdp`; únicamente el
ID token de Firebase se envía a `/me`. Un token Google no crea por sí solo una
sesión BPM. El servidor conserva verificación y permisos; un conflicto de cuenta
no vincula identidades automáticamente. Logout cancela resultados tardíos y
limpia el estado de Credential Manager antes del siguiente acceso.

Configuración pública requerida (sin contraseñas ni secretos OAuth):

El applicationId Android es `bpm.app`, alineado con el registro existente
suministrado de Firebase. `namespace` y paquetes Kotlin conservan `pe.bpm`;
el identificador iOS no cambia. El ID Web de tipo 3 suministrado ya está en
`config/firebase.public.json`. Los APK anteriores con `pe.bpm.app` constituyen
otra instalación: no se migran sus datos privados ni la sesión de Keystore.
El usuario inicia sesión en la app nueva con su cuenta existente; los datos
remotos y permisos no cambian. No desinstalar la app anterior automáticamente.

1. Usar la app Android **`bpm.app`** ya registrada en el proyecto Firebase;
   corresponde al applicationId. `pe.bpm` es el namespace del código.
2. Registrar las huellas SHA-1 y SHA-256 de las firmas debug/release que realmente
   se distribuyan. `./gradlew :composeApp:signingReport` muestra las de debug.
3. Habilitar Google en Authentication y usar el ID OAuth **de tipo Web** del mismo
   proyecto en `-PgoogleWebClientId=...apps.googleusercontent.com`, la variable de
   compilación `GOOGLE_WEB_CLIENT_ID`, o `googleWebClientId` en
   `config/firebase.public.json`. No confundirlo con el ID de cliente Android.
4. Recompilar e instalar el APK. Railway no configura ni distribuye el binario
   instalado en el emulador. El emulador requiere imagen Google Play y el proveedor
   actualizado; el selector permite añadir una cuenta si aún no hay ninguna.

Sin ID Web se ofrece un mensaje claro y correo/contraseña sigue disponible; no se
oculta el botón como ocurría con el adaptador anterior. Un build sin ese ID no
certifica Google operativo. No se añade Firebase Admin ni un segundo SDK de sesión.
El canje usa el cliente HTTP compartido; las credenciales no llegan al UiState ni
a logs. La configuración nativa no amplía `/config.json` ni las variables Railway.

El asistente de Firebase muestra la integración mediante SDK Android: su plugin
Google Services convierte `google-services.json` en recursos para esos SDKs y la
BoM alinea sus versiones. Esta aplicación mantiene el canje REST documentado de
Firebase para compartir sesión con KMP; Credential Manager solo obtiene la
credencial Google en Android. Por ello no necesita ese plugin, Firebase Auth SDK
ni Analytics para este recorrido. Si se incorpora un SDK de Firebase después,
configurarlo con el archivo del paquete correcto y revisar la BoM entonces.

Referencias: [Credential Manager](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation),
[Firebase Android](https://firebase.google.com/docs/auth/android/google-signin),
[configuración Android](https://firebase.google.com/docs/android/setup),
[canje REST de proveedor](https://firebase.google.com/docs/reference/rest/auth#section-sign-in-with-oauth-credential).

La pantalla permite mostrar/ocultar contraseña, confirmar contraseña, recuperar
acceso, reenviar verificación, consultar perfil y cerrar sesión. Las contraseñas
no usan rememberSaveable y se vacían al enviar o cambiar de modo.

Nombres y apellidos son obligatorios, admiten caracteres Unicode y nombres
compuestos. Se normalizan los espacios y se componen como `displayName` (hasta
100 caracteres) para el contrato actual; no se modifica el backend ni se afirma
que almacene ambos campos por separado. Los errores locales señalan `firstName`
y `lastName`. Un fallo de alta conserva los datos públicos del formulario y
ofrece acciones para entrar o recuperar acceso cuando corresponde.

La pantalla de verificación identifica el correo de destino, explica los dos
pasos y solo anuncia el envío tras una respuesta satisfactoria del proveedor.
Un fallo posterior al alta indica que la cuenta ya existe y ofrece reintentar,
sin repetir signUp. Si aún no se confirmó ningún envío, «Reintentar envío» es
la acción principal y no se indica que ya deba haber un correo en la bandeja.
La comprobación de un enlace anterior sigue disponible como acción secundaria.
Reenviar tiene una espera de 60 segundos tras éxito o límite
de intentos; esto mejora la experiencia y no reemplaza los límites de Firebase.
«Ya confirmé mi correo» renueva el token y deja la autorización al backend;
un correo no verificado no crea una sesión BPM. Las credenciales temporales
permanecen en memoria, se actualizan cuando rotan y se descartan al terminar,
salir o volver al login. No se conserva la contraseña para este paso.

## Contratos y errores

Todos los fallos se representan con errors[]: code, message y fields. Los fallos
de formulario señalan firstName, lastName, email, password y confirmPassword;
displayName se conserva para el contrato remoto. Nunca se
presentan excepciones, mensajes SQL, tokens o mensajes originales de Firebase.

Códigos importantes: VALIDATION_ERROR, INVALID_CREDENTIALS, EMAIL_NOT_VERIFIED,
PASSWORD_POLICY, REGISTRATION_REJECTED, REGISTRATION_CONFLICT, USER_INACTIVE,
AUTH_DOMAIN_NOT_AUTHORIZED, AUTH_NOT_CONFIGURED, RATE_LIMITED y NETWORK_ERROR.
Un popup cerrado no crea sesión. Solo un perfil autorizado con UID coincidente
permite entrar; nunca se simula éxito con una respuesta de salud.

AUTH_UNAVAILABLE identifica un 503 remoto: puede ser indisponibilidad temporal
de verificación o almacenamiento de sesión, y no prueba que falte configurar
Firebase. AUTH_NOT_CONFIGURED se reserva para configuración pública cliente
ausente. Al revalidar, un fallo transitorio conserva el perfil previo, libera
el botón y permite reintentar; no concede nuevos permisos ni genera un registro.

El puente abre el popup dentro del click y mantiene un bloqueo hasta terminar
la limpieza del SDK. Logout invalida resultados tardíos sin ejecutar signOut
concurrente con OAuth. GOOGLE_CANCELLED, GOOGLE_IN_PROGRESS,
GOOGLE_POPUP_BLOCKED, GOOGLE_NETWORK_ERROR y GOOGLE_UNAVAILABLE distinguen
cancelación, concurrencia, navegador, red y fallo del proveedor. La UI muestra
un mensaje en español con una acción concreta; los códigos tipados permanecen
internos y no se muestran en la pantalla de acceso.

`IdentityErrorPolicy` define una sola vez qué errores cierran una sesión,
permiten reintentar restauración o conservan un registro pendiente. La UI y
los adaptadores utilizan esas políticas sin volver a declarar listas de códigos.
Los fallos incluyen opcionalmente `IdentityDiagnostic`: etapa, estado HTTP
cuando está disponible y motivo sanitizado. No guarda correo, UID, contraseña,
tokens, URL, cuerpo ni mensajes originales del proveedor. No se añadió registro
de tráfico ni se exponen estos diagnósticos en el formulario.
Un registro parcial conserva el diagnóstico de la etapa fallida; así se distingue
actualizar el nombre de solicitar el correo sin volver a crear la cuenta.

Solo USER_NOT_REGISTERED explícito permite intentar el alta BPM. Un 403
desconocido se convierte en ACCESS_DENIED: no crea usuarios ni modifica permisos.
Un nombre Google vacío, demasiado corto/largo o con controles usa Usuario BPM
para el alta mínima; los nombres existentes nunca se sobrescriben.

Si Firebase ya creó la cuenta y falla actualizar el nombre o enviar el correo,
REGISTRATION_INCOMPLETE lleva a verificación y habilita reenvío;
REGISTRATION_RATE_LIMITED conserva además la espera antes de reenviar. El reintento
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

Prueba manual: crear cuenta propia con nombres y apellidos → abrir enlace del correo
→ volver a la app → pulsar «Ya confirmé mi correo»
→ comprobar perfil sin organizaciones → verificar sesión → cerrar sesión.
Probar además correo aún sin confirmar, reenvío fallido sin repetir el alta,
espera de reenvío, volver al login y entrar con una cuenta pendiente,
formulario vacío, contraseñas diferentes, popup cancelado y cuenta
inactiva. El titular introduce su contraseña y completa Google; no enviarlas al
equipo ni al chat. Las pruebas automatizadas no crean cuentas Firebase reales.

## Límites explícitos

La política de sesión coordinada con backend añade máximo absoluto de 8 horas
desde la autenticación Firebase y 15 minutos sin actividad. El servidor decide
con `auth_time` verificado y estado persistente; los tokens renovados no extienden
ese máximo. `GET /me` comprueba la sesión sin prolongarla. La UI notifica entradas
reales (incluidos formularios, desplazamiento y teclado virtual), con un POST
`/auth/activity` como máximo por minuto; no genera actividad desde temporizadores.
Los endpoints empresariales también actualizan actividad en servidor.

Las cabeceras `X-BPM-Session-Expires-At` y `X-BPM-Session-Idle-Expires-At` permiten
avisar durante el último minuto y retirar acceso local al vencer. El reloj del
cliente solo mejora la experiencia; el backend impide operaciones fuera de plazo.
Un 401 `SESSION_EXPIRED` borra también credenciales móviles y exige autenticación
nueva. No se reintentan escrituras. Las respuestas tardías no restauran sesión.

El aviso de inactividad muestra segundos restantes, «Seguir conectado» y
«Cerrar sesión». Continuar usa la misma operación de actividad real y solo
desaparece cuando el servidor confirma nuevas fechas. Un fallo conserva el
aviso y permite reintentar; las solicitudes fallidas no consumen el intervalo
de actividad exitosa. El temporizador nunca llama a actividad.
El límite absoluto muestra otra explicación y solo ofrece volver al login;
no puede prolongarse con el botón ni con entradas del usuario.

El aviso forma parte del árbol de UI, debajo de la cabecera y dentro del contenido
desplazable, con título anunciado y contador sin anuncios continuos. Su aparición
lleva el desplazamiento al inicio, sin mover la cabecera. Los botones se distribuyen
en varias líneas si falta espacio. Se compone también después del título de los
formularios abiertos mediante un slot, sin importar identity desde workspace.
No usa una nueva capa Popup. Tras vencer, la explicación en español se muestra
en el acceso, después de su título y antes del formulario; nunca se imprimen los
códigos `SESSION_EXPIRED` o `TOKEN_EXPIRED` en la UI.
Al vencer, se limpian credenciales y acceso local incluso si hay una solicitud
de actividad pendiente. No hay período de gracia que reabra una sesión vencida.

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
