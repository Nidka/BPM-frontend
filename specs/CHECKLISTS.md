# Catálogo y detalle de controles

## Alcance de esta entrega

Primer incremento del recorrido de checklist previsto en el plan del proyecto,
relacionado con RF-12 a RF-17. Implementa consulta de plantillas publicadas,
búsqueda por nombre/área/descripción y detalle de preguntas. Incluye autoría y
publicación de primera versión, e inicio, respuestas y finalización en línea.
Evidencias, exportación del historial y sincronización offline siguen pendientes; RF-12
a RF-17 aún no se consideran completos para el piloto.

El diseño sigue el prototipo BPM Aula de Requerimientos: petróleo/coral,
superficies redondeadas y jerarquía de área, plantilla y criterio. Se reutilizan
los recursos y componentes originales ya empaquetados. No se copian resultados,
criterios sanitarios ni registros de demostración del prototipo.

## Recorrido y presentación

1. El perfil y workspace confirman la empresa activa y el rol.
2. Para un contexto con acceso a Control se carga el catálogo autorizado.
3. Control muestra tarjetas con área, nombre, descripción, versión y número de
   preguntas/obligatorias. Los conteos corresponden a la lista cargada.
4. La búsqueda consulta el servidor tras 300 ms y cancela la anterior; no persiste datos.
5. «Ver preguntas» abre el detalle de la versión publicada en su propia ruta. Presenta
   preguntas ordenadas, obligatoriedad, tipo de respuesta, unidad, rango y
   requisito de evidencia cuando exista. No permite registrar respuestas.
6. La flecha de la cabecera o Atrás del sistema regresan a la lista. La ruta es
   `#control/plantilla/{templateId}`: permite historial y enlaces directos. El ID
   identifica una plantilla, sin incluir usuario, empresa ni credenciales. Abrir
   un enlace directo exige sesión, rol y consulta autorizada del detalle en la empresa
   activa; una plantilla ajena o retirada muestra un estado no disponible.
   La solicitud del detalle no depende de la página cargada del catálogo.

Se distinguen carga, catálogo vacío, búsqueda sin coincidencias y fallo. El fallo
permite reintentar la consulta afectada; la entrada a Control también revalida
los datos cuando han perdido vigencia. Una recarga retira datos anteriores hasta confirmar la nueva lectura.
La búsqueda se conserva al reintentar o volver del detalle en el mismo contexto
y se limpia al cambiar usuario/empresa o cerrar sesión.

El contenedor conserva navegación lateral desde 950 dp e inferior por debajo.
Las tarjetas usan dos columnas desde 720 dp de contenido y una en tamaños menores;
el detalle ocupa el ancho del contenido y comparte el desplazamiento del
workspace, con una única acción de regreso en la cabecera. Los enlaces directos usan el catálogo como destino de respaldo.
Campos y acciones mantienen etiquetas textuales; los títulos de preguntas tienen
semántica de encabezado. Ambas pantallas usan el registro de actividad real
existente en la raíz de la aplicación.

## Responsabilidades

```text
features/checklists/
  domain/ChecklistCatalog.kt                 modelos, resultado, puerto y caso de uso
  data/RemoteChecklistCatalogRepository.kt   DTO privados, HTTP y traducción segura
  presentation/ChecklistCatalogViewModel.kt  StateFlow, consulta y búsqueda
  presentation/ChecklistCatalogScreen.kt     catálogo y detalle sin acceso a red
```

Se mantienen Clean Architecture, Repository, MVVM y flujo unidireccional.
`AppGraph` inyecta el caso de uso y comparte cliente/proveedor de token vigente,
observación de ventanas de sesión e invalidación del token utilizado. No añade
frameworks, dependencias ni acceso a Firebase desde esta funcionalidad.

`App` coordina contexto y funcionalidades; `WorkspaceScreen` recibe un slot
Compose para Control y no importa checklists. La presentación solo recibe estado
y callbacks. Dominio no conoce Ktor, DTO, Compose, identidad ni almacenamiento.
Los importadores se verifican con `scripts/architecture.test.mjs`.

## Contrato consumido y seguridad

