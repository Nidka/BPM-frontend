# BPM Frontend

Base Kotlin Multiplatform con Compose, MVVM y Clean Architecture para Android,
iOS y web. Incluye login y registro Firebase, perfil confirmado por la API,
espacio de trabajo autorizado y una comprobación real de conexión con el backend.

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
- [Login y configuración Firebase](specs/IDENTITY.md)
- [Diseño y espacio de trabajo](specs/WORKSPACE.md)
- [División del trabajo](specs/FEATURES.md)
- [Railway y appbpm.com](RAILWAY.md)

La configuración usa el backend público de Railway. Ninguna credencial de
PostgreSQL, Firebase Admin o R2 pertenece a este repositorio.

El acceso real requiere configurar Firebase. El registro completa el perfil
USER en PostgreSQL después de verificar el email, sin asignar organizaciones.
El espacio de trabajo permite crear organización a SUPER_ADMIN, y sedes o
miembros a administradores autorizados. Aprender y Control muestran catálogos
reales; su ejecución, almacenamiento local y sincronización están pendientes.
