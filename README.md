# BPM Frontend

Base Kotlin Multiplatform con Compose, MVVM y Clean Architecture para Android,
iOS y web. Solo incluye una pantalla de conexión real con la API y PostgreSQL.

Repositorio: https://github.com/Nidka/BPM-frontend

```bash
./gradlew :composeApp:jvmTest :composeApp:wasmJsBrowserDistribution
node web/server.mjs
```

Abrir http://localhost:3000. Para Android usar
`./gradlew :composeApp:assembleDebug`. El host iOS está en `iosApp/`.

- [Arquitectura](specs/ARCHITECTURE.md)
- [Desarrollo y requisitos](specs/DEVELOPMENT.md)
- [Contrato offline pendiente](specs/OFFLINE.md)
- [División del trabajo](specs/FEATURES.md)
- [Railway y appbpm.com](RAILWAY.md)

La configuración usa el backend público de Railway. Ninguna credencial de
PostgreSQL, Firebase Admin o R2 pertenece a este repositorio.

No hay módulos funcionales, autenticación, almacenamiento local ni sincronizador
implementados todavía; sus fronteras están documentadas para continuar.
