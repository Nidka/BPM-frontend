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
│   │   │   ├── network/               cliente Ktor y contrato de motores HTTP
│   │   │   └── designsystem/          tema, marca, mascota y componentes reutilizables
│   │   └── features/
│   │       ├── connection/            salud y disponibilidad reales del backend
│   │       ├── identity/              acceso, registro, verificación y recuperación
│   │       └── workspace/             organización activa, sedes, miembros y resúmenes
│   │           ├── domain/            modelos, repositorio y casos de uso
│   │           ├── data/              adaptador remoto, DTO privados y traducción de errores
│   │           └── presentation/
│   │               ├── WorkspaceViewModel.kt   operaciones y estado observable
│   │               ├── WorkspaceState.kt       estado inmutable de presentación
│   │               ├── WorkspaceViewer.kt      datos públicos del usuario para esta feature
│   │               ├── WorkspaceDestination.kt destinos superiores tipados
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

`iosApp/` contiene el host SwiftUI. `jvmMain` no constituye un producto de
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
- **core/di:** única raíz de construcción. `AppGraph` comparte cliente y sesión,
  sin service locator ni singletons mutables. El resto de core no importa features.

Las funcionalidades no importan otras funcionalidades. Cuando un recorrido
necesite colaboración, se compone en app/DI mediante contratos explícitos; solo
se extrae código a core si existe reutilización comprobada. Los modelos del
resumen de workspace son proyecciones de su endpoint, no el modelo definitivo de
cursos, evaluaciones o ejecuciones de checklist.

## Ciclo de vida, navegación y contexto empresarial

`GraphOwner` es un ViewModel propietario de `AppGraph`: el cliente HTTP sobrevive
recomposiciones y cambios de configuración Android y se cierra al liberar al
propietario. Los ViewModels usan `viewModelScope`; la UI observa con
`collectAsStateWithLifecycle`.

La raíz distingue acceso y comprobación de conexión; un perfil confirmado abre
workspace. Los destinos actuales son secciones superiores tipadas: Inicio,
Aprender, Control, Equipo y Empresa. La selección se conserva con
`rememberSaveable`, asociada al usuario y organización. Formularios y menús se
reinician al cambiar de contexto. No se guarda el perfil ni un token en el estado
restaurable: reiniciar el proceso requiere iniciar sesión nuevamente.

El contenedor presenta navegación inferior en ventanas pequeñas y lateral desde
950 dp. Las pantallas comparten lógica; la distribución responde al ancho de la
ventana, no a si el dispositivo se llama celular o computadora.

Esta base todavía no implementa historial de detalles, enlaces profundos ni
sincronización con la URL del navegador. Al incorporar lecciones u otros detalles
se añadirá navegación multiplataforma con historial, atrás de Android y navegador,
sin convertir el enum de secciones en un router manual de toda la aplicación.

Cambiar organización cancela consultas y limpia el contenido anterior. Logout
cancela operaciones y limpia workspace y sesión. Antes de aplicar respuestas se
comprueba que la coroutine siga activa para evitar que una respuesta tardía
restaure datos de otro contexto. Las capacidades visibles proceden del perfil y
la membresía devueltos por API; el backend valida siempre permisos y aislamiento.
El selector de rol del prototipo no pertenece al flujo productivo.

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
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
git diff --check
```

La compilación iOS requiere macOS/Xcode. Si la Mac tiene seleccionadas solo las
Command Line Tools, ejecutar ese comando con
`DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`; no es necesario cambiar
la selección global. CI comprueba límites arquitectónicos,
pruebas web, código compartido, builds Android/web, imagen Docker y framework iOS.
Las pruebas con dobles no sustituyen probar login real, emulador, dispositivo,
accesibilidad y recorridos en navegador. Las pruebas automatizadas no crean
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

Disponibles: comprobación de conexión, login/registro/verificación/recuperación,
Google web, perfil confirmado por API, organizaciones autorizadas, altas de
organización/sede/miembro y catálogos del workspace.

Pendientes: ejecutar cursos/evaluaciones/checklists, acciones correctivas,
evidencias privadas, investigación CAP, reportes completos, navegación por roles
del prototipo, renovación automática y persistencia segura de sesión. Google
nativo requiere sus integraciones por plataforma.

No hay persistencia offline, cola ni service worker. Cuando un recorrido los
necesite, su repositorio observará almacenamiento local mediante Flow y
coordinará sincronización persistente por usuario/organización/sede, con UUID,
idempotencia y resolución de conflictos conforme al contrato de la API. La
propuesta de SQLDelight móvil e IndexedDB web requiere validación de compatibilidad
antes de elegir dependencias. No se muestra éxito de sincronización sin confirmación.

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
