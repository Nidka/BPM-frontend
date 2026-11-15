# Offline-first (fases 1 y 2)

Este apartado reemplaza el estado «pendiente» de las secciones siguientes en lo que cubre; la
recuperación de borradores y la cola de controles preparados descritas abajo siguen vigentes como
editor local.

## Diseño

- `core/offline` define los puertos: `LocalStore` (lectura local por colección/clave), `Outbox`
  (operaciones pendientes), `SyncEngine` y `ConnectivityMonitor`. Las features no conocen el driver.
- Persistencia (`OfflineDatabase`): SQLDelight en Android (driver Android), iOS (driver nativo) y JVM
  (JDBC, pruebas); en web, la misma API sobre IndexedDB mediante `DocumentOfflineDatabase`
  (mejor esfuerzo; no se usó el worker de SQLDelight para evitar OPFS/worker y empaquetado extra).
  Todo se separa por ámbito (origen API + cuenta); la empresa viaja en la ruta y en cada operación.
- `OfflineHttpEngine` envuelve el motor HTTP de la plataforma. Lecturas permitidas (lista explícita
  en `OfflineHttpPolicy`): se guardan y, sin red o ante fallo de conexión, se sirven de la caché con la
  marca de tiempo («Datos de hace X»). Escrituras permitidas (controles, recepciones, limpieza, stock,
  incidencias y acciones correctivas, atender/leer alertas): sin red o tras un fallo de conexión se
  registran en la Outbox con su Idempotency-Key (o clientOperationId/clave determinista) y el
  repositorio recibe un fallo de conexión. Nunca se guarda el token: el envío usa uno vigente.
- Nota: la lectura es «red primero con caché de respaldo» a nivel HTTP; `CachedResource` ofrece el
  patrón completo cache-then-network (emite lo local y refresca) para repositorios nuevos (fase 2).
- Orden y dependencias: una operación depende de las pendientes de la misma entidad (guardar →
  finalizar; alta → foto). Un registro con pendientes mantiene el orden aunque haya red.

## Estados y reglas

PENDING → SENDING → SENT; CONFLICT (409, requiere decisión); REJECTED (4xx de validación/permisos).
Red, 5xx, 408, 429: reintento con backoff exponencial (5 s, doblando, tope 15 min) hasta 12 intentos;
después queda en pausa hasta «Reintentar». 401 no consume intentos. Un cierre inesperado devuelve
SENDING a PENDING y se reenvía con la misma clave (el backend es idempotente). Los dependientes de
una operación fallida esperan; descartarla descarta también a los dependientes (con confirmación).
Conflicto: la interfaz ofrece «Descartar y recargar» o «Reintentar»; nunca se sobrescribe en silencio.

## Sincronización

Al recuperar red, al volver a primer plano, cada minuto con la app abierta y con «Sincronizar ahora».
Android: WorkManager (trabajo único, red conectada, backoff exponencial) envía con la app cerrada
restaurando la sesión guardada. iOS: sin planificador de fondo (requiere BGTaskScheduler en Xcode).
Web: solo con la pestaña abierta. La cola de controles preparados antigua se traslada a la Outbox
(`LegacyChecklistMigration`, mismas claves, sin duplicar); «finalizar» lee la versión vigente del
servidor justo antes de enviar.

## Sesión sin conexión y privacidad

Si hubo una sesión válida, la app abre sin red con el último perfil verificado (máximo 14 días); al
volver la red se renueva el token y se revalida en /me. Un rechazo del servidor nunca se rescata con
la caché. El primer inicio siempre requiere red. Cierre de sesión explícito o cambio de cuenta borran (salvo particiones con pendientes, ver «Celular compartido»)
base, archivos personales y borradores; vencimiento de sesión solo oculta. Cierre de sesión con pendientes: ver «Celular compartido».
Las acciones que requieren servidor se agrupan en `ServerOnly` (fase 2): sin conexión muestran
`OfflineRequiredNotice` y `RaisedButton`/`BpmSecondaryButton` se deshabilitan solos (`LocalServerOnlyActions`).

## Fotos

`OfflineMediaWriter` copia la foto a almacenamiento privado y la encola; `PhotoOutboxHandler` hace
reserva → PUT firmado → confirmación cuando existe el registro padre. La URL firmada no se guarda.
Una foto pendiente no cuenta como evidencia confirmada. En web no hay archivos durables.

## Límites de almacenamiento

