# Reparto y siguientes recorridos

Esta entrega implementa conexión técnica, login/registro, workspace y catálogo de controles. El acceso real
requiere configurar Firebase y verificar email; el backend completa el perfil
mínimo según IDENTITY.md, sin asignar permisos sobre organizaciones.

| Área | Punto de entrada | Próximo trabajo |
|---|---|---|
| Base y despliegue | core/, web/, CI, Railway | Mantener configuración y contratos |
| Identidad | features/identity | Login/registro/recuperación y Google web/Android listos; usuario confirmó Google en celular. iOS con SDK/adaptador y configuración pública: pendiente prueba real y validación adicional de almacenamiento seguro en dispositivos |
| Organización activa | features/workspace | Altas de empresa/sede/miembro y tenant autorizado listos; siguiente edición y bajas |
| Checklists | features/checklists | Catálogo, autoría y ejecución en línea con revisión, versión e idempotencia; recuperación local y reintentos duraderos; historial paginado, separación ejecución/supervisión y programación por sede/frecuencia implementados; cola múltiple para revisiones preparadas implementada; siguientes: inicio sin red, archivos y trabajo en segundo plano |
| Evidencias | features/media | Selección, vista previa privada, PUT firmado y confirmación implementados para controles; fotos de limpieza e incidencias pendientes |
| Operación diaria | features/operations | Catálogos, criterio térmico, recepciones, consulta PEPS y limpieza implementados; activar V5 en backend antes de desplegar. Fotos de limpieza, movimientos de stock, turnos y cola offline nueva pendientes |
| Academia | features/training | Detalle de curso, lecciones con texto y video (ExoPlayer, AVPlayer, <video> web), retoma desde la última lección, modo estudio sin calificación, evaluación DIAGNOSTIC/FINAL idempotente con constancia y autoría ADMIN con subida de video a R2 implementados localmente; ver TRAINING.md. Requiere el backend de Academia antes de activar la UI; subida de videos de más de 100 MiB desde el cliente y pruebas en dispositivo pendientes |
| Incidencias | features/incidents | Catálogo versionado, reporte por sede, asignación, corrección, fotos e historial implementados; activar V6 antes del despliegue. Constancia y notificaciones en módulos posteriores |
| Reportes y uso | features/reports, features/usage | Panel de indicadores en Inicio, cumplimiento por día/sede, resumen, exportación CSV, avance de capacitación, configuración, constancias con verificación pública web y registro de uso por lotes implementados; ver REPORTS.md. Requiere backend V10. Persistencia offline del registro de uso y pruebas Android/iOS del guardado de archivos pendientes |

Cada responsable debe entregar domain, data, presentation, pruebas y su contrato
actualizado. Se crean carpetas al implementar el primer recorrido, no antes.

Plantilla por feature:

```text
features/<nombre>/
  domain/          modelos, repositorio y casos de uso
  data/            DTO, adaptadores remotos/locales y repositorio concreto
  presentation/    UiState, ViewModel y pantallas
```

Pruebas mínimas: caso de uso con repositorio falso, traducción de respuestas API,
transiciones del ViewModel y errores sanitizados. Cuando se incorpore offline,
agregar reinicio sin red, reintentos duplicados, conflictos y aislamiento tenant.

La API disponible se documenta en:
https://bpm-service-production.up.railway.app/docs/

Ver `WORKSPACE.md` para el rediseño y `CHECKLISTS.md` para catálogo/detalle.
Aprender conserva su catálogo de base (cada curso abre el detalle de Academia); Control consulta preguntas de versiones
publicadas y permite ejecutar controles en línea. Lecciones, incidencias y el resto del contrato
offline del piloto siguen pendientes. OFFLINE.md delimita la recuperación local actual.

Identidad requiere Firebase configurado. /me rechaza usuarios sin perfil; el
cliente completa el registro mínimo por /auth/register solo con email verificado.
El cliente jamás determina roles/permisos definitivos ni reactiva cuentas inactivas.

Operación diaria y sus permisos se detallan en [OPERATIONS.md](OPERATIONS.md).
