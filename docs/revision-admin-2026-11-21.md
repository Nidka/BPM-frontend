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
| **RF-45 Accesibilidad: subtítulos** | **Implementado (pendiente de desplegar V16)** | Subtítulos WebVTT por lección: pista nativa en Android y web, superpuesta en iOS, y transcripción |
| **RF-46/47 Consultas normativas** | **Implementado como buscador (pendiente de V17)** | Búsqueda en español sin tildes en la normativa que carga el SUPER_ADMIN; sin IA ni costo por consulta (decisión del equipo) |
| RF-48 Integración n8n | Implementado (pendiente de V18) | Webhooks HTTPS firmados con HMAC por tipo de aviso, gestionados por el ADMIN |
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

## Pendientes antes de producción

1. **Desplegar el backend en orden:** aplicar V16, V17 y V18 (`scripts/aplicar-migraciones.ps1`), luego desplegar
   la API y el worker, y al final el frontend. El frontend nuevo funciona contra el servidor actual: oculta los
   subtítulos y muestra el buscador y las automatizaciones como «aún no disponibles» hasta el despliegue.
2. **Cargar la normativa** (NTS 142, RM 822-2018/MINSA) desde «Buscar en la normativa → Normativa cargada».
3. **Revisión visual con cuentas reales** por rol a 360, 600, 840, 1000, 1200 y 1600 dp, y reproducción de
   videos con subtítulos en un Android y un iPhone reales.
4. **Base de datos de producción con `LC_CTYPE` UTF-8** (por ejemplo `en_US.UTF-8`, el valor por defecto en
   Railway). Con locale `C`, la paginación por nombre de operación diaria pierde registros con tildes; lo detectó la
   prueba de integración `TestDailyOperationsAllKeysetPagesAndLiteralQueries`.