Respuestas cacheadas ≤ 400 KB cada una; operaciones ≤ 600 KB; web: documentos ≤ 1 MiB. Se conservan
las últimas 30 operaciones enviadas. Fotos huérfanas por descarte se borran al cerrar sesión.

## Fase 2: capacitación, encuesta CAP y cierre de brechas

### Lectura local primero
`readLocalFirst` (`LocalFirstReader.Cached`) emite de inmediato la copia local y luego refresca: la primera
lectura corre con `LocalFirstMode`, un plugin del cliente (`LocalFirstPlugin`) añade la cabecera interna
`X-BPM-Cache-Only` y `OfflineHttpEngine` responde solo de la base (sin copia: `OfflineCacheMissException`). La segunda
lectura es la normal (red primero, caché de respaldo). Es el equivalente de `CachedResource` para los repositorios que ya
leen por HTTP. Pantallas: inicio/panel y organizaciones, catálogo y controles programados, operación diaria, incidencias,
alertas, «Mis capacitaciones» y la encuesta CAP. Las ViewModels reciben `localFirst` (por defecto `Direct`, una sola lectura);
`AppGraph` pasa `Cached`. `CachedResource` sigue disponible para recursos nuevos con serializador propio.

### Escrituras encoladas: éxito local pendiente
Cuando el motor encola una escritura, el repositorio devuelve el motivo `QUEUED` (`OfflineQueuedException` → `TrainingFailure`,
`ResearchFailure`, `AlertsFailure`, `IncidentsFailure`, `OperationsFailure`, `RunFailure`) y la ViewModel lo trata como éxito
guardado: cierra el formulario, descarta el borrador y avisa «Guardado en este dispositivo. Se enviará cuando recuperes la
conexión». Las listas y detalles marcan «Pendiente de enviar» (`PendingBadge`, por id de registro) o muestran una sección
`PendingOperationsNotice` (altas que todavía no existen en el servidor). Los datos salen de `PendingMarks`
(`LocalPendingMarks`), derivados de la Outbox. Las alertas vistas/atendidas se ven ya aplicadas con la marca. Los controles de
checklist conservan su flujo propio («Trabajar sin conexión»); un `QUEUED` en ese cliente solo cambia el mensaje.

### Tiempos de espera
Una escritura permitida cuyo `HttpTimeout` vence se encola igual que un corte de red (el motor distingue la cancelación por
timeout de la del usuario con `isTimeout`). Una lectura que vence se repite una vez contra la caché (`LocalFirstPlugin`,
instalado después de `HttpTimeout`).

### Archivos grandes y descargas
`PrivateFileStore` añade escritura por bloques: parte provisional (`appendPart`/`readPart`/`truncatePart`), publicación atómica
(`commitPart`), `list`, `size` y `localUri` (`file://` para el reproductor). Android usa archivos `*.part` sin copia de
seguridad; iOS `NSFileHandle` con protección completa; el navegador no admite archivos durables (`supported=false`).
`VerifiedDownloader` transfiere en streaming (64 KiB), verifica tamaño y SHA-256 incremental, reanuda con `Range` (rehaciendo el
hash de lo ya escrito), reinicia si el origen ignora `Range`, repite una sola vez una descarga corrupta y pide una URL nueva en
cada intento (nunca se guarda). Los videos viven en el almacén del dispositivo (`OfflinePlatform.videos`), no en el personal:
cerrar sesión o cambiar de cuenta no los borra (ver «Celular compartido»).

### Registro de respuestas por Outbox
`HttpOutboxHandler` acepta un gancho `onResponse` (calificaciones de evaluaciones) y trata un 409 `RESEARCH_SUBMISSION_EXISTS` en
`research/submissions` como ENVIADA. `OfflineHttpPolicy` añade lecturas de `training` (salvo `offline-catalog`, videos y borradores) y
`research/participation`, y escrituras `training/courses/{id}/progress` (clave generada), `.../attempts` y `research/submissions`.

## Celular compartido

Varias personas pueden usar el mismo celular, así que el cierre de sesión protege lo que aún no llegó al servidor y separa lo
personal de lo que es del dispositivo.

### Cierre de sesión con pendientes
`LogoutPolicy` (`core/offline`) evalúa la Outbox de la cuenta activa y `OfflineUiState.logout` expone el resultado:

- `Clear`: no queda nada sin enviar; «Cerrar sesión» borra los datos personales (base, archivos personales, borradores, trabajo en
  segundo plano) como antes.
