# Reparto y siguientes recorridos

Esta entrega implementa conexión técnica, login/registro, workspace y catálogo de controles. El acceso real
requiere configurar Firebase y verificar email; el backend completa el perfil
mínimo según IDENTITY.md, sin asignar permisos sobre organizaciones.

| Área | Punto de entrada | Próximo trabajo |
|---|---|---|
| Base y despliegue | core/, web/, CI, Railway | Mantener configuración y contratos |
| Identidad | features/identity | Login/registro/recuperación y Google web/Android listos; usuario confirmó Google en celular. iOS con SDK/adaptador y configuración pública: pendiente prueba real y validación adicional de almacenamiento seguro en dispositivos |
| Organización activa | features/workspace | Altas de empresa/sede/miembro y tenant autorizado listos; siguiente edición y bajas |
| Checklists | features/checklists | Catálogo, autoría y ejecución en línea con revisión, versión e idempotencia; recuperación local y reintentos duraderos; historial paginado, separación ejecución/supervisión y programación por sede/frecuencia implementados; siguiente: cola múltiple y sincronización offline |
| Evidencias | nueva features/media | Archivos locales, PUT firmado, confirmar y descargar |
| Academia | nueva features/training | Catálogo, progreso local y evaluaciones |
| Incidencias | nueva features/incidents | Registro, estados y acciones correctivas |

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
Aprender conserva su catálogo de base; Control consulta preguntas de versiones
publicadas y permite ejecutar controles en línea. Lecciones, incidencias y
sincronización completa siguen pendientes. OFFLINE.md delimita la recuperación local actual.

Identidad requiere Firebase configurado. /me rechaza usuarios sin perfil; el
cliente completa el registro mínimo por /auth/register solo con email verificado.
El cliente jamás determina roles/permisos definitivos ni reactiva cuentas inactivas.
