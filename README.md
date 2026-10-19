# BPM Frontend

Aplicación de Buenas Prácticas de Manipulación con Kotlin Multiplatform y Compose
Multiplatform para Android, iOS y web. Este repositorio contiene el cliente y su
servidor web de distribución/proxy; el backend Go se mantiene en un repositorio y
despliegue independientes.

Repositorio: https://github.com/Nidka/BPM-frontend

## Arquitectura implementada

Organización por funcionalidades, Clean Architecture, MVVM y flujo unidireccional.
Un módulo Gradle `composeApp` mantiene la compilación sencilla. Los límites entre
capas se comprueban automáticamente; se extraerán módulos Gradle cuando exista
una necesidad de aislamiento de dependencias o compilación.

```text
composeApp/src/
├── commonMain/
│   ├── kotlin/pe/bpm/
│   │   ├── app/                        entrada, ciclo de vida y coordinación entre features
│   │   │   ├── App.kt                  sesión, destinos raíz y adaptación del perfil a la UI
│   │   │   └── GraphOwner.kt           propietario del grafo y cierre de recursos
│   │   ├── core/
│   │   │   ├── config/                configuración pública y contrato por plataforma
│   │   │   ├── di/AppGraph.kt         construcción e inyección manual de dependencias
│   │   │   ├── domain/                resultados y fallos técnicos compartidos
│   │   │   ├── network/               cliente Ktor, motores HTTP y error seguro de sesión
│   │   │   ├── storage/               documentos locales atómicos y adaptadores por plataforma
│   │   │   ├── presentation/          coordinación de borradores y vigencia de consultas
│   │   │   └── designsystem/          tema, marca, mascota y componentes reutilizables
│   │   └── features/
│   │       ├── connection/            salud y disponibilidad reales del backend
│   │       ├── identity/              acceso, registro, verificación y recuperación
│   │       ├── checklists/            catálogo, autoría y ejecución en línea de controles
│   │       │   ├── domain/            plantillas, preguntas, puerto y caso de uso
│   │       │   ├── data/              consulta autenticada y DTO privados
│   │       │   └── presentation/      StateFlow, ViewModels, catálogo, editor y ejecución
│   │       └── workspace/             organización activa, sedes, miembros y resúmenes
│   │           ├── domain/            modelos, repositorio y casos de uso
│   │           ├── data/              adaptador remoto, DTO privados y traducción de errores
│   │           └── presentation/
│   │               ├── WorkspaceViewModel.kt   operaciones y estado observable
│   │               ├── WorkspaceState.kt       estado inmutable de presentación
│   │               ├── WorkspaceViewer.kt      datos públicos del usuario para esta feature
│   │               ├── WorkspaceDestination.kt destinos estables y política de navegación por rol
│   │               ├── WorkspaceScreen.kt      contenedor y navegación adaptable
│   │               ├── WorkspaceContent.kt     contenido y componentes de secciones
│   │               └── WorkspaceForm.kt        formularios de organización, sede y miembro
│   └── composeResources/              ilustraciones empaquetadas, sin CDN
├── androidMain/                       entrada Android, motor OkHttp y adaptadores Android
├── iosMain/                           entrada iOS, motor Darwin y adaptadores iOS
├── wasmJsMain/                        entrada del navegador, motor Js y puentes web
├── jvmMain/                           ejecución auxiliar y soporte de pruebas
└── commonTest/                        pruebas compartidas de repositorios y ViewModels
web/                                   servidor Node, proxy cerrado y puente Google web
scripts/architecture.test.mjs           comprobación de dependencias por capa y funcionalidad
specs/                                 contratos, alcance y documentación detallada
```

`iosApp/` contiene el host SwiftUI y el puente al SDK oficial Google Sign-In.
`iosTest/` comprueba cancelación y callbacks del proveedor iOS. `jvmMain` no constituye un producto de
escritorio. No se crean carpetas vacías para funcionalidades aún no implementadas.

## Responsabilidades y dependencias

```text
Pantalla Compose ──acción──> ViewModel ──> Caso de uso ──> Contrato de repositorio
       ↑                       │                               ↑
       └──── UiState/StateFlow ┘                       Implementación data ──> API
```

- **domain:** modelos y contratos propios de la funcionalidad. Sin Compose,
  ViewModel, Ktor, DTO, SDK Firebase ni almacenamiento concreto.
- **data:** implementa contratos del dominio, realiza HTTP y transforma DTO y
  errores del proveedor en resultados seguros. Propaga cancelación de coroutines.