- `Blocked(n)`: hay `n` operaciones PENDING o SENDING (también las que agotaron reintentos). La app **no deja salir** y muestra
  «Tienes N cambios sin enviar. Conéctate y sincroniza antes de salir» con «Sincronizar ahora» (y «Ver pendientes» para
  «Reintentar»). Cuando todo se envía, el mismo cuadro ofrece cerrar sesión. Los dependientes de una operación en conflicto o
  rechazada esperan y no bloquean por sí solos: se descartan con ella.
- `NeedsDiscard`: solo quedan operaciones en CONFLICTO o RECHAZADAS. Se pueden descartar de forma explícita con
  «Descartar y cerrar sesión» (confirmación que dice cuántas se pierden). `OfflineViewModel.discardAll` vuelve a evaluar antes de
  salir: si apareció algo por enviar, no cierra.

El vencimiento de sesión no borra nada (solo oculta).

### Cambio de cuenta con pendientes (celular compartido)
La base local está particionada por cuenta (origen + usuario). Al iniciar sesión con otra cuenta o al cerrar sesión de forma explícita:

- La partición de otra cuenta **se conserva intacta** mientras tenga cambios sin enviar (PENDING, SENDING, CONFLICT o REJECTED) y
  hayan pasado como máximo **30 días** desde su última actividad (`OfflinePolicy.OTHER_ACCOUNT_RETENTION_MS`, medido con el
  `updatedAt`/`clientCreatedAt` más reciente de sus operaciones sin resolver). Pasado ese plazo se borra; si no tiene nada
  pendiente se borra de inmediato (`OfflineRuntime.activate`/`wipe` → `prunePartitions`).
- La cuenta nueva nunca ve ni envía esos datos: `LocalStore`, `Outbox` y el motor de envío trabajan solo con la partición activa.
- Cuando la cuenta anterior vuelve a entrar en el dispositivo, su cola se reanuda y se sincroniza con sus mismas claves.
- Los archivos personales (fotos pendientes) no están separados por cuenta: solo se borran cuando ninguna partición se conserva.
- Al iniciar sesión, si hay pendientes de otra cuenta se muestra un aviso discreto con el recuento y el correo enmascarado:
  «Hay N cambios sin enviar de otra persona (ka•••@gmail.com). Se enviarán cuando esa persona vuelva a entrar»
  (`OfflineRuntime.otherPending`, `OtherAccountNotice`). No incluye otros datos personales.
- El cierre de sesión explícito con pendientes de la cuenta activa sigue bloqueado (ver arriba).
- Android: `OfflineSyncWorker` restaura solo la sesión guardada y envía únicamente la partición de esa cuenta; sin sesión activa
  termina sin enviar nada (`AppGraph.syncInBackground`).

### Videos descargados (almacén del dispositivo)
- `OfflinePlatform.videos` es un `PrivateFileStore` aparte (Android `noBackupFilesDir/device-videos`, iOS `bpm-device-videos-v1`
  con protección completa y fuera de la copia de seguridad). `OfflineRuntime.wipe()` y `activate()` solo vacían `files`.
- Cada archivo se llama `video-<mediaId>-<16 hex del SHA-256>.<ext>`: se identifica por contenido, no por cuenta.
- Un usuario solo ve y reproduce un video local si **su propio catálogo, ya cargado** (`training-catalog`), incluye ese `mediaId`
  con el mismo SHA-256 (`TrainingDownloads.bind`). Sin catálogo cargado, o con otro SHA-256, se usa la URL firmada o nada. El espacio
  usado cuenta todo el dispositivo.
- Se borra con «Borrar descargas» (vacía el almacén, también los videos de otras cuentas) y por el límite de espacio: antes de
  rechazar una descarga se liberan los videos que no son del catálogo de la cuenta activa, los más grandes primero.
- Migración: al asociar el catálogo, los `video-<mediaId>.<ext>` del almacén personal cuyo `mediaId` y tamaño coinciden con el
  catálogo se mueven (`PrivateFileStore.moveTo`, sin cargarlos en memoria) al almacén de videos; los demás se descartan.
- Web: los videos sin conexión siguen deshabilitados (no hay archivos durables; `supported=false`).

## Verificación manual pendiente

Android/iOS no se compilaron en este entorno: revisar `AndroidOffline.kt`, `AppleOffline.kt`
(NWPathMonitor y `NSFileHandle` sin verificar), el Worker y la reproducción de `file://` en dispositivo. Del celular compartido:
`moveTo` en Android/iOS (migración), la carpeta `device-videos` y que cerrar sesión conserve los videos.


