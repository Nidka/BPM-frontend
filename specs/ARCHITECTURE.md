# Arquitectura

KMP comparte lógica y UI entre Android, iOS y navegador. Railway aloja el bundle
web compilado a WebAssembly. Los binarios móviles se distribuyen por sus canales
respectivos.

Se usa un módulo Gradle `composeApp` para mantener simple la base; las fronteras
son paquetes. Se extraerán módulos al necesitar aislamiento de compilación o
trabajo entre equipos, conservando las mismas dependencias.

```text
composeApp/src/commonMain/kotlin/pe/bpm/
  app/                       entrada Compose y ciclo de vida
  core/
    config/                  configuración pública tipada
    di/                      composición manual AppGraph
    network/                 HttpClient y motores expect/actual
    domain/                  resultado y fallos técnicos compartidos
    designsystem/            tema y futuros componentes reutilizados
  features/connection/
    domain/                  modelo, puerto de repositorio y caso de uso
    data/                    DTO y adaptador Ktor
    presentation/            ViewModel, estado y pantalla
  features/identity/
    domain/                  perfil y casos de uso de sesión
    data/                    Firebase REST y perfil de la API
    presentation/            formulario, sesión y ViewModel
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

El cliente HTTP pertenece al ciclo de vida del ViewModel inicial: se registra
como recurso cerrable y se libera en onCleared, no al recomponer la pantalla.
Así sobrevive a cambios de configuración Android sin reutilizar un cliente cerrado.

`androidMain` usa OkHttp; `iosMain`, Darwin; `wasmJsMain`, Js. `jvmMain`
permite ejecutar pruebas compartidas sin emulador. No es un producto de escritorio.

La web llama a `/backend/health` y `/backend/ready` del mismo origen. El servidor
Node reenvía esas rutas y GET /backend/api/v1/me al backend HTTPS definido por
API_BASE_URL. Android/iOS consultan directamente el origen HTTPS configurado al
compilar. Los módulos futuros ampliarán el proxy con rutas y encabezados
autorizados explícitamente.

La base visual usa petróleo, fondo claro y acento naranja de la propuesta BPM.
El inicio implementa login/perfil y permite abrir la comprobación de conexión.
Ver IDENTITY.md. No hay persistencia, sincronización, registro público, selección
de tenant ni otros módulos de negocio.
