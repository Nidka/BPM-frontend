# Reparto y siguientes recorridos

Esta entrega implementa conexión técnica y login/registro/perfil. El acceso real
requiere configurar Firebase y verificar email; el backend completa el perfil
mínimo según IDENTITY.md, sin asignar permisos sobre organizaciones.

| Área | Punto de entrada | Próximo trabajo |
|---|---|---|
| Base y despliegue | core/, web/, CI, Railway | Mantener configuración y contratos |
| Identidad | features/identity | Login/registro/recuperación listos; siguiente: Google nativo, renovación y persistencia segura |
| Organización activa | identity/domain y data | Validar membresía con el backend, seleccionar sede |
| Checklists | nueva features/checklists | Catálogo local, borradores, idempotencia y sincronización |
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

Identidad requiere Firebase configurado. /me rechaza usuarios sin perfil; el
cliente completa el registro mínimo por /auth/register solo con email verificado.
El cliente jamás determina roles/permisos definitivos ni reactiva cuentas inactivas.