# Recuperación local y trabajo sin conexión

## Disponible en esta entrega

Hay copias persistentes de los formularios de organización/sede/equipo, de la
autoría de una plantilla y de las respuestas sin enviar de un control en curso.
Un escritor serial agrupa cambios rápidos; la UI distingue guardando, guardado y
fallo. Solo anuncia guardado después de terminar la escritura local. Si el proceso
muere antes de esa confirmación, la última edición puede no haberse conservado.

La copia se separa por origen API (en web, origen del proxy), cuenta, organización
y clase de documento. Crear organización usa el contexto de plataforma del mismo
usuario. La recuperación inicial conserva una autoría y un control pendiente por cuenta/empresa.
Además, la colección de controles preparados permite conservar varias revisiones
propias, con sus envíos independientes, como se describe a continuación. La sede y la versión
forman parte de la copia del control, sin cambiarse durante un reintento.

El cierre de sesión, el vencimiento y el cambio de empresa limpian el estado de
pantalla y cancelan las solicitudes, pero terminan la escritura local ya encolada.
Las copias quedan disponibles para la misma cuenta después de autenticar y
revalidar sus permisos. Otra cuenta no recibe esos datos en la UI. Descartar pide
confirmación y elimina la copia; publicar o guardar con éxito retira la copia
pendiente de ese documento. No se persisten contraseñas, tokens ni autorizaciones
en estos documentos. Las credenciales móviles siguen su almacenamiento separado.

En controles se consulta el registro autorizado antes de revelar la copia tras un
reinicio. Una denegación no expone sus respuestas. Si cambió rowVersion se conserva
la edición y se bloquea el envío: cargar la versión del servidor exige confirmar
el descarte. Un fallo de esa consulta no elimina la copia.

Antes de iniciar, guardar o finalizar se hace duradera la operación propuesta:
UUID, sede, versión de plantilla, fecha UTC, clientOperationId o Idempotency-Key,
rowVersion y cuerpo exacto. Tras perder una respuesta o reiniciar, se ofrece
comprobar esa misma operación. Recuperar nunca vuelve a enviarla automáticamente.
La publicación conserva los IDs y el contenido originales; su transporte confirma
por lectura y no presupone un POST idempotente. Un fallo de almacenamiento local
bloquea nuevos envíos que quedarían sin posibilidad de recuperación.

## Responsabilidades y adaptadores

- Dominio: modelos puros, puertos específicos de borradores y casos de uso en
  workspace/checklists. No importa JSON, Compose ni almacenamiento.
- Data de cada feature: DTO, validación del documento y repositorios locales.
- `core/storage`: almacenamiento atómico y envoltura JSON con esquema/propietario.
  No conoce funcionalidades ni permisos.
- `core/presentation/DraftAutosave`: coordina cambios consecutivos de un documento;
  termina lo encolado al cerrar su vista y comunica fallos, sin hacer HTTP.
- `core/designsystem/DraftSaveNotice` y `DiscardDraftConfirmation`: presentación
  reutilizada, sin guardar datos ni navegar.
- AppGraph: inyecta adaptadores desde cada host. No hay estado global de borradores.

Android utiliza AtomicFile privado sin backup, AES/GCM y Android Keystore; la clave
del documento es también dato autenticado del cifrado. iOS usa Application Support
privado, escritura atómica, protección completa de archivos y exclusión de backup.
Web usa IndexedDB del origen y confirma al completarse la transacción, sin almacenar
tokens. Borrar datos del sitio, desinstalar o las políticas del navegador pueden
eliminar esas copias. IndexedDB no sustituye la protección frente a XSS ni es una
copia cifrada vinculada al inicio de sesión del usuario.

Los documentos están versionados y acotados a 1 MiB. Un esquema desconocido, archivo
dañado, cuota agotada o almacenamiento bloqueado se comunica; no se sobrescribe
silenciosamente. Son documentos pequeños y completos que se reemplazan de forma
atómica; no se agregó una base relacional para estos checkpoints sin consultas.

## Controles preparados y cola de envío

El CHEF inicia la revisión con conexión y elige **Trabajar sin conexión** antes de
perderla. La API vuelve a autorizar la lectura del borrador propio. La copia
incluye preguntas y criterios inmutables, sede, responsable, versión y respuestas.
Desde **Control → Controles en este dispositivo** se pueden conservar y responder
varias revisiones. La edición usa el mismo componente, reglas de entrada y
navegación del teclado que el formulario conectado.

