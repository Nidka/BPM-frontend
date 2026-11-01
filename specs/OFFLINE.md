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
