# Reportes, constancias y registro de uso (frontend)

Contrato fuente: `specs/REPORTS.md` del backend (RF-09, 16, 17, 27, 28, 29, 30, 39). Este documento
solo describe cómo lo consume el cliente KMP. Requiere el backend con la migración V10 antes de activar la UI.

## Estructura

- `features/reports/{domain,data,presentation}`: KPIs, cumplimiento, resumen, exportación CSV, avance de
  capacitación, configuración y constancias. Tres ViewModels: `DashboardViewModel` (panel),
  `ReportsViewModel` (cumplimiento/resumen/exportación) y `ReportsAdminViewModel` (capacitación,
  constancias, configuración). `CertificateVerificationViewModel` sirve la página pública.
- `features/usage/{domain,data,presentation}`: registro de uso (RF-27). `UsageTracker` crea los
  eventos, `UsageBatcher` los agrupa (50 máx.), `UsageEventQueue` es la cola y `UsageEventSink` el destino.
- `core/presentation/TextFileSaver` (expect/actual): guardar o compartir un archivo de texto.
- `app/ReportsContent`, `app/PublicVerification`, `app/UsageModules`: adaptadores de la raíz.

## Pantallas y rutas

| Ruta | Quién | Contenido |
|---|---|---|
| `inicio` (Home) | ADMIN, CHEF, SUPER_ADMIN | `DashboardPanel`: cumplimiento hoy/7/30 días, controles pendientes y atrasados, alertas e incidencias abiertas y avance de capacitación (oculto si el servidor entrega `training: null`, como para CHEF). |
| `reportes` | ADMIN, CHEF, SUPER_ADMIN | Cumplimiento (gráfico barras/línea + tabla equivalente + por sede), Resumen (por sede, plantilla, área, incidencias, temperaturas), exportación CSV. ADMIN además ve "Equipo y constancias": avance por curso y por persona (paginado), constancias y configuración. WORKER es redirigido. |
| `/verificar-cumplimiento/{codigo}` (web, sin login) | Cualquiera | Verifica la constancia contra `GET /compliance-certificates/{code}`. `main.kt` decide por `window.location.pathname`; `index.html` usa `/composeApp.js` absoluto para que el bundle cargue en esa ruta. |

## Decisiones

- Períodos: atajos de 7/30/90 días y "este mes", o fechas `AAAA-MM-DD`. Se valida antes de llamar
  (92 días cumplimiento; 366 resumen, exportación y constancias). "Hoy" se calcula en hora de Lima (UTC-5,
  sin horario de verano), igual que el backend por defecto. La constancia exige `to` anterior a hoy.
- Los porcentajes se muestran con un decimal; nunca se muestra texto del servidor, solo códigos públicos.
  `REPORT_THRESHOLD_NOT_MET` muestra promedio y umbral (únicos `fields` conservados).
- Constancias: 201 = nueva, 200 = ya emitida para ese período (se informa sin error).
- Exportación CSV: el repositorio descarga completo, comprueba tipo `text/csv`, tamaño (`Content-Length`),
  encabezado esperado y cierre de la última fila; si falla es `INCOMPLETE_DOWNLOAD` (reintentable). El nombre
  del archivo se arma localmente. `EXPORT_TOO_LARGE` pide acortar el período o filtrar por sede.
  Guardado: web descarga con Blob; Android usa el selector del sistema (CreateDocument); iOS la hoja de
  compartir; JVM escribe en la carpeta temporal. El texto se entrega como `String` UTF-8.
- Gráficos: Canvas propio (`ComplianceChart`, `PercentBar`) con colores del tema, etiquetas de eje,
  descripción accesible y tabla equivalente. Solo se dibujan días con ejecuciones.
- Las sedes del filtro salen del workspace (adaptadas en `App`); si el workspace pagina las sedes solo se
  ofrecen las cargadas. Hueco asumido: el contrato no tiene endpoint de sedes para reportes.
- Proxy: `web/reports-proxy.mjs` (rutas de `reports/*`, `usage/events` y la verificación pública). Valida
  consultas (sin repetidos ni desconocidos, fechas reales, UUID, paginación), cuerpos exactos (configuración,
  constancia, lote de uso ≤ 32 KB), reduce errores a códigos conocidos y entrega el CSV en streaming con
  `Content-Type: text/csv` y `Content-Disposition` reconstruido. La ruta pública no reenvía credenciales.
  Las respuestas JSON se reenvían tras comprobar `data`/`pagination` (no se re-proyecta cada campo).
  No se expone `usage/summary` porque el cliente no lo consume.

## Registro de uso (RF-27)

- Eventos: `SESSION_START`, `SESSION_END`, `MODULE_VISIT` (al salir del módulo, con duración),
  `ALERT_VIEWED`, `ALERT_ATTENDED`. Solo `id`, `sessionId`, `type`, `module`, `occurredAt`, `durationSeconds`:
  sin URL, identificadores de controles/cursos/alertas ni metadatos.
- Elegibilidad: persona con membresía activa (ADMIN/CHEF/WORKER). SUPER_ADMIN no registra, aunque tenga
  membresía (el cliente no puede distinguirlo con certeza; es la opción segura).
- Envío: lotes de hasta 50, al cambiar de pantalla, cada 60 s, al volver al primer plano y al cerrar sesión.
  Éxito y duplicados se quitan de la cola; 401/408/429/5xx/red se reintentan con los mismos UUID; otros 4xx
  (403, 409, 422) se descartan; eventos de más de ~7 días se descartan. Nada se propaga a la UI.
- Cola: `InMemoryUsageEventQueue` (500 máx.). Para la persistencia offline-first basta implementar
  `UsageEventQueue` y cambiarla en `AppGraph.usageQueue`; el lote y el rastreador no cambian. Cada evento
  en cola guarda persona y empresa; una persona nunca envía eventos de otra.
- Pendiente (backend): vincular al consentimiento informado de investigación.

## Verificación

Pruebas `commonTest` en `features/reports` y `features/usage` (casos de uso, DTO con MockEngine,
ViewModels, batcher/tracker) y `web/reports-proxy.test.mjs`. Android/iOS (`TextFileSaver`) no se
compilaron: sin Android SDK ni macOS; revisar en esos entornos.
