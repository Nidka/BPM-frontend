# Recuperación local y trabajo sin conexión

## Disponible en esta entrega

Hay copias persistentes de los formularios de organización/sede/equipo, de la
autoría de una plantilla y de las respuestas sin enviar de un control en curso.
Un escritor serial agrupa cambios rápidos; la UI distingue guardando, guardado y
fallo. Solo anuncia guardado después de terminar la escritura local. Si el proceso
muere antes de esa confirmación, la última edición puede no haberse conservado.

La copia se separa por origen API (en web, origen del proxy), cuenta, organización
y clase de documento. Crear organización usa el contexto de plataforma del mismo
usuario. Esta fase conserva una autoría y un control pendiente por cuenta/empresa;
no implementa un historial local de múltiples ejecuciones. La sede y la versión
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

## Lo que continúa pendiente

Esto protege edición local y reintentos, pero no completa el contrato offline del
piloto. Iniciar requiere una versión y sede autorizadas en línea. La recuperación
tras un nuevo acceso requiere red; no hay autenticación offline. Los borradores de
servidor ya confirmados pueden consultarse por su ruta y por el historial paginado
autorizado. No hay service worker ni garantía de arranque web sin red.

La siguiente fase debe incorporar:

1. Catálogos/versiones y registros consultables desde almacenamiento local por Flow.
2. Cola de múltiples operaciones, deviceId y estados pendiente/sincronizando/
   sincronizado/requiere atención, con sincronizador y señal de conectividad.
3. Reanudación autenticada, reintentos acotados y conflictos explícitos; nunca
   sobrescribir cambios ajenos o repetir una finalización con otra clave.
4. Archivos locales y cargas R2 privadas después de datos estructurados, sin
   persistir URL firmada como referencia estable.
5. Cachear bundle/recursos web con service worker y comprobar arranque sin red,
   actualizaciones y limpieza por cuenta/dispositivo.

SQLDelight móvil e IndexedDB web siguen siendo opciones para la futura cola y sus
consultas. No se introducen interfaces vacías de sincronización ni se anuncia
éxito remoto mientras solo exista una copia local.

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