El usuario puede enviar un borrador o revisar y finalizar. Antes de hacer HTTP
se conservan respuestas, versión y claves inmutables. El sincronizador guarda
primero las respuestas y conserva esa confirmación antes de finalizar. Si se
pierde una respuesta o falla un checkpoint local, repite la misma petición, no
crea claves nuevas. El resultado y el porcentaje solo se muestran como confirmados
cuando responde la API y termina su escritura local.

La cola se procesa en primer plano al abrir una sesión autorizada, al volver a
la aplicación y después de encolar una revisión. Cada tanda realiza hasta tres
intentos con pausas de 5 y 20 segundos. Tras agotarlos, «Enviar pendientes» permite
otra tanda; no hay sondeo permanente ni renovación artificial de la actividad.
Los errores de permisos, formato o concurrencia quedan para revisión. Cargar la
versión del servidor exige confirmar la pérdida de la edición local; si falla la
consulta, no se reemplaza la copia. Una operación incierta no se puede quitar.

Cada nueva sesión revalida la lectura de cada revisión antes de revelar sus
respuestas. Las copias que no pueden autorizarse se conservan sin mostrarlas.
Cerrar sesión/cambiar de empresa cancela HTTP y limpia la pantalla; otra cuenta
no recibe respuestas, títulos ni observaciones. La sesión debe seguir vigente:
no se habilita autenticación offline ni se prolonga el acceso por un temporizador
de sincronización.

`ChecklistOfflineRepository` expresa las operaciones locales de checklists.
`ManageOfflineChecklists` coordina preparación, edición y propuestas;
`SyncOfflineChecklists` conserva el orden de guardado/finalización.
`LocalChecklistOfflineRepository` implementa una colección atómica por origen,
cuenta y empresa. `JsonDraftDocument.update` usa comparación/reemplazo atómicos:
IndexedDB lo realiza dentro de una transacción readwrite, también entre pestañas;
los hosts nativos lo serializan mediante el adaptador único de AppGraph.
Ediciones de preguntas distintas se combinan; dos cambios incompatibles en la
misma respuesta detienen el guardado y exigen confirmar la carga de la copia
local. Tampoco se encola una respuesta diferente de la que el usuario revisó.
La colección admite hasta 20 revisiones dentro del límite de documento de 1 MiB.
Si se alcanza ese límite o la cuota de disco, no se reemplaza el contenido previo
ni se confirma un envío nuevo. Quitar copias ya confirmadas libera espacio.

## Lo que continúa pendiente

- Iniciar revisiones nuevas sin red a partir de asignaciones/versiones cacheadas;
  esta entrega prepara revisiones ya iniciadas en el servidor. No cambia sus
  períodos ni el historial a partir del reloj del cliente.
- Registro de dispositivos/deviceId y ejecución en segundo plano con planificadores
  nativos. Esta cola se procesa mientras la aplicación está abierta.
- Sincronización de otras funcionalidades y archivos R2 privados después de los
  datos estructurados.
- Service worker y garantía de arranque web offline con limpieza/actualización de
  recursos. Recuperar la sesión después de reiniciar todavía requiere red.
- Una base local consultable para catálogos grandes; la colección actual es pequeña
  y acotada, no descarga toda la organización ni simula un catálogo local completo.

La recuperación simple sigue comprobando su operación manualmente, sin reenviarla
automáticamente. Solo una propuesta explícita en la nueva cola habilita los
reintentos automáticos. No se publican plantillas ni altas administrativas sin
confirmación del usuario.

## Comprobación manual

1. En una organización de prueba, abrir Crear plantilla y usar «Control de apertura».
2. Esperar «Borrador guardado en este dispositivo», recargar y entrar con la misma
   cuenta. Deben recuperarse nombre, preguntas, orden y tipos, sin publicar.
3. Salir y entrar con otra cuenta: no debe aparecer el contenido de la anterior.
4. En un control iniciado, escribir una observación, esperar su copia local y
   recargar. Tras autorizar la lectura se recuperan las respuestas sin enviarlas.
5. Si otra sesión guardó una versión distinta, verificar el conflicto y que la
   edición no se sobrescribe. Un reintento de lectura fallido tampoco la elimina.
6. En una sede/equipo sin guardar, volver a entrar con la misma cuenta y abrir el
   mismo formulario. Debe conservar valores e identificador; Guardar confirma API.