- **presentation:** pantallas reciben estado y callbacks; ViewModels reciben casos
  de uso por constructor. No ejecuta HTTP ni construye repositorios.
- **app:** coordina funcionalidades. Convierte el perfil de identidad a
  `WorkspaceViewer`; workspace no importa tipos de identity ni recibe tokens.
- **core/di:** compone el grafo de funcionalidades. Los hosts nativos inyectan
  los adaptadores ligados al sistema operativo. `AppGraph` comparte cliente y
  sesión, sin service locator ni singletons propios. El resto de core no importa features.

Las funcionalidades no importan otras funcionalidades. Cuando un recorrido
necesite colaboración, se compone en app/DI mediante contratos explícitos; solo
se extrae código a core si existe reutilización comprobada. Los modelos del
resumen de workspace son proyecciones de su endpoint, no el modelo definitivo de
cursos, evaluaciones o ejecuciones de checklist.

## Anotaciones de Kotlin y contratos de plataforma

| Elemento aplicado | Responsabilidad | Ubicación |
|---|---|---|
| `@Composable` | Declarar UI Compose compartida. | `app/`, `core/designsystem/` y `presentation/`. |
| `@Serializable` | Generar serializadores de DTO y credenciales privadas del adaptador. | `data/`; no en dominio. |
| `@SerialName` | Mapear nombres externos como `refresh_token` sin alterar el contrato de la API. | DTO Firebase de `RemoteIdentityRepository`. |
| `@OptIn` / `@file:OptIn` | Declarar explícitamente APIs experimentales: UUID, historial web e interoperabilidad nativa. | Archivos que usan esas APIs. |
| `@JsFun` | Implementar puentes JavaScript del navegador. | `wasmJsMain`, fuera de dominio. |
| `expect` / `actual` | Declarar contratos comunes e implementaciones específicas de configuración, HTTP y Google. Son palabras clave, no anotaciones. | `commonMain` y source sets de plataforma. |

Estas anotaciones ya están aplicadas en código. La composición por constructor y
las dependencias entre capas permanecen explícitas; las anotaciones no sustituyen
la autorización del servidor ni la validación de seguridad del despliegue.

## Ciclo de vida, navegación y contexto empresarial

Las sesiones preparadas para el backend V2 aplican 8 horas máximas y 15 minutos
de inactividad, con aviso y cierre local. La renovación no reinicia el plazo;
el servidor lo valida con Firebase y PostgreSQL. El contrato, compatibilidad y
límites de borradores están en [specs/IDENTITY.md](specs/IDENTITY.md).

`GraphOwner` es un ViewModel propietario de `AppGraph`: el cliente HTTP sobrevive
recomposiciones y cambios de configuración Android y se cierra al liberar al
propietario. Los ViewModels usan `viewModelScope`; la UI observa con
`collectAsStateWithLifecycle`.

La raíz usa Navigation Compose Multiplatform 2.9.2, con NavHost y una lista
cerrada de rutas: `acceso`, `conexion`, `inicio`, `aprender`, `control`, `equipo`,
`empresa`, `cuenta` y `control/plantilla/{templateId}`. Android integra atrás del sistema; web conecta el mismo
grafo mediante `bindToBrowserNavigation` para atrás/adelante y URLs como
`#aprender`. No se incluyen tokens, usuario ni empresa en la URL.

Las rutas protegidas comprueban sesión y capacidades antes de componer contenido.
Abrir una URL directamente, restaurar historial o volver después de logout no
permite saltar el acceso. Un enlace protegido conserva su destino durante login;
si el rol no lo permite, se abre Inicio. Cambiar empresa o cerrar sesión limpia
la pila interna; cualquier entrada antigua del navegador vuelve a pasar los
controles. Los enlaces nativos del sistema (Android App Links/iOS Universal Links)
no están registrados: requieren una integración específica posterior.

| Contexto confirmado por API | Destinos disponibles |
|---|---|
| SUPER_ADMIN de plataforma | Inicio, Equipo, Empresa y Mi cuenta |
| ADMIN de organización | Inicio, Aprender, Control, Equipo, Empresa y Mi cuenta |
| CHEF | Inicio, Control, Equipo, Aprender y Mi cuenta |
| WORKER | Inicio, Aprender y Mi cuenta |
| Sin membresía o rol desconocido | Inicio y Mi cuenta |

