# Plan: rediseño del rol Personal (WORKER)

Fuente de diseño: `/Users/helcorp/Downloads/Proyectos/BPM/Documentacion/Propuestas/rediseno-personal/index.html`.
Hechos del código: `docs/plans/hechos-rediseno-personal.md` (léelo: tiene firmas, líneas y riesgos).
Rama: `feat/personal-redesign`. No hacer commit ni push.

## Reglas para todos los paquetes

- Seguir `AGENTS.md`: comentarios/KDoc en español, MVVM, la UI observa StateFlow, nada de datos simulados.
- **No inventar datos.** No existen: racha diaria, % por curso, lecciones completadas, resumen de lección,
  «pendiente de hoy». Donde la propuesta los muestra, usar lo que sí existe (indicado abajo) u omitir.
- Componentes nuevos en `core/designsystem` (paquete `pe.bpm.core.designsystem`), genéricos, sin importar features.
  Reutilizar primero: `BpmCard`, `RaisedButton`, `BpmSecondaryButton`, `BpmTextButton`, `BpmChoiceRow`,
  `BpmFilterChip`, `BpmActionBar`, `BpmPageHeader`, `BpmCardGrid`, `BpmGridLayout`, `EmptyState`, `Eyebrow`,
  `Mascot`, `BpmIcon`, `BpmTokens`, `BpmSpacing`, `BpmType`, `bpmAppear`, `PendingBadge`, `PendingOperationsNotice`.
- Responsivo con `currentWindowClass`: Compact (<600) una columna y acción principal en `BpmActionBar`
  (fija abajo); Medium+ contenido + panel de apoyo. Objetivos táctiles ≥ 48 dp.
- Botones de acción que requieren servidor: usar `RaisedButton`/`BpmSecondaryButton` (ya respetan `ServerOnly`).
- Campos nuevos en data classes: **al final y con valor por defecto** (los tests construyen posicionalmente).
- Otros roles: la navegación, Inicio, Tareas y Empresa de ADMIN/CHEF/SUPER_ADMIN no cambian. El recorrido
  de aprendizaje (Aprender, curso, lección, evaluación) es la experiencia de alumno y sí cambia para todos
  los que aprenden; es intencional (mismo flujo de alumno).
- Compilar con `./gradlew :composeApp:compileKotlinJvm -q` (timeout 600000) hasta que pase.
  Pruebas puntuales: `./gradlew :composeApp:jvmTest --tests '<patrón>'`.

## WP1 · Componentes del design system

Crear (un archivo por grupo) y probar las funciones puras:

1. `BpmTone.kt`: `enum class BpmTone { Neutral, Info, Success, Warning, Danger }` + `@Composable fun BpmTone.colors(): Pair<Color, Color>`
   (fondo, texto) con `BpmTokens` (Info = soft/primary, Neutral = 0xFFEEF1F1/muted).
2. `BpmStatusChip.kt`: `BpmStatusChip(text: String, tone: BpmTone = BpmTone.Info, modifier: Modifier = Modifier, symbol: BpmSymbol? = null)`
   — píldora 12 sp bold, icono 14 dp opcional. Sirve como chip de vencimiento/estado.
3. `BpmProgress.kt`:
   - `BpmProgressRing(progress: Float, label: String, contentDescription: String, modifier: Modifier = Modifier, size: Dp = 64.dp, strokeWidth: Dp = 7.dp)`
     (Canvas, pista `border`, arco `primary`, texto centrado tabular).
   - `BpmSegmentedProgress(total: Int, completed: Int, contentDescription: String, modifier: Modifier = Modifier, current: Int? = null)`
     (segmentos 6 dp redondeados, hechos = primary, actual = accent).
4. `BpmSegmentedTabs.kt`: `data class BpmTabOption(val id: String, val label: String, val count: Int? = null)`;
   `BpmSegmentedTabs(options: List<BpmTabOption>, selectedId: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier)`
   — contenedor gris redondeado, seleccionada en blanco, contador en píldora accent, `Role.Tab`, 48 dp.
5. `BpmSupportPaneLayout.kt`: `fun supportPaneWidth(windowClass: BpmWindowClass): Dp?` (Compact null, Medium 260, Expanded/Large 300) y
   `BpmSupportPaneLayout(modifier: Modifier = Modifier, supportFirstOnCompact: Boolean = false, support: @Composable ColumnScope.() -> Unit, main: @Composable ColumnScope.() -> Unit)`
   — Compact: columna (main luego support, o al revés); si no, `Row` main `weight(1f)` + support de ancho fijo, `spacedBy(BpmSpacing.grid)`, alineado arriba.