7. Dejar de interactuar: el último minuto muestra aviso modal, cuenta regresiva y
   «Seguir conectado». Solo una respuesta válida del servidor amplía el plazo.
   Al vencer, se exige entrar de nuevo y se recupera únicamente lo conservado.

Las pruebas compartidas simulan pérdida de respuesta, reinicio, fallo de disco,
conflicto y aislamiento por cuenta/empresa/origen. La prueba iOS escribe y recupera
un documento protegido con dos instancias del adaptador. Las pruebas web usan
IndexedDB real y API local simulada, sin modificar datos de producción.

Referencias: [estado y almacenamiento Android](https://developer.android.com/topic/libraries/architecture/saving-states),
[criptografía Android](https://developer.android.com/privacy-and-security/cryptography),
[protección de archivos Apple](https://developer.apple.com/documentation/foundation/nsdata/writingoptions/completefileprotection),
[transacciones IndexedDB](https://developer.mozilla.org/en-US/docs/Web/API/IDBTransaction/complete_event),
[WCAG 2.2.5, nivel AAA](https://www.w3.org/WAI/WCAG22/Understanding/re-authenticating.html).

### Propuestas de programación

La programación de controles conserva una propuesta por plantilla y contexto
(usuario/organización/origen), sin tokens. IDs, sede, frecuencia, hora y estado
pendiente se hacen duraderos antes de crear. Restaurar solo lee; comprobar un
resultado incierto consulta el mismo ID y, si el servidor confirma que falta,
reenvía exactamente la propuesta. No es una cola múltiple ni un planificador
local. Un inicio de revisión programada persiste también assignmentId; reiniciar
no convierte el intento en una revisión libre ni cambia su período.

## Comprobación de varios controles

1. Con un CHEF de una empresa de prueba, iniciar cinco revisiones permitidas y
   preparar cada una con «Trabajar sin conexión».
2. Abrir Control → Controles en este dispositivo, desactivar la conexión y
   responder las cinco. Esperar la confirmación local de cada edición.
3. Revisar/finalizar cada una: debe verse pendiente, sin porcentaje inventado.
4. Cerrar y volver a abrir la aplicación; recuperar sesión con conexión y
   verificar que solo se muestran las revisiones nuevamente autorizadas.
5. Enviar pendientes y confirmar cinco resultados, sin duplicados ni cambios de
   sede. Provocar pérdida de respuesta y comprobar reutilización de claves.
6. Modificar una revisión desde otra sesión: el conflicto conserva respuestas;
   cargar la versión del servidor pide confirmación y no modifica la API.
7. Entrar con otra cuenta o con permiso revocado: ninguna respuesta local ajena
   debe mostrarse. No borrar los datos del dispositivo durante esta prueba.

Referencia adicional: [arquitectura offline Android](https://developer.android.com/topic/architecture/data-layer/offline-first).

## Fotos de controles

La carga privada de fotos está integrada, con conexión, en [MEDIA.md](MEDIA.md).
Los bytes de fotos no forman parte de esta cola local. Los recuentos confirmados
por pregunta sí acompañan la revisión; finalizar siempre revalida en el backend.

## Formularios de operación diaria

`features/operations` reutiliza `DraftStorage`, `JsonDraftDocument` y `DraftAutosave`.
La clave incluye origen, cuenta, empresa, recurso y sede. El formulario vuelve a
autorizar acceso antes de leer el documento. El cuerpo pendiente se limita a 512 KiB
en UTF-8, igual que la API y el proxy, incluyendo texto Unicode. UUID, clave y JSON
se guardan antes de HTTP y permanecen idénticos ante respuesta incierta. La salida
espera flush; el cierre confirmado borra el documento sin volver a crearlo.

Esto protege un formulario y un envío en duda. No añade una cola de nuevas
recepciones/limpiezas ni confirma registros sin conexión. El detalle del contrato
y de las respuestas comparadas está en OPERATIONS.md.

## Editor de incidencias

El editor separado reutiliza DraftAutosave/JsonDraftDocument. Reautoriza sede y,
para acciones recuperadas, el detalle concreto antes de mostrar una copia local.
UUID, clave y cuerpo se confirman en disco antes de HTTP. Solo los resultados
inciertos conservan un cuerpo pendiente inmutable. Rechazos definitivos 400,404,
409 y422 liberan ese pendiente y conservan los campos para corregir o descartar
explícitamente; un conflicto no ajusta la versión ni reenvía automáticamente.
La salida espera flush y un fallo local conserva la pantalla. Esto es recuperación
de un formulario y su operación; no una cola offline de incidencias nuevas.
