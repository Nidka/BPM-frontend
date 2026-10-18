# Guía del frontend BPM

Leer `specs/ARCHITECTURE.md`, `specs/DEVELOPMENT.md`, `specs/OFFLINE.md`,
`specs/FEATURES.md`, `specs/IDENTITY.md` y `RAILWAY.md` antes de cambiar la base.
Para UI, tenant y formularios de empresa/equipo leer también `specs/WORKSPACE.md`.

- Escribir comentarios, KDoc, GoDoc y documentación nueva en español; conservar los identificadores técnicos y nombres de API.
- KMP + Compose Multiplatform, MVVM, Clean Architecture por features.
- `core/di/AppGraph` compone dependencias manualmente; no usar service locators.
- Dominio no importa Compose, Ktor, SDK Firebase, almacenamiento ni DTO.
- ViewModel depende de casos de uso; UI observa StateFlow y emite acciones.
- DTO y repositorios concretos viven en data; errores públicos son sanitizados.
- No mostrar conexión exitosa con datos simulados ni confundir ausencia de red con éxito offline.
- Android/iOS y web comparten UI y lógica; adaptadores específicos van en su source set.
- Nuevas features se desarrollan como recorridos completos bajo features/.
- Compartir componentes solo cuando exista reutilización real.
- Ninguna clave PostgreSQL, cuenta de servicio Firebase ni credencial R2 va al cliente.
- Login confirma /me; no simular éxito ni persistir contraseña/token en el navegador.
- Toda operación empresarial futura valida tenant/permisos en el backend.
- Offline sigue el contrato de specs/OFFLINE.md; aún no hay persistencia ni sincronizador.
- Verificar `./gradlew :composeApp:jvmTest :composeApp:wasmJsBrowserDistribution :composeApp:assembleDebug`
  y `node --test web/server.test.mjs`; en macOS, compilar el framework iOS.
- El repositorio contiene exclusivamente Software/Frontend.