6. `BpmNextStepCard.kt`: `BpmNextStepCard(eyebrow: String, title: String, actionLabel: String, onAction: () -> Unit, modifier: Modifier = Modifier, supporting: String? = null, progress: Float? = null, showMascot: Boolean = true)`
   — superficie `primary` con texto blanco, barra opcional blanca, botón blanco con relieve; `Mascot(learning = true)` a la derecha solo desde Medium.
7. `BpmStepList.kt`: `enum class BpmStepState { Done, Current, Pending }`;
   `data class BpmStep(val id: String, val title: String, val detail: String? = null, val state: BpmStepState = BpmStepState.Pending, val badge: String? = null, val highlight: Boolean = false)`;
   `BpmStepList(steps: List<BpmStep>, modifier: Modifier = Modifier, dense: Boolean = false, onStep: ((BpmStep) -> Unit)? = null)`
   — círculo numerado (hecho = check en successText, actual = primary relleno y fila con fondo soft), línea conectora, fila ≥ 48 dp clicable,
   `badge` como `BpmStatusChip` a la derecha, `highlight` (para la evaluación final) con círculo warning.
8. `BpmTaskRow.kt`: `BpmTaskRow(title: String, symbol: BpmSymbol, modifier: Modifier = Modifier, detail: String? = null, tone: BpmTone = BpmTone.Info, selected: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null)`
   — tarjeta blanca 14 dp, icono en cuadro 40 dp con el tono, título bold, detalle muted, borde primary 2 dp si `selected`.
9. `BpmQuestionNavigator.kt` (**reemplaza el bloque «Tus respuestas» que se veía mal**):
   `enum class BpmQuestionMark { Answered, Current, Unanswered }`;
   `fun questionNavigatorSummary(marks: List<BpmQuestionMark>): String` → «7 de 10 respondidas · faltan 8, 9 y 10»;
   con más de 3 faltantes «… · faltan 8, 9, 10 y 2 más»; todas → «Todas respondidas». La actual cuenta como respondida solo si tiene respuesta: recibir
   `answered: List<Boolean>` y `current: Int` en lugar de marks si es más simple (`fun questionMarks(answered, current)`).
   `BpmQuestionNavigator(answered: List<Boolean>, current: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, wrap: Boolean = false)`
   — resumen arriba (bodySmall bold), luego chips numerados 44 dp: respondida = fondo soft + check pequeño + número primary,
   actual = relleno primary texto blanco, sin responder = contorno fieldBorder. `wrap=false` → `LazyRow` desplazable (Compact),
   `wrap=true` → `FlowRow` (panel lateral). Leyenda de una línea con tres puntos de color. Semántica: «Pregunta 3, respondida».
   Nada de cuadrícula de cuadrados sueltos.
10. `BpmRewardSummary.kt`: `BpmRewardSummary(experiencePoints: Int, earnedBadges: Int, totalBadges: Int, modifier: Modifier = Modifier, nextBadgeTitle: String? = null, nextBadgeHint: String? = null, onOpen: (() -> Unit)? = null)`
    — tarjeta con «N XP» grande, «x de y insignias», próxima insignia; botón de texto «Ver mis logros» si `onOpen`.
11. `BpmCourseCard.kt`: `BpmCourseCard(code: String, title: String, meta: String, onClick: () -> Unit, modifier: Modifier = Modifier, colorIndex: Int = 0, progress: Float? = null, status: (@Composable () -> Unit)? = null)`
    — banda superior 72 dp con color de una paleta fija de 4 tonos derivados de `BpmTokens` (primary, text, successText, 0xFF7A3A12) y el código; título, meta, barra opcional y estado.
12. Iconos: añadir a `BpmSymbol` `Play, Award, Lock, Camera, Download, ChevronRight, Close, Route, Book`
    con drawables `ic_*` en el mismo formato y carpeta que los existentes (Lucide, trazo 2).
13. Pruebas en `commonTest/.../core/designsystem/`: `questionNavigatorSummary` (0, algunas, >3 faltantes, todas) y `supportPaneWidth`.

## WP2 · Navegación del rol Personal + pantalla Hoy

- `WorkspaceDestination.kt`: agregar `fun destinationLabel(destination: WorkspaceDestination, role: String?): String`
  que devuelve «Hoy» para Home y «Tareas» para Tasks solo si `role == "WORKER"`; si no, `destination.label`.
  Usarla en `WorkspaceScreen` (etiquetas de rail/menú/barra y título de página de secciones). Mantener «Hola, Nombre» como título de Home.
  Actualizar `WorkspaceNavigationTest`: WORKER → `["Hoy","Tareas","Aprender"]`; el resto igual. Perfil sigue en el avatar de la cabecera.