Operaciones de lectura con Bearer: catálogo `GET /api/v1/organizations/{organizationID}/checklists`
y detalle `GET /api/v1/organizations/{organizationID}/checklists/{templateID}`.
Android/iOS utilizan el origen HTTPS configurado; web usa el mismo recurso bajo
`/backend`, mediante `web/checklists-proxy.mjs`. El backend existente conserva la
autorización por usuario y empresa. La API incorpora páginas y detalle; no requiere migraciones.

La respuesta `{data: [...], pagination: {nextPageToken: ""}}` contiene `id`, `name`, `description`, `area`,
`versionId`, `versionNumber` e `items`. Cada ítem usa `id`, `sectionName`, `label`,
`responseType`, `displayOrder`, `required` y opcionalmente `unit`, `minimumValue`,
`maximumValue`, `evidenceRequiredOnFailure`. Se aceptan COMPLIANCE, TEXT, NUMBER
y TEMPERATURE. Los rangos numéricos deben ser finitos y ordenados; nunca se
deducen límites sanitarios universales. Los tipos desconocidos o datos malformados
producen un fallo explícito, sin presentar un catálogo parcialmente interpretado.

El catálogo se solicita de 25 en 25, con un máximo de 100 plantillas por página
y 100 preguntas por plantilla. El servidor selecciona plantillas completas antes
de unir sus preguntas. El proxy limita además la respuesta a
4 MiB; una respuesta mayor necesita revisar el contrato antes de aumentar el límite.

El proxy solo habilita GET en las dos rutas explícitas. El catálogo acepta
`pageSize`, `pageToken` y `query`, acotados y sin repetición; el detalle no admite query. No permite
redirecciones, destinos dinámicos, cookies ni cabeceras de rol/empresa. Reenvía
solo campos públicos y las cabeceras numéricas de sesión existentes. Un 401
invalida el token utilizado; los mensajes originales de errores no se exponen.
403, 404, 429, fallo de red, servicio no disponible y respuesta inválida se
traducen a resultados tipados y mensajes seguros.

El estado en memoria se identifica por usuario y empresa. Cambiar contexto o
salir cancela consultas y limpia datos y búsqueda. La navegación abandona el detalle.
La raíz no muestra
un estado cuyo contexto no coincide, incluso antes de ejecutar sus efectos.
Antes de aplicar cada respuesta se comprueba cancelación; una respuesta tardía
no puede recuperar un contexto anterior.

Control sigue disponible para ADMIN y CHEF según la política visual existente.
SUPER_ADMIN conserva su recorrido de gestión y WORKER su aprendizaje. Esta
decisión de navegación no cambia los permisos de la API.

## Verificación y límites

Pruebas compartidas: sesión requerida, contrato GET, mapeo de los cuatro tipos,
criterios publicados y orden, catálogo vacío, errores sanitizados, datos inválidos,
propagación de cancelación, búsqueda/reintento y aislamiento ante cambio
de usuario/empresa y respuestas tardías. Node comprueba ruta cerrada, campos y
cabeceras permitidos, permisos rechazados y respuesta inválida.

Comandos de verificación: los de `DEVELOPMENT.md`, incluyendo JVM, distribución
web, APK Android, framework iOS y pruebas Node/fronteras.

Prueba funcional con una cuenta autorizada: abrir Control, buscar por área,
abrir preguntas y volver con el botón o historial, reducir el ancho, cambiar
empresa y cerrar sesión. Probar un enlace directo y una plantilla no disponible.
Comprobar catálogo vacío y desconexión sin convertirlos en éxito ficticio.
Una empresa necesita plantillas publicadas para mostrar contenido; el frontend
no crea datos de demostración en producción.

La revisión en Chrome con API e identidad simuladas cubre enlace directo tras
login, búsqueda, detalle, Atrás/Adelante, ancho de 390 px, error/reintento,
catálogo vacío, cambio de empresa, plantilla ajena y logout con historial
protegido. No sustituye probar una cuenta real autorizada ni una auditoría
completa con lectores de pantalla.