El administrador de organización accede a su aprendizaje desde Inicio o Mi cuenta
para conservar un máximo de cinco destinos de navegación principal. Equipo del
chef conserva el estado informativo actual; no simula asignaciones ni reportes
que la API todavía no proporciona. Ocultar destinos es una política de experiencia,
no una autorización del cliente. Los roles elevados proceden de la API.

El contenedor presenta navegación inferior en ventanas pequeñas y lateral desde
950 dp. Las pantallas comparten lógica y responden al ancho de ventana. Formularios
y menús se reinician al cambiar usuario/organización. Control consulta el catálogo
publicado y permite iniciar, guardar y finalizar un control de sede en línea,
con revisión y confirmación antes de cerrar las respuestas. El contrato, decisiones y
límites de este primer incremento están en [specs/CHECKLISTS.md](specs/CHECKLISTS.md).

Cambiar organización cancela consultas y limpia el contenido anterior. Logout
cancela operaciones y limpia workspace y sesión. Antes de aplicar respuestas se
comprueba que la coroutine siga activa para evitar que una respuesta tardía
restaure datos de otro contexto. Las capacidades visibles proceden del perfil y
la membresía devueltos por API; el backend valida siempre permisos y aislamiento.
El selector de rol del prototipo no pertenece al flujo productivo.

## Sesión y almacenamiento seguro

La contraseña se utiliza solo para el acceso a Firebase. El ID token vive en
memoria. El adaptador conoce vencimiento y refresh token, renueva con un margen
de 60 segundos y serializa renovaciones mediante Mutex. Las solicitudes de negocio
obtienen una credencial vigente antes de enviarse; no se reenvían automáticamente
POST tras errores de autorización. El perfil se revalida cada minuto mientras
existe sesión y desde Mi cuenta. Cambios de membresía recargan el workspace.

- **Android:** AndroidSessionStore cifra mediante AES-256/GCM; la clave reside en
  Android Keystore, con alias del paquete BPM. El archivo cifrado se escribe de
  forma atómica en `noBackupFilesDir`; la app mantiene `allowBackup=false`.
- **iOS:** KeychainSessionStore usa un ítem del bundle BPM, accesible solo con el
  dispositivo desbloqueado, exclusivo del dispositivo y sin sincronizar a iCloud.
- **Web/JVM:** EphemeralSessionStore no escribe nada. ID token y refresh token
  permanecen en memoria; recargar la web exige entrar nuevamente.

El registro persistido móvil contiene origen API, UID y refresh token; nunca la
contraseña, el perfil como autorización o un ID token persistente. Cambiar de
origen rechaza la credencial antes de enviarla. Restaurar exige renovar y validar
el UID tanto en Firebase como en `/me`; no registra perfiles automáticamente.
La renovación rota el refresh token almacenado. Una indisponibilidad temporal
permite reintentar; una revocación definitiva limpia sesión y almacenamiento.

Logout borra credenciales y workspace. La generación de sesión evita que una
respuesta anterior los restaure. Un 401 de la API invalida la sesión si corresponde
al token actual; rechazos de peticiones antiguas no expulsan una sesión nueva.
Liberar AppGraph cierra recursos y memoria. La credencial protegida móvil y las
copias locales de borradores ya confirmadas permanecen para el próximo arranque. La restauración requiere red y no
constituye autenticación offline.

## Web y aplicaciones móviles

| Plataforma | UI y lógica | Acceso a API | Integraciones |
|---|---|---|---|
| Android | Compose y funcionalidades compartidas | Ktor/OkHttp al origen HTTPS | `androidMain` |
| iOS | Compose y funcionalidades compartidas | Ktor/Darwin al origen HTTPS | `iosMain` y host SwiftUI |
| Web | Compose compilado a Wasm | rutas `/backend/...` del mismo origen | `wasmJsMain` y `web/` |

`composeApp/webpack.config.d/browser-runtime.js` resuelve las referencias a
`process` como ausentes en el bundle del navegador. Evita que el logger Wasm de
Ktor intente acceder al global de Node, sin añadir polyfills ni incorporar
variables del equipo de compilación. Esta configuración no afecta al servidor Node.

Node sirve la distribución y reenvía únicamente rutas, métodos y campos
permitidos. No implementa reglas empresariales ni permite destinos arbitrarios.
Cada endpoint nuevo necesita adaptación explícita del proxy y pruebas.

Por defecto, web local y binarios móviles apuntan al servicio de Railway. La
configuración `.env` del frontend es pública; Node la carga solo con `--env-file`.
Gradle no lee `.env` automáticamente: usa propiedades `-PapiBaseUrl` y
`-PfirebaseApiKey`, variables del proceso o los valores predeterminados del proyecto.
Un cambio de configuración móvil requiere recompilar.