- Nuevo `features/workspace/presentation/WorkerToday.kt` (o en `app/` si necesita VMs de training; preferir `app/WorkerTodayContent.kt`):
  `WorkerTodayContent(learner: TrainingLearnerViewModel, userId, organizationId, onOpenCourse, onLearn, onTasks, onReport, onAchievements)`.
  - Llama `learner.open(userId, organizationId, member = true)` y `learner.loadAchievements()` en `LaunchedEffect`.
  - **Siguiente paso** (`BpmNextStepCard`): de `state.assignments` elegir IN_PROGRESS, si no OVERDUE, si no PENDING (por `dueAt` ascendente).
    Título «Continúa: {curso}» o «Empieza: {curso}», supporting «Vence el {fecha}» si hay `dueAt`, botón «Continuar curso»/«Empezar curso» → `onOpenCourse`.
    Sin asignaciones pendientes: tarjeta «Todo al día» con `Mascot` y botón secundario «Explorar cursos» → `onLearn`.
  - **Tu turno** (tarjeta): dos `BpmTaskRow` reales: «Registrar limpieza» (symbol Control) → `onTasks`; «Incidencias y correcciones» (Alert) → `onReport`.
    No listar tareas con hora (no hay modelo de pendientes).
  - **Capacitaciones asignadas**: hasta 3 `BpmTaskRow` con `BpmStatusChip` (OVERDUE Danger «Vencida», con fecha Warning «Vence {fecha}», COMPLETED Success).
  - Panel de apoyo: `BpmRewardSummary` con `achievements.totals.experiencePoints`, `earned.size`, total, próxima insignia = primera de `pending` con `criterionLabel`; botón «Reportar un problema» (`BpmSecondaryButton` con Camera) → `onReport`.
  - Layout: `BpmSupportPaneLayout`; en Compact el orden es Siguiente paso, Tu turno, Asignadas, Recompensas, Reportar.
- `App.kt`: para `currentWorkspace.role == "WORKER"` pasar `homeContent = { WorkerTodayContent(...) }`.
  `WorkspaceScreen` Home: si `current.role == "WORKER" && homeContent != null` dibujar solo `homeContent()` (no el `Home()` genérico). Otros roles sin cambios.
  `formatDate`: crear helper de fecha legible «25 nov» en `features/training/presentation` (reemplaza `dateLabel` donde se use en UI nueva).

## WP3 · Aprender + detalle de Curso

- **Aprender** (rama Learn de `WorkspaceScreen` + `TrainingLearnerHeader`/`MyAssignmentsPanel`):
  - Quitar `BpmIntroCard("Un hábito a la vez.")` de la rama Learn cuando hay `learnHeader`.
  - Reescribir `MyAssignmentsPanel` como hub dentro de `BpmSupportPaneLayout`:
    main: tarjeta **Continuar** (asignación IN_PROGRESS → `BpmProgressRing` sin % inventado: usar etiqueta «En curso»; anillo solo si hay dato; si no, icono Play) con `RaisedButton("Continuar")`;
    **Asignadas a ti** con `BpmCourseCard` + `BpmStatusChip` de vencimiento (Compact: `LazyRow` de tarjetas de 260 dp; Medium+: `BpmCardGrid`);
    **Rutas**: botón-tarjeta que lleva a rutas (`onPaths`).
    support: `BpmRewardSummary` (llamar `loadAchievements()` si `member`) con `onOpen = onAchievements`.
  - Catálogo (`Entries` con `onOpenCourse`): usar `BpmCourseCard` + `BpmStatusChip` por estado, filtros `BpmFilterChip` Todos / Sin iniciar / En curso / Completados (filtrado local de la página), y ocultar los cursos ya asignados (pasar `hiddenCourseIds: Set<String>` desde App.kt con los ids de `learner.state.assignments`). Mantener paginación.
