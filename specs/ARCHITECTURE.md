# Arquitectura

KMP comparte lógica y UI entre Android, iOS y navegador. Railway aloja el bundle
web compilado a WebAssembly. Los binarios móviles se distribuyen por sus canales
respectivos.

Se usa un módulo Gradle `composeApp` para mantener simple la base; las fronteras
son paquetes. Se extraerán módulos al necesitar aislamiento de compilación o
trabajo entre equipos, conservando las mismas dependencias.

```text
composeApp/src/commonMain/kotlin/pe/bpm/
  app/                       entrada Compose, GraphOwner y coordinación entre features
  core/
    config/                  configuración pública tipada
    di/                      composición manual AppGraph
    network/                 HttpClient y motores expect/actual
    domain/                  resultado, propietario de borrador y fallos técnicos compartidos
    storage/                 documentos locales atómicos y adaptadores de plataforma
    presentation/            coordinación de escrituras locales y vigencia de consultas
    designsystem/            tema, tarjetas, marca, mascota y botones compartidos
  features/connection/
    domain/                  modelo, puerto de repositorio y caso de uso
    data/                    DTO y adaptador Ktor
    presentation/            ViewModel, estado y pantalla
  features/identity/
    domain/                  perfil y casos de uso de sesión
    data/                    adaptadores Firebase/BPM, coordinación de credenciales y almacenamiento seguro
    presentation/            formularios, ViewModel y monitor/aviso de sesión
  features/workspace/
    domain/                  organizaciones, sedes, equipo y casos de uso
    data/                    DTO y adaptador Ktor autenticado
    presentation/            contenedor adaptable, contenido, formularios, estado y ViewModel
  features/checklists/
    domain/                  plantillas, preguntas y puertos separados de consulta/publicación
    data/                    DTO y adaptadores autenticados de lectura/publicación
    presentation/            catálogo, editor, revisión y publicación por ViewModels separados
```

Flujo: pantalla → ViewModel → caso de uso → interfaz del repositorio →
implementación data → API. El estado regresa por StateFlow hacia la pantalla.
El dominio conoce únicamente modelos y contratos; no depende de UI ni red.

La UI utiliza `viewModel` con propietario de ciclo de vida. El ViewModel usa
`viewModelScope`; al salir se cancela su trabajo. Los reintentos cancelan la
solicitud anterior. El repositorio propaga CancellationException y transforma
errores técnicos en AppFailure. Nunca presenta mensajes internos del proveedor.

AppGraph construye cliente, repositorio, caso de uso y ViewModel. Cada constructor
recibe sus dependencias. No hay framework DI, estado global mutable ni repositorio
genérico.

El cliente HTTP pertenece al ciclo de vida de GraphOwner (ViewModel): se registra
como recurso cerrable y se libera en onCleared, no al recomponer la pantalla.
Así sobrevive a cambios de configuración Android sin reutilizar un cliente cerrado.

`androidMain` usa OkHttp; `iosMain`, Darwin; `wasmJsMain`, Js. `jvmMain`
permite ejecutar pruebas compartidas sin emulador. No es un producto de escritorio.

La web llama a `/backend/health` y `/backend/ready` del mismo origen. El servidor
Node reenvía esas rutas y GET /backend/api/v1/me al backend HTTPS definido por
API_BASE_URL. Android/iOS consultan directamente el origen HTTPS configurado al
compilar. Los módulos futuros ampliarán el proxy con rutas y encabezados
autorizados explícitamente.

La base visual BPM usa petróleo/coral, mascota propia, botones con relieve,
tarjetas y navegación adaptable. Identidad conserva login/registro, verificación
y recuperación. Al autenticarse abre workspace con tenant autorizado, altas de
empresa/sede/equipo y catálogos de Aprender/Control. Ver IDENTITY.md y WORKSPACE.md.
Hay recuperación local de borradores según OFFLINE.md; el sincronizador completo sigue pendiente. Registro solo crea USER sin acceso
a organizaciones. AppGraph comparte sesión y red entre features sin singletons.

## Fronteras comprobadas

El README de la raíz describe el árbol implementado, responsabilidades y comandos.
`node --test scripts/*.test.mjs` comprueba imports de producción en todos los
source sets: dominio independiente, presentación sin acceso a data/HTTP, data sin
UI, features sin dependencias entre sí y core sin features salvo la raíz DI.
La comprobación es estática sobre imports; no sustituye pruebas de comportamiento
ni detecta referencias escritas con nombres completamente cualificados.

App adapta el perfil de identidad a WorkspaceViewer (datos públicos de
presentación, sin token). Workspace no importa identity. GraphOwner administra
la vida del grafo; AppGraph compone las dependencias de las funcionalidades.
Los hosts nativos inyectan adaptadores que requieren Activity, UIViewController
o un SDK Swift; las pantallas y los casos de uso desconocen esas dependencias.

