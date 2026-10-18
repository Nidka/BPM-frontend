# Catálogo y detalle de controles

## Alcance de esta entrega

Primer incremento del recorrido de checklist previsto en el plan del proyecto,
relacionado con RF-12 a RF-17. Implementa consulta de plantillas publicadas,
búsqueda por nombre/área/descripción y detalle de preguntas. No completa todavía
esos requerimientos: iniciar ejecuciones, responder, enviar, evidencias e historial
pertenecen a incrementos posteriores. La autoría y publicación de la primera
versión ya están disponibles para administradores, según la sección final.

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
6. «Volver al catálogo» o Atrás regresan a la lista. La ruta es
   `#control/plantilla/{templateId}`: permite historial y enlaces directos. El ID
   identifica una plantilla, sin incluir usuario, empresa ni credenciales. Abrir
   un enlace directo exige sesión, rol y consulta autorizada del detalle en la empresa
   activa; una plantilla ajena o retirada muestra un estado no disponible.
   La solicitud del detalle no depende de la página cargada del catálogo.

Se distinguen carga, catálogo vacío, búsqueda sin coincidencias y fallo. El fallo
permite reintentar; «Actualizar» del workspace vuelve a consultar el contexto y
su catálogo. Una recarga retira datos anteriores hasta confirmar la nueva lectura.
La búsqueda se conserva al reintentar o volver del detalle en el mismo contexto
y se limpia al cambiar usuario/empresa o cerrar sesión.

El contenedor conserva navegación lateral desde 950 dp e inferior por debajo.
Las tarjetas usan dos columnas desde 720 dp de contenido y una en tamaños menores;
el detalle ocupa el ancho del contenido y comparte el desplazamiento del
workspace, con regreso al catálogo al principio y al final.
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

Esta entrega requiere internet. No hay caché persistente, borradores de ejecución,
envío de respuestas, sincronización, carga de archivos ni edición de versiones publicadas.
Se mantienen como trabajo posterior según `OFFLINE.md`; no se declaran terminadas
por existir una pantalla de consulta.

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

El borrador existe en memoria durante la sesión del mismo usuario y organización.
Volver por navegación y abrir el editor conserva ese estado; descartar pide
confirmación en el contenido. Cambiar usuario/organización, perder permisos o
cerrar sesión lo limpia y cancela solicitudes. Cerrar el proceso o recargar la web
pierde el borrador. No es persistencia, cola ni sincronización offline; esa parte
sigue el contrato OFFLINE.md y no queda completada por este editor.

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
401/403 limpian el borrador y muestran un estado explícito. El cuerpo y los
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