- **Curso** (`CourseOverview` en `TrainingScreen.kt`):
  - Quitar el `widthIn(max = 860.dp)` del contenedor para OVERVIEW (usar `BpmSpacing.contentMax`); LESSON/STUDY/EVALUATION siguen con ancho de lectura 720 dp en la columna principal.
  - Cabecera: Eyebrow «{código} · {min} min · {n} lecciones», título, descripción, aviso de copia local y `PendingBadge` como hoy.
  - Función pura nueva en `features/training/presentation/CourseSteps.kt`: `fun lessonStepStates(course: TrainingCourse): List<BpmStepState>`
    — COMPLETED: todas Done; NOT_STARTED o sin `lastLessonId`: primera Current; si no: antes de `lastLessonId` Done, esa Current, resto Pending. (Es una aproximación: documentarla en KDoc). Probarla en commonTest.
  - Main: `BpmStepList` con lecciones (detail «Video · N min» no existe: usar «Incluye video»/«Lectura»/«Video de YouTube · requiere conexión»), y un último paso `highlight` «Evaluación final» (detail «Aprueba con {passingScore} · emite constancia») → `startEvaluation(FINAL)` (solo si `canPersist`). Debajo, enlace «Evaluación diagnóstica».
  - Support: `BpmProgressRing` con lecciones Done/total (de `lessonStepStates`), texto de estado/mejor puntaje/XP, acción principal (`RaisedButton` «Continuar lección n» / «Empezar el curso» / «Repasar el curso» → `resume`), `BpmTextButton` «Practicar sin calificación» → `startStudy()`, `DownloadsCard`, `CertificateCard` o placeholder «Tu constancia aparecerá aquí cuando apruebes la evaluación final», `AttemptsHistory`.
  - Compact: además publicar la acción principal en `BpmActionBar`.

## WP4 · Lección, estudio, evaluación y resultado

- **Lección** (`LessonViewer`): `BpmSegmentedProgress(total = lecciones, completed = índice, current = índice)`, título, video (no tocar modificadores del reproductor), contenido en tarjeta de lectura.
  Tarjeta «Comprueba lo aprendido · no se califica» con «N preguntas de esta lección» y `BpmSecondaryButton("Practicar esta lección")` → `startStudy(lesson.id)` solo si hay preguntas con ese `lessonId`.
  Enlace `BpmTextButton` «Evaluar esta lección (suma XP)» → `startModuleEvaluation` (mismas condiciones de hoy).
  Acciones: `BpmActionBar { Row: BpmSecondaryButton(icono Back, «Anterior»/solo icono en Compact) + RaisedButton("Siguiente lección" | "Terminar lecciones") }`. Quitar «Volver al curso» (el atrás de la cabecera ya vuelve).
  Expanded+: a la derecha `BpmStepList(dense = true)` del temario para saltar de lección (`selectLesson`).
- **Estudio** (`StudyPractice`): agregar `BpmSegmentedProgress` y mantener el resto; acciones de feedback en `BpmActionBar` en Compact.
- **Evaluación una pregunta a la vez**:
  - `EvaluationState`: agregar al final `index: Int = 0` y `reviewing: Boolean = false`.
  - `TrainingViewModel`: `goToQuestion(index)`, `nextQuestion()`, `previousQuestion()`, `openReview()`, `closeReview()` (sin cambiar envío; ignorar si `submitting`/`queued`/`outcome`). Al responder no avanzar solo.
  - Pruebas en `TrainingViewModelTest` (o archivo nuevo): navegación acotada, revisión, que el envío sigue igual.
  - UI `EvaluationPanel`: Eyebrow tipo, `BpmSegmentedProgress`, «Pregunta i de n», enunciado `titleLarge`, opciones `BpmChoiceRow`.
    `BpmQuestionNavigator` (Compact: `wrap=false` sobre las acciones; Expanded+: en el panel de apoyo con `wrap=true` dentro de una tarjeta titulada «Progreso de la evaluación»).
    Acciones (`BpmActionBar`): Anterior (secundario) + «Siguiente pregunta»; en la última «Revisar respuestas».
    Revisión: lista de preguntas con la respuesta elegida o «Sin responder» (tono Warning) y botón «Cambiar» → `goToQuestion`; `RaisedButton("Enviar evaluación")` habilitado con `canSubmitEvaluation()`; «Salir sin enviar».
    Mantener los estados de envío, error, `queued` y reintento de hoy.
- **Resultado** (`EvaluationResult`): arriba `Mascot` (si aprobó) + `BpmStatusChip` Aprobado/No aprobado + puntaje grande «85 / 100» + «mínimo X» + XP; `AchievementCelebration`; `CertificateCard` destacada;
  «Revisar respuestas» plegable (cerrado por defecto) con el detalle por pregunta de hoy; acciones de hoy («Repasar en modo estudio», «Intentar otra vez», «Volver al curso»).

## WP5 · Tareas del turno (solo WORKER)