## Ejecutar y verificar

Requisitos: JDK 21, Android SDK 36 y Node 24; Xcode para compilar iOS.
Abrir esta carpeta, la que contiene `settings.gradle.kts`, en Android Studio.

Web local contra Railway:

```bash
./gradlew :composeApp:wasmJsBrowserDistribution
node --env-file=.env web/server.mjs
```

Abrir http://localhost:3000. Si no existe `.env`, copiar `.env.example` y ajustar
solo configuración pública. El servidor de desarrollo Gradle no incluye el proxy
Node; para validar integración web usar el servidor indicado arriba.

Android: ejecutar la configuración `composeApp` desde Android Studio o:

```bash
./gradlew :composeApp:assembleDebug :composeApp:installDebug
```

Verificación antes de integrar una rama:

```bash
node --test scripts/*.test.mjs web/*.test.mjs
./gradlew :composeApp:jvmTest :composeApp:wasmJsBrowserDistribution :composeApp:assembleDebug
./gradlew :composeApp:iosSimulatorArm64Test :composeApp:linkDebugFrameworkIosSimulatorArm64
bash scripts/build-ios.sh
git diff --check
```

La compilación iOS requiere macOS/Xcode. Si la Mac tiene seleccionadas solo las
Command Line Tools, ejecutar ese comando con
`DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`; no es necesario cambiar
la selección global. CI comprueba límites arquitectónicos,
pruebas web, código compartido, builds Android/web, imagen Docker, pruebas iOS y
compilación del framework y del host Swift con el SDK Google. El host requiere
XcodeGen; ver `specs/DEVELOPMENT.md` para usarlo sin cambiar ajustes globales.
Las pruebas con dobles no sustituyen probar login real, almacenamiento nativo
en dispositivos/emuladores, accesibilidad y recorridos en navegador. Las pruebas automatizadas no crean
cuentas ni escriben en el backend de producción.

## Buenas prácticas para continuar

1. Crear una rama por cambio; implementar y verificar antes de abrir PR a `main`.
   El despliegue productivo pertenece al flujo de integración del repositorio.
2. Desarrollar recorridos por funcionalidad con `domain`, `data`, `presentation`
   y pruebas del comportamiento. No crear repositorios genéricos ni capas vacías.
3. Usar casos de uso para reglas y coordinación; mantenerlos como dependencia de
   ViewModel según la convención de este proyecto.
4. Mantener DTO en data y componentes de negocio en su feature. Compartir en el
   sistema de diseño solo componentes con reutilización real.
5. Representar carga, vacío, error y éxito confirmado. Conservar el formulario
   cuando falle el guardado y evitar envíos concurrentes.
6. Nunca guardar contraseñas, tokens, cuentas Admin, credenciales PostgreSQL o R2
   en Git, recursos o almacenamiento del navegador. No registrar datos sensibles.
7. Mantener el contrato de la API desplegada. No inferir que una tabla existente
   o pantalla del prototipo implica un endpoint implementado.
8. Probar aislamiento entre empresas, cancelación, respuestas tardías y limpieza
   de sesión al modificar operaciones autenticadas.
9. Comprobar teclado, lector de pantalla, texto ampliado, contraste y acciones
   táctiles en cada plataforma; compartir UI no sustituye estas verificaciones.
10. Documentar el comportamiento final y los límites reales; actualizar specs
    cuando cambien contratos o responsabilidades.

## Alcance real y extensiones

Disponibles: navegación con historial y permisos, cuenta, renovación/restauración
de sesión, comprobación de conexión, login/registro/verificación/recuperación,
Google web, perfil confirmado por API, organizaciones autorizadas, altas de
organización/sede/miembro, catálogos y ejecución en línea de controles.

Pendientes: ejecutar cursos/evaluaciones, sincronizar controles offline, acciones correctivas,
evidencias privadas, investigación CAP, reportes completos y recorridos de contenido
y administración aún no implementados del prototipo. Google Android tiene adaptador
Credential Manager y acceso real confirmado por el usuario en su celular.
Google iOS incorpora SDK/adaptador y configuración pública; requiere completar
una prueba real con Google. Ver `specs/IDENTITY.md`. Todas las funcionalidades usan
`BpmTextField`; la decisión de mantener URL con `#` está documentada en
`specs/ARCHITECTURE.md` según la integración oficial de navegación web.

