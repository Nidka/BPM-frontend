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

Abrir Software/Frontend en Android Studio. Si no detecta el SDK, configurar
local.properties (ignorado), por ejemplo sdk.dir=/ruta/al/Android/sdk.

El origen API nativo se fija al compilar:
`./gradlew :composeApp:assembleDebug -PapiBaseUrl=https://api.ejemplo.com`.
Nunca incluye contraseñas ni credenciales de infraestructura.

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

Los builds web requieren un navegador con WasmGC. Esta entrega usa wasmJs; aún
no incluye fallback Kotlin/JS para navegadores antiguos.

Verificar antes del push:
```bash
node --test web/server.test.mjs
./gradlew :composeApp:jvmTest :composeApp:wasmJsBrowserDistribution :composeApp:assembleDebug
git diff --check
```

GitHub Actions repite estos controles, construye Docker y compila el framework
iOS en macOS. Railway puede esperar a que ambos jobs aprueben.