- `App.kt` `taskContent`: para WORKER reemplazar el botón suelto por `WorkerTasksHeader`: `BpmPageHeader("Mis tareas")` + `BpmTaskRow` «Incidencias y correcciones» (Alert, detalle «Reporta un problema o sube la foto de una corrección») → `INCIDENT_TASKS`. Otros roles: igual que hoy.
- `DailyOperationsContent`: si `role == "WORKER"` y el workspace tiene **una sola sede**, seleccionarla automáticamente.
- `OperationsScreen` para WORKER: sin el título «Mis tareas de limpieza»; en lugar de los chips usar `BpmSegmentedTabs` «Por hacer» (PLANS) / «Hechas» (EXECUTIONS).
  Filas con `BpmTaskRow`: plan → icono Clock, detalle frecuencia, `BpmStatusChip` con `dueTime` («hasta 10:00», Warning si ya pasó la hora local, Neutral si no o si no hay hora); orden por `dueTime` (vacíos al final).
  Ejecución → Check/Success, detalle resultado y hora; chip por resultado (COMPLETED Success, OBSERVED Warning, NOT_COMPLETED Danger).
  Tocar un plan abre el formulario de limpieza con ese plan preseleccionado si el VM lo permite (`newForm()` + selección del plan; si exige cambios en el VM, agregar `newCleaningFor(plan)` con prueba).
  Acción principal «Registrar limpieza» en `BpmActionBar` (Compact) o arriba (Medium+). Expanded+: detalle al lado con `BpmListDetail` como hoy.
  ADMIN/CHEF: sin cambios visuales.

## Pendientes de backend (no implementar en cliente)

- Racha diaria (días seguidos) para la pantalla Hoy.
- Avance por lección (lecciones completadas / %) para anillos exactos.
- Tareas de hoy del trabajador con estado «hecho hoy» (plan × ejecución por fecha).
- Resumen «Lo esencial» por lección.

## Estado WP1

Completado. `compileKotlinJvm`, `compileKotlinWasmJs` y `jvmTest --tests '*designsystem*'` pasan.

Archivos creados en `composeApp/src/commonMain/kotlin/pe/bpm/core/designsystem/`:
`BpmTone.kt`, `BpmStatusChip.kt`, `BpmProgress.kt`, `BpmSegmentedTabs.kt`, `BpmSupportPaneLayout.kt`,
`BpmNextStepCard.kt`, `BpmStepList.kt`, `BpmTaskRow.kt`, `BpmQuestionNavigator.kt`, `BpmRewardSummary.kt`, `BpmCourseCard.kt`.
Modificado: `BpmIcon.kt` (+ `Play, Award, Lock, Camera, Download, ChevronRight, Close, Route, Book`).
Drawables nuevos (Lucide, trazo 2): `ic_play, ic_award, ic_lock, ic_camera, ic_download, ic_chevron_right, ic_x, ic_route, ic_book`.
Pruebas: `commonTest/.../core/designsystem/BpmComponentsLogicTest.kt` (`questionNavigatorSummary`, `questionMarks`, `supportPaneWidth`).

Desviaciones de firma:
- `questionNavigatorSummary(answered: List<Boolean>)` en lugar de `List<BpmQuestionMark>` (alternativa permitida por el plan: la actual cuenta
  como respondida solo si tiene respuesta). Se añade `questionMarks(answered, current): List<BpmQuestionMark>`. Vacío → «Sin preguntas».
- `BpmNextStepCard` añade `enabled: Boolean = true` al final (no aplica `ServerOnly` por sí misma; el llamador decide).
- Contador de `BpmSegmentedTabs` usa `secondaryContainer`/`onSecondaryContainer` (acento suave) en vez de naranja sólido, por contraste del texto de 12 sp.
- Interno nuevo: `BpmProgressBar` (barra lineal de 8 dp, `internal`) y `BpmNeutralSurface` (gris 0xFFEEF1F1, `internal`).
- La ficha del navegador mide 44 dp visibles dentro de un área táctil de 48 dp; las fichas son circulares.

Pendientes: ningún componente tiene prueba de UI (el repo no tiene tests de Compose); revisar visualmente en dispositivo/web
(`BpmStepList` con IntrinsicSize, `BpmNextStepCard` con mascota sobre fondo primario). `BpmCourseCard` usa `Surface` con
`clickable` interior (la tarjeta completa es el botón).

## Estado WP2

Completado. `compileKotlinJvm`, `compileKotlinWasmJs` y `jvmTest --tests '*Workspace*' --tests '*WorkerToday*'` pasan.

Archivos:
- `features/workspace/presentation/WorkspaceDestination.kt`: `destinationLabel(destination, role)` («Hoy»/«Tareas» solo para WORKER).
- `features/workspace/presentation/WorkspaceScreen.kt`: etiquetas de rail/menú/barra y título de sección con `destinationLabel` (el título de Home sigue «Hola, Nombre»);
  rama Home: si `role == "WORKER" && homeContent != null` dibuja solo `homeContent()`; el resto de roles igual que antes.
