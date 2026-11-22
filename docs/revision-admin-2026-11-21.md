# Revisión del rol Administrador frente a los requerimientos — 21/11/2026

Fuente de requerimientos: `Requerimientos/ResumenProyectoBPM.md` (RF-01 a RF-50, RNF). Se revisó el código del
frontend (KMP) y del backend (Go) en `main` al 21/11/2026. «ADMIN» es el administrador de una empresa; «SUPER_ADMIN»
es el administrador general de la plataforma, que es el rol que el documento llama «Administrador».

## Módulos del ADMIN en la app

Inicio (panel), Control (operar como chef en todas sus sedes), Aprender, Seguimiento, Equipo y Empresa: cuentas y
equipo, invitaciones, organización y sedes, plantillas de control (crear, versiones, programar), controles
programados, Academia (cursos, asignaciones, insignias, rutas), reglas de avisos, reportes y constancias,
insumos y limpieza, e incidencias.

## Cumplimiento

| Requerimiento | Estado | Evidencia / observación |
|---|---|---|
| RF-01 Empresas y usuarios con 3 roles | Cumple | Alta de persona, invitaciones por correo/enlace con rol y sedes |
| RF-02/03 Login y recuperación | Cumple | Firebase Auth; la recuperación se envía desde el cliente |
| RF-04 Crear, editar y desactivar cuentas | Cumple | `TeamPane`: editar rol/sedes, desactivar, reactivar, transferir administración |
| RF-05 Expiración configurable | Parcial | Configurable en el backend (`config`); el ADMIN no lo ajusta desde la app (decisión de plataforma) |
| RF-06/07/08/10/11 Academia | Cumple | Asignación a persona, rol o sede; lecciones, evaluaciones, XP, logros y retoma |
| RF-09 Avance consolidado por empresa | Cumple | Reportes: avance por curso y persona |
| RF-12–15 Checklists, temperatura y fotos | Cumple | Plantillas con criterio mínimo/máximo, evidencia ante incumplimiento |
| RF-16/17/28 Cumplimiento y exportación | Cumple | Reportes por período y sede con CSV |
| RF-18–22 Alertas | Cumple | Reglas `CHECKLIST_MISSING`, `TEMPERATURE_DANGER`, `ORGANIZATION_INACTIVE`, `TRAINING_ASSIGNED`; visto/atendido |
| RF-27/29/30 Uso, envío semanal y panel | Cumple | Registro de uso, `WeeklyJob` del worker, indicadores en Inicio y Reportes |
| RF-31–34 Recepción PEPS y limpieza | Cumple | Operación diaria: lotes, consumos, descartes, plan de limpieza con fotos |
| RF-35–38 Incidencias y acciones correctivas | Cumple | Catálogo por norma, plan con responsable y plazo, fotos antes/después, validación |
| RF-39 Constancia interna | Cumple | Constancias con umbral configurable y verificación pública |
| RF-40–44 Rutas y evaluaciones | Cumple | Rutas de aprendizaje, evaluación diagnóstica/por módulo/final |
| **RF-45 Accesibilidad: subtítulos** | **No cumple** | Los videos no tienen pistas de subtítulos (ni en el modelo ni en el reproductor). Contraste y teclado sí |
| **RF-46/47 Agente IA normativo** | **No implementado** | No existe en frontend ni backend. Es función del SUPER_ADMIN («configura agente IA») |
| RF-48 Integración n8n | No implementado | Opcional según el documento |
| RF-49 Tipo de establecimiento | Cumple | Restaurante, cafetín, catering, otro servicio |
| RF-50 Necesidades detectadas | Cumple | Investigación (SUPER_ADMIN / investigación) |
| **RNF Usabilidad: mínimo 14 sp** | **Corregido** | Texto de apoyo y etiquetas estaban en 13 sp y los chips en 12 sp; ahora 14 sp |
| **RNF Responsivo (RF-45, usabilidad)** | **Corregido** | Ver «Diseño adaptable» abajo |

## Diseño adaptable (corregido en esta rama)

1. **Lista–detalle roto entre 840 y 1199 dp de ventana.** La regla usaba la clase de ventana; con el menú lateral
   de 240 dp el detalle quedaba en 152–511 dp junto a la lista de 360. Ahora el detalle va al lado solo desde
   864 dp útiles y, si no, ocupa la pantalla con «Volver».
2. **Rejilla de 2 columnas a 600 dp** dejaba tarjetas de ~228 dp. Ahora las columnas salen del ancho útil
   (1 < 616 · 2 hasta 931 · 3 desde 932).
3. **Formularios sin medida común** (640, 700, 900 dp o todo el ancho). Ahora todos van en `BpmFormLayout`
   (720 dp alineado al título) con `BpmSection` y `BpmFormActions`.
4. **Componentes duplicados por módulo** (`PagedBlock`, `WorkspacePagedList`, `ConfirmCard`, `StatusFilter`) y
   cuatro variantes de botones de formulario. Ahora usan `BpmPagedList`, `BpmConfirmPanel`, `BpmFilterChips` y
   `BpmFormActions`; el verificador (R15, R16) impide que vuelvan.
5. **Detalle con dos botones principales** (plantilla: «Preparar revisión» y «Programar en una sede»; incidencias:
   varias acciones del flujo). Ahora `BpmDetailPane` con un solo principal.

Contrato completo: `specs/LAYOUT.md`.

## Pendientes recomendados antes de producción

1. **Subtítulos de video (RF-45):** campo de subtítulos (WebVTT) por lección en el backend, carga en la autoría
   y pistas en ExoPlayer/AVPlayer/`<video>`.
2. **Agente IA normativo (RF-46/47):** decidir si entra en el alcance del piloto; hoy no existe.
3. **Detalles aún con estructura propia:** control programado, revisión (historial) y operación diaria siguen sin
   `BpmDetailPane`; migrarlos con el mismo patrón.
4. **Revisión visual con datos reales** por rol a 360, 600, 840, 1000, 1200 y 1600 dp (faltan cuentas de prueba).
