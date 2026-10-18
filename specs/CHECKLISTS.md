# Catálogo y detalle de controles

## Alcance de esta entrega

Primer incremento del recorrido de checklist previsto en el plan del proyecto,
relacionado con RF-12 a RF-17. Implementa consulta de plantillas publicadas,
búsqueda por nombre/área/descripción y detalle de preguntas. No completa todavía
esos requerimientos: iniciar ejecuciones, responder, enviar, evidencias e historial
pertenecen a incrementos posteriores.

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

Esta entrega requiere internet. No hay caché persistente, borradores, envío,
sincronización, carga de archivos ni creación/edición de plantillas en el cliente.
Se mantienen como trabajo posterior según `OFFLINE.md`; no se declaran terminadas
por existir una pantalla de consulta.

Referencias oficiales: [ViewModel compartido](https://kotlinlang.org/docs/multiplatform/compose-viewmodel.html),
[ciclo de vida Compose Multiplatform](https://kotlinlang.org/docs/multiplatform/compose-lifecycle.html)
y [conversión JSON de Ktor](https://api.ktor.io/ktor-serialization/io.ktor.serialization/-json-convert-exception/index.html).
El contrato de negocio se contrastó con `specs/CHECKLISTS.md` y OpenAPI del backend
del workspace, consultados sin modificar ese repositorio.

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