- `features/training/presentation/WorkerToday.kt` (nuevo, funciones puras): `readableDate` («25 nov»), `nextAssignment`, `assignmentsForToday`, `assignmentChip`.
- `app/WorkerTodayContent.kt` (nuevo): pantalla Hoy con `BpmSupportPaneLayout` (compacta: siguiente paso, tu turno, asignadas, recompensas, reportar).
- `app/App.kt`: `homeContent` para WORKER (rama nueva antes de la de ADMIN/CHEF/SUPER_ADMIN, que queda intacta).
- Pruebas: `WorkspaceNavigationTest` (solo WORKER → `["Hoy","Tareas","Aprender"]`; el helper `nav` usa `destinationLabel`), `commonTest/.../training/WorkerTodayTest.kt`.

Desviaciones:
- `dateLabel` no se reemplaza ni se borra (la usa UI existente); `readableDate` es el helper nuevo para UI nueva.
- Asignación vencida: el apoyo dice «Venció el {fecha}» en vez de «Vence el».
- Estados: carga → tarjeta ligera «Buscando tus capacitaciones…»; fallo → `BpmInlineNotice` (warning) con acción «Reintentar» = `refreshAssignments`; sin fallo y cargado sin pendientes → «Todo al día» + `Mascot` + «Explorar cursos».
  Con fallo y sin datos no se muestra «Todo al día». Los logros solo se muestran si cargaron (si fallan se omite la tarjeta).
- «Reportar un problema» y «Incidencias y correcciones» navegan a `INCIDENT_TASKS` (con `leaveRun`); «Registrar limpieza» → ruta `tareas`.
- `screenNotice` del Home (tarjeta «Encuesta del estudio») sigue apareciendo sobre «Hoy» para WORKER, como antes.

Pendientes: revisión visual en dispositivo/web; la lista «Capacitaciones asignadas» ofrece «Ver todas» (→ Aprender) solo si hay más de 3.

## Estado WP3

Completado. `compileKotlinJvm`, `compileKotlinWasmJs` y `jvmTest --tests '*Training*' --tests '*Workspace*' --tests '*CourseSteps*' --tests '*CourseCatalog*'` pasan.

Archivos:
- `features/training/presentation/CourseSteps.kt` (nuevo): `lessonStepStates(course)` (aproximación documentada en KDoc: el modelo solo conoce `status` y `lastLessonId`) y `courseMainActionLabel`.
- `features/training/presentation/LearnHub.kt` (nuevo): `continueAssignment` (solo asignaciones IN_PROGRESS) y `assignmentMeta` («30 min · mejor puntaje 85»).
- `features/training/presentation/CourseOverview.kt` (nuevo; movido desde `TrainingScreen.kt`): cabecera, `BpmStepList` con la evaluación final destacada (solo `canPersist` y con preguntas),
  enlace «Evaluación diagnóstica», panel de apoyo (anillo hechas/total, estado, mejor puntaje, XP, `RaisedButton` principal, «Practicar sin calificación», `DownloadsCard`, constancia o texto de espera, `AttemptsHistory`).
  En Compact: tarjeta de avance arriba del temario, «Practicar sin calificación» como fila y la acción principal en `BpmActionBar`.
- `features/training/presentation/TrainingScreen.kt`: sin el bloque de la vista general; `DownloadsCard`, `CertificateCard` y `AttemptsHistory` pasan a `internal`; ancho `BpmSpacing.contentMax` en OVERVIEW y 720 dp (`readingWidth`) en LESSON/STUDY/EVALUATION.
- `features/training/presentation/TrainingLearnerScreens.kt`: `MyAssignmentsPanel` reescrito como hub (`BpmSupportPaneLayout`: Continuar, «Asignadas a ti» con `BpmCourseCard` + chip, `LazyRow` de 260 dp en Compact y `BpmCardGrid` desde Medium, entrada «Rutas de aprendizaje»; apoyo: `BpmRewardSummary`).
- `features/workspace/presentation/CourseCatalog.kt` (nuevo): `CatalogFilter`, `visibleCatalog`, `catalogStatusChip`, `catalogSummary`. `WorkspaceContent.kt`: `Entries` con `onOpen` dibuja `BpmCourseCard` + chip; sin `onOpen` queda igual.
- `features/workspace/presentation/WorkspaceScreen.kt`: nuevo parámetro opcional `hiddenCourseIds` (por defecto vacío); rama Learn sin `BpmIntroCard` cuando hay `learnHeader`, encabezado «Catálogo», `BpmFilterChip` Todos / Sin iniciar / En curso / Completados y paginación intacta.
- `app/App.kt`: pasa `hiddenCourseIds` (ids de `trainingLearner.state.assignments`) y usa `BpmPattern.Panel` para la ruta del curso mientras la vista es OVERVIEW (StepFlow en el resto). `app/TrainingContent.kt`: `TrainingLearnerHeader` llama `loadAchievements()` si `member`.
- Pruebas: `commonTest/.../training/CourseStepsTest.kt`, `commonTest/.../workspace/CourseCatalogTest.kt`.

