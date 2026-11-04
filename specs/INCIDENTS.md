# Incidencias y acciones correctivas

`features/incidents` implementa dominio, transporte remoto, borrador local y UI KMP. La raíz
compone la selección de sede y conecta evidencia mediante callbacks; incidencias no importa
media ni workspace. No contiene sedes, cuentas, normas ni criterios de ejemplo.

## Acceso y recorrido

La sede es obligatoria para entrar en el recorrido. La raíz pasa el identificador y la zona
horaria real del workspace. ADMIN/SUPER_ADMIN configuran criterios y crean versiones
inmutables y supervisan. CHEF y WORKER reportan incidencias. El catálogo consultado muestra las
últimas versiones; cada incidencia conserva el criterio y la versión que confirmó el servidor.

La lista usa búsqueda de servidor con espera de 300 ms, filtro de estado, cursores opacos y
`PageNavigation`. WORKER recibe exclusivamente los hechos que el servidor le permite ver.
El detalle explica el siguiente paso y muestra solo `allowedActions`; oculta además la
validación de una corrección propia aunque una respuesta errónea la anuncie. El servidor
sigue siendo la autoridad de permisos, concurrencia y estados.

Asignar abre un selector de responsables con búsqueda y páginas reales. No se introducen
UUID manualmente. El plazo tiene campos etiquetados de fecha `yyyy-MM-dd` y hora `HH:mm`,
con la zona IANA de la sede visible. Cada plataforma convierte esa hora a un instante y
rechaza zonas desconocidas, fechas inválidas y horas ambiguas/inexistentes durante cambios
horarios. La revisión muestra fecha, hora y zona antes de enviar. Se exige un plazo futuro
al preparar un nuevo envío; recuperar un envío existente conserva su fecha original.

Los comandos disponibles son asignar, iniciar, registrar corrección, validar/cerrar y devolver.
La corrección y la revisión/devolución exigen explicación. El historial es paginado y muestra
actor, cambio de estado, nota y fecha confirmados; no expone hashes internos ni cuerpos HTTP.
Las acciones históricas múltiples se conservan en el detalle y el backend resuelve sus
conflictos; el cliente nunca las descarta ni las cierra automáticamente. Se conservan acciones
CANCELLED e incidencias VERIFIED históricas, sin imponer límites de lectura de fotos propios de una carga nueva.

## Borrador y recuperación

`IncidentEditor` concentra edición y envío, separado de las consultas de `IncidentsViewModel`.
Usa `DraftAutosave` y `JsonDraftDocument`, con documento por origen API, cuenta, organización
y sede. Primero reautoriza workspace y acceso de sede en el servidor; después lee el
borrador. Cambiar de contexto cancela consultas y borra los datos de pantalla; los escritores
anteriores terminan su cola y se esperan antes de recuperar un documento. No se guardan tokens.

La revisión genera UUID de operación, clave de idempotencia y JSON una vez. Se guarda el
formulario y ese cuerpo antes de cualquier POST. Si falla el almacenamiento, no envía ni
abandona la pantalla. Los cambios de campos se bloquean mientras hay un cuerpo pendiente.
Reintentar vuelve a autorizar y usa la misma clave y los mismos bytes JSON. Los rechazos definitivos 400/404/409/422 liberan el envío pendiente y conservan el formulario
editable; no se confunden con resultados inciertos ni se reenvía otra versión automáticamente.
La respuesta se
comprueba contra sede, identificadores, versión, contenido y transición esperada; los plazos
se comparan a microsegundos como el backend. Una respuesta no verificable conserva el envío.
La copia se elimina solo después de una respuesta verificada y un borrado local confirmado.

Guardar y volver conserva el formulario y ofrece Continuar borrador. Abandonar el módulo
espera el guardado. El envío pendiente se puede conservar al salir y recuperar al volver,
pero no se edita ni se descarta silenciosamente mientras el resultado sea incierto. No se afirma que un POST incierto falló ni
que una desconexión confirmó una incidencia. No hay inicio offline, cola de fotos durable,
notificaciones, constancias ni procesos de fondo en este módulo.

## Evidencia privada compatible

`EvidenceContext` conserva los tres primeros parámetros de controles y añade
`incidentId`, `actionId` y `EvidenceStage`. Solo acepta un destino completo:

- Control: `organizationId`, `runId`, `itemId`; sin campos de incidente.
- Antes: `organizationId`, `incidentId`, `BEFORE`; sin acción.
- Después: `organizationId`, `incidentId`, `actionId`, `AFTER`.

El transporte elige la ruta de lectura explícita y solo los campos de destino pertinentes
en POST media/uploads. La respuesta debe coincidir exactamente con incidente, acción y etapa.
Confirmación, descarga y borrado conservan endpoints y validaciones existentes. Se reutilizan
panel, selección de fotos, progreso, vista previa, confirmación de borrado y reintento
`confirmFirst`. Un resultado pendiente no se cuenta como foto disponible.

El callback de UI es `(incidentId, actionId?, stage, writable)`. Antes permite edición al
reportero o supervisor durante OPEN/ASSIGNED/IN_PROGRESS; después, solo al responsable con
incidencia y acción IN_PROGRESS. Estas condiciones guían la interfaz; el servidor reautoriza
cada acceso. La raíz debe recargar el detalle al regresar para actualizar conteos y permisos.

## Composición y comprobación

La raíz crea `RemoteIncidentsRepository`, `LocalIncidentDraftRepository`, `ManageIncidents`
y `IncidentsViewModel(manage, newId, now)`. Abre con
`open(userId, organizationId, role, siteId, timeZone)`. Observa `state` y `editor.state`.
`prepareBack` resuelve selector/formulario/historial/detalle; `prepareLeave` y `flush` esperan
el guardado al abandonar el módulo o cambiar contexto. `reset` limpia la memoria visible.
Para media llama `openIncident(user, org, incidentId, actionId, stage, canEdit)`; los controles
conservan `open(user, org, run, item, canEdit)`.

Las pruebas JVM cubren reglas, payload estricto, comparación temporal, respuestas cruzadas,
páginas, denegación, aislamiento cuenta/empresa/sede/origen, almacenamiento fallido,
recuperación del mismo cuerpo, autovalidación y zonas con cambio horario. Las pruebas de
media conservan el recorrido de controles y añaden destino incidente, cruce de etapa/acción
y reintento de confirmación. La publicación requiere el backend con V6 antes del frontend.

La aceptación local recorrió reporte, fotos y preview, asignación, inicio y
corrección por colaborador y validación por otra persona hasta CLOSED e historial.
Se verificó devolución accesible al detalle al cerrar fotos y anchos 320,390,768,1440.
Las descripciones, criterio y explicaciones usan el campo compartido en varias
líneas; el plazo conserva fecha/hora separadas y navegación IME. La zona se
proyecta desde workspace en web; no se envía como campo del comando de alta
de sede. Los DTO de lectura y escritura permanecen separados.

Verificación compartida final: 226 pruebas JVM y 67 pruebas Node pasan, sin
fallos ni pruebas omitidas. Se comprobaron web, APK Android y framework de
simulador iOS. La compilación no sustituye una prueba en móvil físico ni la
aceptación del proveedor R2 real. La activación requiere backend con V6 antes
de desplegar esta UI.
