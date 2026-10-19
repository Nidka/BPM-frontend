# Desarrollo

Requisitos: JDK 21, Android SDK 36 para Android y Xcode para iOS.
Las versiones están fijadas en gradle/libs.versions.toml y el wrapper de Gradle.

Desde la raíz del repositorio:

```bash
./gradlew :composeApp:jvmTest
./gradlew :composeApp:wasmJsBrowserDistribution
node web/server.mjs
```

Abrir http://localhost:3000. El servidor usa el backend Railway por defecto.
El archivo .env.example documenta variables; Node no carga .env implícitamente.
Se puede usar `node --env-file=.env web/server.mjs` después de copiar y editar
el ejemplo. Solo contiene configuración pública del frontend.

Desarrollo web con recarga del compilador:
`./gradlew :composeApp:wasmJsBrowserDevelopmentRun`.
Ese servidor Gradle no incluye el proxy Node. Para comprobar conexión web real,
usar la distribución con Node como se indica arriba.

Android:
```bash
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:installDebug
```

Abrir la raíz de BPM-frontend (donde está settings.gradle.kts) en Android Studio.
Si no detecta el SDK, configurar
local.properties (ignorado), por ejemplo sdk.dir=/ruta/al/Android/sdk.

El origen API nativo se fija al compilar:
`./gradlew :composeApp:assembleDebug -PapiBaseUrl=https://api.ejemplo.com`.
Nunca incluye contraseñas ni credenciales de infraestructura.
El login usa config/firebase.public.json por defecto. Para otro entorno nativo,
agregar `-PfirebaseApiKey=<Web-API-Key-pública>` al build.
El proveedor y la cuenta se preparan según IDENTITY.md.
Google Android usa Credential Manager y el ID público OAuth Web del mismo proyecto,
ya incluido en `config/firebase.public.json`; el build habitual lo incorpora.
Para otro entorno se admite `-PgoogleWebClientId=<ID-OAuth-Web>` o
`GOOGLE_WEB_CLIENT_ID` durante compilación.
En la app Firebase `bpm.app`, registrar las huellas de `:composeApp:signingReport`.
El applicationId Android coincide con ese registro. Se retiró la instalación
anterior del emulador y el usuario confirmó Google en su celular. Los próximos
APK con otra firma requieren registrar su huella; no introducir secretos OAuth.

iOS:
```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
brew install xcodegen
cd iosApp
xcodegen generate
open BPMApp.xcodeproj
```

El host SwiftUI y el proyecto declarativo project.yml están incluidos. Xcode
invoca embedAndSignAppleFrameworkForXcode antes del build. Para dispositivo real,
configurar el equipo de firma. El framework Kotlin puede compilarse sin firma.

El host incluye GoogleSignIn 10.0.0 mediante Swift Package Manager; el registro
Firebase `pe.bpm.app` y su ID público OAuth iOS ya están configurados mediante
`googleIosClientId` en el JSON público. El build genera el esquema de retorno en
`composeApp/build/generated/config/ios/App-Info.plist`. No editarlo a mano.
La prueba real iOS requiere el registro y consentimiento del titular; ver IDENTITY.md.
La configuración actual compila para iPhone arm64 y simuladores Apple Silicon.
Para verificar también el host Swift, ejecutar `bash scripts/build-ios.sh` con
XcodeGen disponible; `XCODEGEN_BIN` admite su ejecutable en una carpeta temporal.
El script respeta `DEVELOPER_DIR`, no cambia xcode-select y usa las versiones de
`iosApp/Package.resolved`. `BPM_IOS_DERIVED_DATA` permite aislar el build.
El Info.plist generado incluye `CADisableMinimumFrameDurationOnPhone=true`,
requisito de arranque de Compose iOS. El script comprueba la propiedad en el
binario construido; no deshabilita la comprobación de Compose.
El host del simulador se firma ad hoc con «Sign to Run Locally», sin certificados
Apple ni equipo personal. Esa firma permite comprobar Keychain; un bundle sin
firma no representa correctamente el comportamiento del almacenamiento seguro.
`iosApp/UITests/IdentityStartupTests.swift` verifica el arranque real, la ausencia
de error de Keychain y el botón Google habilitado, sin autenticar usuarios.
Se ejecuta en un simulador exclusivo de BPM con `xcodebuild test` y destino por
UUID; no usar un simulador con sesiones de otro proyecto. CI usa su VM aislada
y ejecuta esta prueba después de construir el host nativo.

Si `xcode-select -p` apunta solo a CommandLineTools, seleccionar Xcode para esa
compilación sin cambiar la configuración global:

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Los builds web requieren un navegador con WasmGC. Esta entrega usa wasmJs; aún
no incluye fallback Kotlin/JS para navegadores antiguos.

Verificar antes del push:
```bash
node --test scripts/*.test.mjs web/*.test.mjs
./gradlew :composeApp:jvmTest :composeApp:wasmJsBrowserDistribution :composeApp:assembleDebug
git diff --check
```

GitHub Actions repite estos controles, construye Docker y compila el framework
Kotlin y el host Swift con el SDK Google en macOS. Railway puede esperar a que
ambos jobs aprueben.