Desviaciones:
- El catálogo (`Entry`: id, título, descripción, estado) no trae código ni duración: la banda de la tarjeta dice «CURSO» y el meta es la descripción recortada a 110 caracteres.
- La tarjeta «Continuar» usa icono Play (no hay % por curso) y solo aparece si hay una asignación IN_PROGRESS; las asignaciones llevan «{min} min» y, si existe, «mejor puntaje» (no hay conteo de lecciones en `MyAssignment`).
- El hub no repite «Mis logros» como botón: si los logros no cargan se ofrece un botón «Mis logros» en el panel de apoyo.
- La evaluación final solo se lista si hay preguntas (como el botón anterior, que se deshabilitaba sin preguntas); badge «Última vista» en la lección actual cuando hay `lastLessonId`.
- El contador «n cursos» de «Asignadas a ti» se oculta si quedan páginas por cargar.

Pendientes: revisión visual en dispositivo/web (`LazyRow` de tarjetas con alturas distintas en Compact; `BpmStepList` con muchas lecciones).

## Estado WP4

Completado. `compileKotlinJvm`, `jvmTest --tests '*Training*'` y `compileKotlinWasmJs` pasan.

Archivos:
- `features/training/presentation/LessonFlow.kt` (nuevo; movido desde `TrainingScreen.kt`): `LessonViewer` (progreso segmentado, título, video con los mismos modificadores, tarjeta de contenido,
  tarjeta «Comprueba lo aprendido · no se califica» solo si hay preguntas con ese `lessonId`, enlace «Evaluar esta lección (suma XP)» con las condiciones de antes, `BpmActionBar` con Anterior + Siguiente lección/Terminar lecciones,
  sin «Volver al curso»; desde Expanded el temario `BpmStepList(dense)` a la derecha con `selectLesson`) y `StudyPractice` (progreso segmentado; «Reintentar»/«Siguiente pregunta» en `BpmActionBar`).
- `features/training/presentation/EvaluationFlow.kt` (nuevo): `EvaluationPanel` (una pregunta por pantalla; `BpmQuestionNavigator` `wrap=false` sobre las acciones en Compact/Medium y `wrap=true` en la tarjeta «Progreso de la evaluación» desde Expanded; en la última «Revisar respuestas»),
  revisión (respuesta elegida o chip «Sin responder» Warning, «Cambiar», `RaisedButton` «Enviar evaluación»/«Reintentar envío» con `canSubmitEvaluation()`, «Salir sin enviar»; estados enviando, error y cola intactos) y `EvaluationResult` (mascota si aprobó, chip, «85 / 100», mínimo, XP, `AchievementCelebration`, `CertificateCard(prominent)`, «Revisar respuestas» plegable cerrado por defecto con el detalle de antes y las acciones de antes).
- `TrainingState.kt`: `EvaluationState` con `index: Int = 0` y `reviewing: Boolean = false` al final. `TrainingViewModel.kt`: `goToQuestion/nextQuestion/previousQuestion/openReview/closeReview` (sin efecto con `submitting`/`queued`/`outcome`; índice acotado). El envío no cambia.
- `TrainingScreen.kt`: sin lección/estudio/evaluación/resultado; contenido centrado; ancho completo solo en lección y evaluación por preguntas desde Expanded (`wantsWidePane`); `CertificateCard(prominent)`.
- Hosting: `WorkspaceScreen` recibe `secondaryWideOnExpanded` (StepFlow pasa a Panel desde Expanded); `App.kt` lo activa con `wantsWidePane`. En Compact/Medium sigue StepFlow (sin barra de navegación, `BpmActionBar` fija en Compact).
- Pruebas: 4 nuevas en `TrainingViewModelTest` (navegación acotada, revisión, ignoradas al enviar/con resultado, envío sin cambios).

Desviaciones:
- En Medium el navegador va en línea sobre las acciones (el plan solo define Compact y Expanded+); el panel lateral aparece desde Expanded.
- El resultado no tiene «XP ganados» del intento (el modelo solo trae el total): se mantiene «Puntos de experiencia: N XP» solo en la final. No se incluyen «Lo esencial» ni «Siguiente curso» (sin datos).
- «Cambiar» se usa también para preguntas sin responder. El estado de las lecciones del temario lateral combina la aproximación de `lessonStepStates` con la lección abierta como actual.

Pendientes: revisión visual en dispositivo/web (barra de acción con el navegador en Compact, temario lateral con muchas lecciones).

## Estado WP5

