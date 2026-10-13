# Reparto y siguientes recorridos

Esta entrega solo implementa el recorrido técnico de conexión.

| Área | Punto de entrada | Próximo trabajo |
|---|---|---|
| Base y despliegue | core/, web/, CI, Railway | Mantener configuración y contratos |
| Identidad | nueva features/identity | Firebase cliente, sesión y GET /api/v1/me |
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

Para comenzar identidad, primero habilitar Firebase en el backend y registrar
el usuario interno. Un token válido sin usuario habilitado debe producir acceso
denegado. El cliente jamás determina roles/permisos definitivos.
