# Guía del frontend BPM

Leer `specs/ARCHITECTURE.md`, `specs/DEVELOPMENT.md`, `specs/OFFLINE.md`,
`specs/FEATURES.md`, `specs/IDENTITY.md` y `RAILWAY.md` antes de cambiar la base.
Para UI, tenant y formularios de empresa/equipo leer también `specs/WORKSPACE.md`.
Toda pantalla nueva o cambiada sigue `specs/LAYOUT.md` (listado, formulario y detalle con medidas exactas y
componentes compartidos `BpmRecordCard`, `BpmPagedList`, `BpmFormLayout`/`BpmSection`/`BpmFormActions`, `BpmDetailPane`).

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
- Offline-first fase 1 (specs/OFFLINE.md, sección «Offline-first»): las features dependen de los puertos de `core/offline` (LocalStore, Outbox, SyncEngine, ConnectivityMonitor), nunca del driver. Toda escritura de negocio diferible lleva UUID e Idempotency-Key; no persistir tokens ni URL firmadas. Fase 2: las ViewModels de lectura reciben un `LocalFirstReader` (lo local primero y luego el servidor); una escritura encolada llega como `QUEUED` y se trata como éxito local pendiente (aviso + `PendingBadge`/`PendingOperationsNotice`), nunca como error de conexión; las pantallas administrativas que requieren servidor van dentro de `ServerOnly`. Los archivos grandes (videos) se escriben por bloques en `PrivateFileStore` y se verifican con SHA-256 (`VerifiedDownloader`); el servidor califica, el cliente nunca. Detalle en specs/OFFLINE.md, TRAINING.md y RESEARCH.md.
- Verificar `./gradlew :composeApp:jvmTest :composeApp:wasmJsBrowserDistribution :composeApp:assembleDebug`
  y `node --test web/server.test.mjs`; en macOS, compilar el framework iOS.
- El repositorio contiene exclusivamente Software/Frontend.

Estado de operación diaria e incidencias: leer `specs/OPERATIONS.md` y
`specs/INCIDENTS.md`. Los módulos y sus fotos privadas están implementados
localmente; su activación depende de V5/V6 y despliegue del backend antes de la UI.
No confundir compilación o aceptación aislada con publicación en Railway.

Academia BPM (lecciones con video, modo estudio sin calificación, evaluaciones y autoría): leer specs/TRAINING.md.
Está implementada localmente; su UI depende del backend de Academia. El reproductor Android/iOS
y los selectores de video nativos no se compilan sin Android SDK/macOS: verificarlos en esos entornos.

Ampliación de Academia (asignaciones, logros, rutas, evaluación por módulo, video de YouTube y verificación pública): ver la última sección de specs/TRAINING.md; requiere V11 del backend.

Alertas/reglas y registro de notificaciones Android: leer specs/ALERTS.md.
La activación necesita V7, API y worker antes del frontend; no implica despliegue.

Cuentas (edición de miembros/sedes/organización, invitaciones, dispositivos propios, tipo de establecimiento): leer specs/ACCOUNTS.md.
Requiere V12 del backend antes de activar la UI; no implica despliegue.
Estudio CAP (consentimiento, encuesta pre/post e investigación): leer specs/RESEARCH.md. Requiere el backend V9.
Reportes, constancias y registro de uso (RF-09/16/17/27/28/29/30/39): leer specs/REPORTS.md.
Requiere el backend con V10; el guardado de CSV en Android/iOS no se compila sin SDK/macOS.

Buscador de normativa (RF-46/47, sin IA) y automatizaciones con webhooks para n8n (RF-48): leer specs/NORMATIVE.md.
Requieren V17/V18 del backend; no implica despliegue.