Completado. `compileKotlinJvm`, `jvmTest --tests '*Operations*' --tests '*Workspace*' --tests '*WorkerTasks*'` y `compileKotlinWasmJs` pasan.

Archivos:
- `app/WorkerTasksHeader.kt` (nuevo): `BpmPageHeader("Mis tareas")` + `BpmTaskRow` «Incidencias y correcciones» (Alert) → `INCIDENT_TASKS`. `App.kt` `taskContent` lo usa solo si `role == "WORKER"`; el resto conserva el botón.
- `app/DailyOperationsContent.kt`: WORKER con una sola sede (lista completa: sin más páginas ni búsqueda) la selecciona solo y no muestra «¿En qué sede trabajarás?».
- `features/operations/presentation/WorkerTasks.kt` (nuevo, puro): `sortPlansByDueTime`/`sortByDueTime` (vacíos al final, estable), `dueChipText`, `dueTone`, `minutesOfDay`, `resultTone`.
- `features/operations/presentation/WorkerTaskItem.kt` (nuevo): fila de plan (Clock, frecuencia, chip «hasta HH:mm»; toca = formulario con el plan) o de ejecución (Check, hora, chip de resultado; toca = detalle); «Ver detalle» en planes y «Fotos de la limpieza» en ejecuciones.
- `OperationsScreen.kt`: para WORKER sin título, `BpmSegmentedTabs` «Por hacer»/«Hechas», filas anteriores, «Registrar limpieza» en `BpmActionBar` (Compact) o arriba (Medium+). ADMIN/CHEF sin cambios (guardas `worker`).
- `OperationsViewModel.kt`: `newCleaningFor(plan: CleaningPlan?)` y `newCleaning()`: pasan a ejecuciones, abren el formulario y, con plan, lo preseleccionan sin buscarlo en el servidor (borrador con envío pendiente manda; plan inactivo se ignora).
- Pruebas: `commonTest/.../operations/WorkerTasksTest.kt` (orden, chips, tonos, `newCleaningFor`).

Desviaciones:
- No hay hora local en `commonMain` sin dependencia nueva (no hay kotlinx-datetime; solo `expect/actual` de texto): el chip de vencimiento es siempre Neutral. `dueTone(dueTime, localMinutes)` ya implementa Warning y está probada; falta pasarle la hora local.
- El orden por hora es por página cargada (la lista es paginada por el servidor). Sin contadores en las pestañas (no hay total).
- Desde «Por hacer», «Registrar limpieza» y tocar un plan cambian la pestaña a «Hechas» (el formulario es del recurso EXECUTIONS); al cerrar el formulario se vuelve a «Hechas».
- No se incluye «Reportar» flotante ni las correcciones asignadas dentro de la lista (no hay modelo unificado).

## Verificación final

Rama `feat/personal-redesign`, cambios sin confirmar (sin commit ni push).

| Comando | Resultado |
|---|---|
| `./gradlew :composeApp:jvmTest` | OK, `BUILD SUCCESSFUL` |
| `./gradlew :composeApp:wasmJsBrowserDistribution` | OK, `BUILD SUCCESSFUL` (solo avisos de tamaño de webpack) |
| `./gradlew :composeApp:assembleDebug` | OK, `BUILD SUCCESSFUL` |
| `node --test web/server.test.mjs` | OK, 6 pruebas, 0 fallos |
| `./gradlew :composeApp:compileKotlinIosSimulatorArm64` | OK, `BUILD SUCCESSFUL` (avisos previos en `iosMain`, no del rediseño) |

No hubo fallos que corregir.

Revisión de código: los flujos ADMIN, CHEF y SUPER_ADMIN siguen protegidos por las guardas `role == "WORKER"` / `worker` en `App.kt`, `WorkspaceScreen`, `OperationsScreen` y `DailyOperationsContent`. No se añadieron datos simulados ni nombres de usuario fijos (el saludo usa `viewer.displayName`). Los textos visibles están en español.

Pendientes:
- El chip «hasta HH:mm» sigue siempre Neutral: el proyecto no tiene `kotlinx-datetime` ni otro ayudante de hora local en `commonMain` (solo `kotlin.time.Clock`, sin zona horaria). `dueTone(dueTime, localMinutes)` ya admite Warning; falta un `expect/actual` o la dependencia para pasarle la hora local de `Location.timezone`.
- El catálogo de Aprender (tarjetas de curso, filtros de estado y ocultar cursos ya asignados) se aplica a todos los roles, no solo a WORKER. Confirmar que es lo deseado para ADMIN/CHEF.
- Revisión visual en dispositivo/web de WP1-WP5 (ver pendientes de cada «Estado WP»).
- Las pruebas de Android/iOS con SDK/macOS completo no se ejecutaron más allá de la compilación indicada.