Hay recuperación local de formularios, autoría y respuestas pendientes mediante
adaptadores nativos e IndexedDB web. No hay cola múltiple, sincronizador automático
ni service worker. Las operaciones se conservan antes de enviarlas, con la misma
clave y versión durante reintentos. Recuperar requiere autenticación y permisos;
una copia local nunca se anuncia como éxito remoto. Ver [OFFLINE.md](specs/OFFLINE.md).

El prototipo BPM Aula guía el diseño y los recorridos. Sus datos simulados,
calificaciones, permisos ilustrativos y respuestas normativas no se trasladan
como resultados reales a producción.

## Documentación

- [Arquitectura detallada](specs/ARCHITECTURE.md)
- [Desarrollo y requisitos](specs/DEVELOPMENT.md)
- [Contrato offline pendiente](specs/OFFLINE.md)
- [Identidad y Firebase](specs/IDENTITY.md)
- [Diseño y workspace](specs/WORKSPACE.md)
- [Funcionalidades y alcance](specs/FEATURES.md)
- [Railway y dominio](RAILWAY.md)
- [Estructura oficial de KMP](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html)
- [ViewModel multiplataforma](https://kotlinlang.org/docs/multiplatform/compose-viewmodel.html)
- [Recomendaciones de arquitectura Android](https://developer.android.com/topic/architecture/recommendations)

### Revisión de identidad y estados adaptables

El transporte de identidad está separado en `FirebaseIdentityDataSource` y
`BpmIdentityDataSource`; el repositorio coordina credenciales y los casos de uso
dependen de contratos por capacidad. `SessionMonitor` expone el aviso con cuenta
regresiva: continuar requiere actividad confirmada por servidor; el máximo
absoluto exige autenticación nueva. Ver [IDENTITY.md](specs/IDENTITY.md).

La cabecera usa Material 3 y los íconos Lucide locales del prototipo, con etiquetas
accesibles. La marca temporal «BPM» sustituye el nombre provisional «BPM Aula»
y su eslogan; «Cerrar sesión» vive en Mi cuenta. Los avisos de acceso aparecen
bajo la cabecera; el vencimiento de sesión utiliza una capa modal única. Los estados distinguen
acceso denegado, datos vacíos y fallos de carga, conservando el contrato del
backend y la base visual del prototipo. Ver [WORKSPACE.md](specs/WORKSPACE.md).

La cabecera no tiene una recarga permanente. Las consultas se revalidan al entrar
o volver a primer plano si llevan dos minutos sin actualizarse; los fallos ofrecen
Reintentar. No se recargan formularios ni se mantiene la sesión mediante sondeo.
`RaisedButton`, `BpmSecondaryButton` y `BpmTextButton` unifican las acciones, mientras
Google conserva su botón de marca. Inicio muestra su mascota en móvil y distribuye
los indicadores según ancho y escala de texto. El acceso declara Autofill para
Android; el proveedor del dispositivo controla la oferta de guardar contraseñas.

La elección de organización usa una pantalla con búsqueda y filas de selección,
separada del contenido empresarial. La respuesta a pulsación se configura por
control, con colores del tema y foco visible. Los listados consultan páginas de
25 registros y búsqueda en servidor, con Anterior/Siguiente. El backend autoriza
cada página y devuelve totales para Inicio; el detalle de controles se consulta
por ID independientemente del catálogo. Publicar primero backend y después
frontend; no hay migraciones ni variables nuevas. Ver [WORKSPACE.md](specs/WORKSPACE.md).


La primera versión de plantillas ya se puede preparar desde **Empresa → Gestionar
plantillas → Crear plantilla** con ADMIN o SUPER_ADMIN. El editor permite preguntas,
criterios, revisión y publicación confirmada, con recuperación de respuestas perdidas
sin generar nuevos IDs, incluso tras recuperar la copia local. El editor ofrece
ejemplos editables de apertura y limpieza y explica cada forma de respuesta.
También se pueden ejecutar controles en línea, guardarlos y confirmar su cierre. Contrato y límites en
[CHECKLISTS.md](specs/CHECKLISTS.md).


La entrega de recuperación añade aviso modal de sesión, actividad del teclado
virtual y borradores locales de empresa/equipo, plantillas y controles. Los estados
«guardando»/«guardado»/«no se pudo conservar» y la confirmación de descarte son UI
compartida. Las reglas y DTO pertenecen a cada feature; core solo aporta coordinación
de escrituras y almacenamiento atómico. [OFFLINE.md](specs/OFFLINE.md) contiene el
recorrido de prueba y los límites pendientes, sin declarar completa la sincronización.