WorkspaceScreen conserva la distribución adaptable y delega el contenido en
WorkspaceContent y los formularios en WorkspaceForm. WorkspaceDestination enumera
las rutas públicas de las secciones existentes y la política de navegación por
rol. El grafo se limpia al cambiar de empresa o cerrar sesión; formularios y menús
se reinician al cambiar de contexto.
App utiliza Navigation Compose Multiplatform 2.9.2 y NavHost. Los fragmentos web
se enlazan mediante bindToBrowserNavigation; Android usa atrás del sistema.
Cada ruta protegida comprueba sesión y destinos permitidos antes de componer UI.
El detalle de preguntas usa `control/plantilla/{templateId}` en el mismo NavHost,
con las comprobaciones de sesión y capacidades de Control. Consulta por ID una
plantilla publicada de la empresa activa, independientemente de la página cargada; admite historial y
enlace directo. Ver CHECKLISTS.md.
El inicio `control/plantilla/{templateId}/iniciar` y el registro
`control/ejecucion/{runId}` comparten esas comprobaciones. `app/ChecklistRunContent`
adapta las sedes del workspace al módulo de controles; no hay dependencias entre
features. El ViewModel usa casos de uso y el repositorio recibe la sesión por
inyección. La ejecución disponible es en línea; no sustituye la cola offline.
Las altas de organización/sede/miembro también tienen rutas propias en ese
NavHost. App comprueba las capacidades antes de mostrarlas y observa la revisión
de guardado para regresar solo después del éxito confirmado. WorkspaceForm
observa WorkspaceFormViewModel; el estado y los casos de uso de borradores ya no viven en remember de la pantalla. No depende de app ni accede al disco.
Ver WORKSPACE.md para rutas, permisos y accesibilidad.
Los enlaces nativos del sistema operativo aún no se han registrado.

### Decisión de URL web

Se conserva el enlace oficial `bindToBrowserNavigation()` de Navigation 2.9.2.
Representa destinos mediante fragmentos: `https://appbpm.com/#acceso`, `#inicio`,
`#cuenta`, `#empresa` y `#empresa/plantillas/nueva`. Las rutas son públicas y
legibles; no incluyen nombres de clases, correos ni credenciales. El navegador
gestiona Atrás/Adelante y los enlaces directos. El fragmento no se envía a Node.
Cada destino protegido comprueba sesión y capacidades también mediante URL.

Esta forma está documentada por JetBrains. Se mantiene para compartir el mismo
NavHost con Android/iOS y evitar otra implementación de historial. Una futura
adopción de `/acceso` necesitará adaptar el enlace entre rutas e historial y
comprobar recargas, enlaces guardados y autorización; el fallback HTML del
servidor por sí solo no cambia el router. La presentación de la URL no modifica
permisos ni el contrato del backend.

Referencia: [navegación y URL en Compose Multiplatform](https://kotlinlang.org/docs/multiplatform/compose-navigation-routing.html#support-for-browser-navigation-in-web-apps).

### Campos compartidos

`core/designsystem/BpmTextField` define contorno de 14 dp, ancho disponible,
etiqueta accesible, ayuda/error y estados Material de foco/deshabilitado.
Identidad, empresa, equipo, búsquedas y editor de plantillas lo utilizan. Admite
contraseña oculta/visible, teclado específico y texto multilínea; la altura se
adapta al contenido y a la tipografía. No guarda valores ni valida reglas de negocio.
Los adaptadores de cada pantalla conservan sus acciones de usuario, validaciones
y opciones de teclado; no crean estilos paralelos.

Referencia: [campos de texto en Compose](https://developer.android.com/develop/ui/compose/text/user-input).

La cancelación se verifica antes de aplicar cada respuesta del workspace,
incluidos adaptadores que entreguen respuestas después de cancelar. Una prueba
simula una respuesta tardía tras logout y confirma que no restaura el tenant.

El bundle web declara ausente el global Node `process` mediante DefinePlugin en
`composeApp/webpack.config.d/browser-runtime.js`. Esto permite iniciar el logger
Wasm de Ktor en navegadores sin introducir un polyfill de Node ni sus variables.

## Renovación y restauración

AppGraph inyecta el adaptador SessionStore recibido del punto de entrada nativo.
Android utiliza un archivo AES/GCM con clave de Android Keystore; iOS un ítem
Keychain exclusivo del bundle y dispositivo. Web/JVM usan un adaptador que no
persiste. Las pantallas y el dominio nunca conocen tokens ni APIs de almacenamiento.

RemoteIdentityRepository serializa renovaciones, conserva origen/UID/refresh token
y comprueba /me antes de restaurar un perfil. Workspace recibe por DI un proveedor
suspendido de tokens vigentes y una función de rechazo del token actual. Ambos
adaptadores comparten únicamente el error técnico de core/network; no se importan
entre funcionalidades. ObserveSessionInvalidations permite limpiar la UI desde
el ViewModel; no hay dependencias de red en presentación.

Checklists sigue el mismo contrato de inyección de sesión. App coordina su estado
por usuario/empresa y lo inserta en WorkspaceScreen mediante un slot Compose;
workspace no importa checklists. El lector consulta plantillas publicadas; el publicador consume un puerto de escritura independiente.
Ver CHECKLISTS.md para límites, reintentos y limpieza del contexto.

## Responsabilidades de identidad y sesión

La separación de transporte y coordinación se documenta en IDENTITY.md.
FirebaseIdentityDataSource y BpmIdentityDataSource contienen los contratos
externos; RemoteIdentityRepository coordina el contexto de identidad y rechaza
respuestas de generaciones anteriores. Los casos de uso reciben interfaces por
capacidad. IdentityErrorPolicy concentra la clasificación de errores y
SessionMonitor concentra reloj/actividad, sin HTTP ni SDK en presentación.
AppGraph construye estas dependencias y la UI observa sus StateFlow.

El aviso de sesión se compone una sola vez en la raíz de app como capa modal del
árbol principal, con fondo inaccesible mientras está abierto y foco acotado a sus
acciones. Esta composición evita crear otra ventana Popup y conserva navegación
accesible web al cerrar. La entrada del teclado virtual se notifica desde
BpmTextField mediante LocalUserActivity, proporcionado por app: el componente no
importa identidad ni decide plazos. No se notifica actividad por guardado automático,
recomposición o temporizador. La recuperación pertenece a casos de uso específicos
de workspace/checklists y a adaptadores locales inyectados; ver OFFLINE.md.
La prueba estática de imports confirma fronteras; no certifica por sí
sola el cumplimiento completo de SOLID ni sustituye la revisión de comportamiento.