Se usan una ruta de detalle y un selector de organización integrado en el
contenido para conservar el árbol de accesibilidad al volver o cambiar empresa.
La revisión en navegador detectó que las capas emergentes anteriores conservaban
semántica de la capa cerrada. No se actualizaron dependencias ni se añadieron
parches JavaScript al DOM para resolver ese recorrido.

Esta entrega requiere internet. Los borradores se guardan en el servidor mediante
la acción explícita del usuario. Hay recuperación local de edición e intentos
pendientes; no hay sincronizador, carga de archivos ni edición de versiones publicadas. Esos límites se mantienen
según `OFFLINE.md`; el recorrido no se declara apto para el piloto sin esa etapa.

Referencias oficiales: [ViewModel compartido](https://kotlinlang.org/docs/multiplatform/compose-viewmodel.html),
[ciclo de vida Compose Multiplatform](https://kotlinlang.org/docs/multiplatform/compose-lifecycle.html)
y [conversión JSON de Ktor](https://api.ktor.io/ktor-serialization/io.ktor.serialization/-json-convert-exception/index.html).
El contrato de negocio se contrastó con `specs/CHECKLISTS.md` y OpenAPI del backend
del workspace. La publicación consume el API existente y no modifica el backend.

## Páginas y enlaces directos

Buscar nombre, área o descripción consulta el servidor tras 300 ms y cancela
la solicitud anterior. `Anterior/Siguiente` mantiene una página de plantillas en
memoria. No hace descarga masiva ni sondeo. Las búsquedas pueden encontrar
plantillas posteriores al primer centenar. Una falla de permisos vacía datos y
no se confunde con catálogo vacío. Actualizar reinicia el cursor con la búsqueda.

El detalle tiene carga, fallo y solicitud propios: no busca exclusivamente en
la página presente del catálogo. Una ruta directa recupera la plantilla autorizada
por ID; volver mantiene la página/búsqueda del catálogo. Cambiar usuario/empresa
o cerrar sesión cancela ambas consultas. Compartir PageRequest/controles técnicos
no introduce dependencias entre features. Publicar primero backend y luego cliente.

## Autoría y publicación de la primera versión

Recorrido administrativo (fase 3 del plan, RF-12): Empresa → Gestionar plantillas
→ Crear plantilla → Revisar plantilla → Publicar plantilla. ADMIN del tenant y
SUPER_ADMIN tienen acceso; CHEF y WORKER no reciben estos destinos. SUPER_ADMIN
mantiene su navegación de gestión y no necesita acceder a Control como operador.
La raíz verifica capacidad también al abrir directamente las rutas
`empresa/plantillas`, `empresa/plantillas/nueva` y
`empresa/plantillas/detalle/{templateId}`. Cada solicitud se autoriza de nuevo en la API.

La plantilla permite nombre, área y descripción; de 1 a 100 preguntas con sección,
enunciado, obligatoriedad y tipo COMPLIANCE/TEXT/TEMPERATURE/NUMBER. Las preguntas
numéricas requieren rango aprobado, hasta dos decimales y unidad breve; no hay
rangos sanitarios predeterminados. La entrada acepta coma decimal y el transporte
usa números JSON. Se puede cambiar orden, eliminar y deshacer la última eliminación
mediante Snackbar. La fila compartida BpmCheckboxRow agrupa casilla y etiqueta con
área de al menos 48 dp, usada también por la selección de sedes.

La revisión muestra el contenido y la primera versión antes de la acción de
publicar. El contenido publicado se consulta como versión inmutable; no se añade
edición, borrado ni publicación de versión 2. La evidencia obligatoria sigue
reservada al módulo multimedia y no se ofrece como función disponible.

Responsabilidades:

- `domain/PublishChecklistTemplate`: validación, propuesta y puerto mínimo de publicación.
- `PublishedChecklistReader`: capacidad mínima de lectura por ID; publicar no depende del listado.
- `data/RemoteChecklistTemplatePublisher`: token inyectado, DTO, límite real de 64 KiB,
  HTTP y confirmación por lectura. Reutiliza el mapeo de preguntas del catálogo.
- `presentation/ChecklistTemplateEditorViewModel`: estado de autoría, orden, deshacer,
  identificadores estables, revisión y cancelación de publicación al cambiar contexto.
- `presentation/ChecklistTemplateEditorScreen`: campos adaptables y acciones; sin red,
  SDK, permisos de base de datos ni generación de identificadores.
- `AppGraph` compone puertos, casos de uso y VM; app coordina rutas y slots de sesión.

El borrador se conserva localmente por cuenta/empresa con esquema versionado.
Recargar y volver a entrar recupera campos, IDs, preguntas y orden después de
validar el contexto autorizado. Los intentos de publicación quedan congelados
antes de enviar; recuperar ofrece comprobarlos sin otro POST automático. Descartar
pide confirmación. Cambiar cuenta/empresa o cerrar sesión cancela solicitudes y
limpia la UI, conservando la copia del propietario. Ver OFFLINE.md.

El editor ofrece ejemplos de apertura y limpieza editables, sin umbrales sanitarios
inventados ni publicación automática. Solo se aplican al empezar, sin sobrescribir
ediciones existentes. La pregunta aparece antes del grupo, con placeholders que
no sustituyen las etiquetas. El selector explica cómo responderá el equipo, el
contador muestra la cantidad real y las acciones de orden solo aparecen si aplican.

El transporte usa GET `/checklists/{id}` para comprobar si ya existe y POST
`/checklists` (201) para publicar. Los IDs de plantilla, versión y preguntas se
generan una vez por propuesta en el VM mediante una función inyectada. Un reintento
conserva todos esos IDs. Si la lectura confirma exactamente versión, metadata,
orden, tipos, criterios y preguntas, se confirma publicación sin otro POST. Si
una creación concurrente da 409, se vuelve a leer y comparar. Una coincidencia
parcial o un contenido distinto nunca se anuncia como éxito ni se sobrescribe.
Esto es recuperación del cliente; el POST del backend no se declara idempotente.
Si el 409 corresponde a un nombre ya utilizado y el ID de la propuesta no existe,
el editor permite corregir el nombre sin confundirlo con una publicación propia.

Si no puede confirmarse el resultado, se conserva la propuesta enviada sin dejar
editar ese contenido hasta resolver la publicación. Se ofrece comprobar y
reintentar; un nuevo límite de tasa no convierte un resultado incierto en conocido.
401/403 limpian la pantalla y muestran un estado explícito; no revelan la copia local sin reautorizar. El cuerpo y los
mensajes internos del proveedor nunca se muestran; solo campos de error conocidos.
Una respuesta tardía cancelada no puede restaurar el borrador de otro contexto.

El proxy permite POST solamente en el catálogo sin query, Bearer, origen válido
cuando existe, JSON de hasta 64 KiB y campos explícitos de plantilla/preguntas.
No reenvía cookies ni cabeceras de rol/tenant, no acepta actores ni organizationId
como campos, ni habilita PUT/DELETE. Las respuestas se filtran a campos públicos;
los errores se sanitizan. Android/iOS usan directamente el mismo contrato HTTPS.

La revisión y la confirmación de descarte forman parte de la pantalla para
conservar navegación y accesibilidad en Compose web; no usan capas Popup/Dialog.
Los criterios numéricos se apilan en ventanas estrechas y comparten fila cuando
hay espacio. La validación lleva al inicio al revisar; corregir un campo no debe
mover automáticamente el scroll en cada pulsación.

Pruebas: validación sin escritura, autorización, DTO sin campos de autoridad,
conflictos, respuesta perdida y confirmación sin duplicado, orden e IDs estables,
Deshacer, cancelación y cambio de contexto, límites de cuerpo y proxy cerrado.
La publicación depende del GET de detalle añadido con paginación: publicar primero
ese backend antes de distribuir este cliente. No requiere nuevas variables ni
migraciones y no crea contenido de prueba en producción.

Referencias: [ViewModel KMP](https://kotlinlang.org/docs/multiplatform/compose-viewmodel.html),
[accesibilidad de controles Compose](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).

## Ejecución en línea de controles

CHEF accede desde Control → Ver preguntas → Comenzar revisión.
ADMIN/SUPER_ADMIN consultan las revisiones desde Seguimiento.
La sede se elige con búsqueda y páginas reales del workspace, adaptadas en la
raíz de composición; checklists no importa workspace. La API conserva la decisión
sobre membresía y sede. SUPER_ADMIN mantiene gestión y WORKER su recorrido de
aprendizaje; abrir la URL no amplía sus destinos.

`control/plantilla/{templateId}/iniciar` consulta la versión publicada antes de
crear una ejecución. `control/ejecucion/{runId}` permite recuperar su borrador
remoto con autorización nueva. El alta conserva UUID, operación y fecha UTC
mientras se reintenta el mismo intento. La URL de la ejecución se establece
solo tras confirmar el alta. El historial paginado permite recuperar la ejecución sin volver a iniciarla.

`ChecklistRunViewModel` coordina los casos de uso Start/Load/Save/Submit; el puerto
de lectura está separado del de escritura. `RemoteChecklistRunRepository`
reutiliza ItemDto y recibe la sesión por inyección. Valida ID, tenant, sede,
versión, tipos de preguntas y respuestas; no acepta un guardado distinto del
cuerpo propuesto ni una finalización sin estado SUBMITTED y resultado válido.

Se responden Cumple/No cumple/No aplica, texto y valores numéricos, con observación
opcional. Los números aceptan coma decimal, hasta dos decimales y los límites
estructurales de la API. Los criterios sanitarios proceden de la plantilla.
«Guardar borrador» envía el conjunto completo con rowVersion e Idempotency-Key;
«Revisar y finalizar» solo aparece operativo después de guardar. Los cambios de fase, carga y error llevan el desplazamiento al inicio para que
el resultado no quede fuera de vista; editar una respuesta no mueve el scroll.
La confirmación final advierte que el envío es inmutable. El porcentaje siempre viene del servidor;
TEXT y No aplica no se presentan como preguntas evaluables.

Una respuesta perdida conserva la operación, su cuerpo, versión y clave. No se
permite editarla mientras su resultado es incierto; Reintentar envía exactamente
la misma solicitud. Un conflicto no sobrescribe automáticamente: cargar la
versión guardada exige confirmar el descarte de cambios locales. Las acciones de
salida permiten conservar la copia local antes de salir; una operación incierta
se recupera con su misma propuesta y no se descarta mientras sigue sin confirmar. Cambiar usuario/empresa o perder permisos cancela y limpia
el estado; el backend autoriza cada llamada nuevamente.

Las respuestas sin enviar y las operaciones pendientes se guardan localmente con
cuerpo, clave y versión estables antes de HTTP. Tras reiniciar se reautoriza el
registro y se recupera la edición o se ofrece comprobar la operación original.
Un cambio de versión bloquea el envío sin sobrescribir respuestas; una lectura
fallida no elimina la copia. Esto no sustituye la cola múltiple ni el sincronizador
del piloto; alcance, privacidad y pruebas en OFFLINE.md.
Las plantillas que exijan evidencia fallida no pueden finalizarse sin el módulo
multimedia; no se omite la condición ni se ofrece una carga ficticia.

El proxy de ejecuciones admite POST de alta, GET de historial, GET por ID, PUT
answers y POST submit. Solo el historial acepta los filtros documentados y la
paginación; las escrituras y el detalle rechazan query y destinos dinámicos. Comprueba Bearer, origen cuando existe,
JSON acotado, campos permitidos y claves de idempotencia. No reenvía cookies,
actores, roles ni cabeceras de tenant; filtra la respuesta y los errores. El
Dockerfile incluye el adaptador y su prueba de arranque valida ese paquete.

Pruebas: obligatoriedad, no aplicable, decimales, evidencia, sesión, tenant ajeno,
reintento inmutable, revisión obligatoria, bloqueo del control enviado, cancelación
por contexto, proxy cerrado y sanitización. Las pruebas de UI con API local de
prueba no crean registros ni usuarios en producción.

## Historial de revisiones

RF-13/RF-17: Control del CHEF incluye «Mis revisiones» con filtros de estado,
búsqueda, fechas, sede y continuación del borrador propio. ADMIN/SUPER_ADMIN
usan «Seguimiento» para consultar al equipo, sin recuperar borradores de otras
personas en el editor ni ejecutar controles. El detalle comparte el componente
de lectura de respuestas con la revisión previa al envío. WORKER no dispone
de estas rutas. La API es la autoridad de roles y sedes.

`ChecklistHistoryViewModel` depende de casos de uso y un puerto de lectura
específico; el repositorio traduce DTO y recibe sesión por DI. Cambiar cuenta o
empresa cancela trabajos y limpia páginas/detalle; respuestas tardías no restauran
otro contexto. El buscador reutiliza BpmSearchField; solo conserva la página
visible y los cursores. La consulta se renueva al volver si quedó desactualizada
y después de guardar/finalizar una revisión. La exportación permanece pendiente.

La validación de campos y teclado se especifica en [INPUT.md](INPUT.md).

## Programación por sede y frecuencia

ADMIN/SUPER_ADMIN: Empresa → Gestionar plantillas → abrir una publicada →
Programar en una sede. El formulario reutiliza campos, botones, búsqueda y
selección paginada de sedes autorizadas. Elige una sede, frecuencia y, si hace
falta, hora de referencia local HH:MM. No hay sedes o fechas ficticias. Las
programaciones activas se consultan desde el catálogo administrativo y se pueden
detener con confirmación; se conservan revisiones e historial.

CHEF: Control muestra programaciones de sus sedes y el estado del período:
pendiente, en curso o finalizado. Abre preguntas y sede fija antes del inicio;
retoma su borrador o consulta la revisión existente. Un segundo inicio recibe un
conflicto específico y dirige a la revisión del período. El equipo comparte una
revisión por programación/período; únicamente el ejecutor edita. ADMIN supervisa,
WORKER conserva su recorrido de aprendizaje. La API decide los permisos en cada
llamada, incluidas URL directas.

Rutas: `empresa/controles-programados`,
`empresa/controles-programados/{assignmentId}`,
`empresa/plantillas/detalle/{templateId}/asignar`,
`control/programacion/{assignmentId}`. El catálogo libre está en
`control/plantillas`; iniciar sin programación solo es válido si no hay una
asignación activa para esa plantilla y sede. No se impone una frecuencia global
al catálogo completo.

ONCE exige una revisión; DAILY usa día local; WEEKLY lunes a domingo; MONTHLY mes
calendario. La zona IANA procede de la sede y el servidor determina el período.
La hora es una referencia, sin alarmas ni restricción horaria. No se implementan
turnos ni asignación individual con campos inexistentes en el esquema.

Puertos de lectura/escritura separados, casos de uso y ViewModels propios bajo
checklists; AppGraph inyecta adaptadores y sesión. La raíz app adapta las sedes
del workspace mediante `ChecklistLocationSelector`, compartido con el inicio de
revisión. El proxy web acepta solo cuatro operaciones, filtra metadatos y no
reenvía cookies ni roles. El Dockerfile incluye el nuevo módulo.

El formulario conserva un borrador por plantilla, propietario, organización y
origen. Antes de HTTP persiste el UUID y cuerpo propuestos. Si se pierde una
respuesta bloquea la edición y ofrece comprobar esa misma programación: consulta
el ID antes de crear y nunca genera otro ID en el reintento. Recuperar no envía
POST automático. Las propuestas de inicio de revisión conservan assignmentId en
la copia local junto a operación y fecha originales. Ver OFFLINE.md.

Las listas se revalidan por antigüedad al entrar/volver al primer plano y al
confirmar una revisión; no se recargan formularios ni se usa un temporizador de
consultas. Un error o denegación se distingue de una lista vacía. El contrato de
API, calendario y concurrencia está en Backend/BPM-service/specs/CHECKLISTS.md.

Al volver a la lista se revalida también si terminó el período recibido, aunque
no hayan pasado los dos minutos de antigüedad. Esto evita conservar el estado de
ayer al cruzar medianoche; el período definitivo sigue perteneciendo al servidor.
