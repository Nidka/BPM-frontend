# Hechos del código para el rediseño del rol WORKER («Personal»)

Solo hechos verificados leyendo el código (sin modificar nada). Rutas relativas a
`composeApp/src/commonMain/kotlin/pe/bpm` salvo que se indique otra cosa; abreviado `B/`.
Los tests viven en `composeApp/src/{commonTest,jvmTest,iosTest}/kotlin/pe/bpm`; abreviado `T/`.

---------------------------------------------------------------------------------------------------
## 1. Navegación

### 1.1 `B/features/workspace/presentation/WorkspaceDestination.kt` (94 líneas)

```kotlin
enum class WorkspaceDestination(val route: String, val label: String) {   // L4-12
    Home("inicio","Inicio"), Learn("aprender","Aprender"), Operations("control","Control"),
    Supervision("seguimiento","Seguimiento"), Team("equipo","Equipo"), Company("empresa","Empresa"),
    Tasks("tareas","Mis tareas"), Account("cuenta","Mi cuenta");
    companion object { fun fromRoute(route: String?) = entries.firstOrNull { it.route == route } }
}
fun workspaceDestinations(role: String?, platformAdmin: Boolean): List<WorkspaceDestination>   // L20-28
```
| Caso | Resultado |
|---|---|
| platformAdmin | Home, Supervision, Team, Company, Account |
| ADMIN | Home, Operations, Learn, Supervision, Team, Company, Account |
| CHEF | Home, Operations, Learn, Account |
| **WORKER** | **Home, Tasks, Learn, Account** (L25) |
| otro / null / "worker" / SUPER_ADMIN sin membresía | Home, Account |

```kotlin
fun workspacePrimaryDestinations(available: List<WorkspaceDestination>): List<WorkspaceDestination>  // L35-40
// quita Account; si >5 quita Team; si aún >5 quita Learn.  WORKER => [Home, Tasks, Learn]
object OperatingScope { fun canOperate(role, platformAdmin) = Operations in workspaceDestinations(...) }  // L46-49
enum class WorkspaceShortcut(val label: String, val after: WorkspaceDestination)  // L55-60
    // Incidents("Incidencias", after Operations), Research("Investigación", Home), Companies("Empresas", Home), Access("Acceso", Supervision)
fun workspaceShortcuts(role, platformAdmin, canResearch, canManageGrants): List<WorkspaceShortcut>  // L66-72
    // Incidents solo CHEF (no platformAdmin). WORKER no tiene atajos salvo Research si researchState.access lo permite.
sealed interface WorkspaceNavItem { Section(destination) ; Shortcut(shortcut) }  // L75-78
fun workspaceNavigation(primary, shortcuts): List<WorkspaceNavItem>  // L83-94: mezcla, omite Team y luego Learn si >5, take(5)
```
- Navegación efectiva WORKER hoy: **Inicio · Mis tareas · Aprender** (3 ítems). «Mi cuenta» NO está en la
  navegación principal: va como icono de avatar en la cabecera (`WorkspaceTopBar`, `onAccount`).
- La campana de avisos (`onAlerts`, `unreadAlertCount`) también va en la cabecera, no en la nav.

### 1.2 Iconos y render (`B/features/workspace/presentation/`)
- `WorkspaceIcon.kt:8-17` `@Composable internal fun WorkspaceIcon(destination)` → `BpmIcon(BpmSymbol.X)`:
  Home→`Home`; Learn→`Learn`; **Tasks, Operations, Supervision→`Control`** (clipboard-check, mismo icono);
  Team→`Team`; Company→`Company`; Account→`Account`.
- `WorkspaceScreen.kt:343-351` `WorkspaceNavItem.Icon()` (Section→`WorkspaceIcon`, Shortcut: Incidents→`Alert`,
  Research→`Sprout`, Companies→`Company`, Access→`Team`). Etiqueta: `WorkspaceScreen.kt:327-330` (`destination.label`/`shortcut.label`).
- Selección: `isSelected` L333-336; apertura: `open` L338-341 (llama `navigate(destination)` o `onShortcut`).
- `WorkspaceScreen.kt:65-71`: `BoxWithConstraints` calcula `windowClass = BpmWindowClass.fromWidth(maxWidth)` y
  `navMode = BpmNavigationMode.of(windowClass, maxHeight)`; `wide = navMode != BottomBar`; provee
  `ProvideBpmWindowClass` y `LocalBpmActionBarSlot`.
- **Rail** (`navMode == Rail`, 80 dp, L73-86): `NavigationRail` con `Brand(compact=true)` en header y
  `NavigationRailItem(selected, onClick, icon={item.Icon()}, label=Text(labelSmall,maxLines=1))`.
- **SideMenu** (240 dp, L87-104): `Surface` + `Column(padding 24.dp)` con `Brand()` y `NavigationDrawerItem(label=Text(BpmType.bodyStrong), icon)`.
- **BottomBar** (compact o alto < 480 dp, L249-259): `NavigationBar { navItems.forEach { NavigationBarItem(icon, label=Text(labelSmall,maxLines=1)) } }`
  al final de la `Column`, **después** de `BpmActionBarHost(actionSlot)` (L246). Se oculta si `form != null`, `stepFlow`
  (`secondaryContent != null && secondaryPattern == StepFlow`) o (`secondaryEditor || choosingOrganization`) salvo `activeShortcut != null`.
  Colores: seleccionado `primary` sobre `primaryContainer` (indicador).
- Cabecera `WorkspaceTopBar.kt` (93 líneas): `WorkspaceTopBar(organizationName, wide, windowClass, canChangeOrganization, enabled, onToggleOrganization, onBack, onAlerts, unreadCount, onAccount)`;
  en no-wide muestra `Brand(compact)`+nombre; `onBack` aparece cuando hay `form`/`secondaryContent`/`secondaryTitle`/picker.

### 1.3 Tests que fijan etiquetas/orden de destinos
| Archivo | Qué afirma |
|---|---|
| `T/features/workspace/WorkspaceNavigationTest.kt` | WORKER: `workspaceDestinations("WORKER")` contiene Learn y Tasks, NO Operations/Company/Team; `workspacePrimaryDestinations(WORKER) == [Home, Tasks, Learn]` (L~44 exacto); `nav("WORKER") == ["Inicio","Mis tareas","Aprender"]` (L~85 exacto, usa `label`); `nav("CHEF") == ["Inicio","Control","Incidencias","Aprender"]`; ADMIN primary exacto `[Home, Operations, Learn, Supervision, Company]` y `nav("ADMIN").size == 5`; rol desconocido/null/"worker"/SUPER_ADMIN => `[Home, Account]`; Account nunca en primary de ADMIN/CHEF/WORKER; combinatoria rol×atajos cabe en 5 y solo se pierden Team/Learn. |
| `T/features/workspace/OperatingAccessTest.kt` | `Operations in workspaceDestinations("ADMIN")`, no para platformAdmin; primary de ADMIN incluye Operations y Company; Team en available. |
| `T/features/workspace/WorkspaceFormAccessTest.kt`, `WorkspacePlaceholderTest.kt` | No dependen de labels de nav. |
- Cambiar label/orden de `Home`, `Tasks`, `Learn` o el enum rompe `WorkspaceNavigationTest` (assert por `label`). No hay tests de UI Compose (ver §7).

---------------------------------------------------------------------------------------------------
## 2. Home («Hoy») y cómo se arma WorkspaceScreen

### 2.1 `Home()` — `features/workspace/presentation/WorkspaceContent.kt:19-41`
```kotlin
@Composable internal fun Home(workspace: Workspace, learningAllowed: Boolean, onLearn: () -> Unit, onCompany: () -> Unit)
```
Compartido por TODOS los roles. Renderiza `BpmPanelLayout(indicators = 3×BpmStatTile, support = BpmCard "Estado de tu espacio", main = BpmIntroCard)`:
- Indicadores: «Sedes» = `workspace.totals["locations"] ?: locations.size`; «Cursos» = `totals["courses"] ?: courses.size`; «Controles» = `totals["checklists"] ?: checklists.size`.
- Soporte: `SetupStatus` ×3 (Cuenta verificada / Organización disponible / Sedes disponibles|Configura la primera sede) con `BpmIcon(Check|Clock)`; botón «Ver mi organización» solo si `workspace.canManage` (WORKER no).
- Main: `BpmIntroCard(title = if (learningAllowed) "Capacitación y buenas prácticas" else "Gestión de organizaciones", …)` + `RaisedButton("Explorar capacitaciones", onLearn)` (WORKER: `learningAllowed = Learn in available` = true).
- `SetupStatus` L43-48, `SectionHeading` L50-56, `Entries` L58-66 (grilla de `Entry`, estado→«Publicado/Disponible/Sin iniciar/En progreso/Completado/Abierta/Resuelta», botón «Abrir curso» si `onOpen != null`), `Errors` L67-70, `roleLabel` L71 (WORKER→«Colaborador»).
- Título de la página Home (en `WorkspaceScreen.kt:129`): `"Hola, ${viewer.displayName.substringBefore(' ')}"`; otras secciones: `section.label`.

### 2.2 Firma completa de `WorkspaceScreen` — `WorkspaceScreen.kt:19-53` (`internal`)
```kotlin
viewer: WorkspaceViewer(id, displayName, email, canCreateOrganization: Boolean)   // WorkspaceViewer.kt
state: WorkspaceState, section: WorkspaceDestination,
onNavigate: (WorkspaceDestination) -> Unit,
onVerifySession: () -> Unit, sessionBusy: Boolean, sessionMessage: String?, sessionConfirmation: String?,
onSelect: (String) -> Unit, onRefresh: () -> Unit, onClear: () -> Unit, onLogout: () -> Unit,
formDraft: WorkspaceFormState, onSubmitForm: () -> Unit, onFormRetryAccount: () -> Unit, onFormSelectAccount: (String) -> Unit,
onFormEdit: ((WorkspaceFormDraft) -> WorkspaceFormDraft) -> Unit, onFormRetry: () -> Unit, onFormDiscard: () -> Unit,
form: WorkspaceFormKind?, onOpenForm: (WorkspaceFormKind) -> Unit, onCloseForm: () -> Unit,
choosingOrganization: Boolean, onChooseOrganization: () -> Unit,
checklistContent: @Composable () -> Unit, onManageTemplates: () -> Unit,
onDailyOperations: () -> Unit = {}, onIncidents: () -> Unit = {}, onTraining: () -> Unit = {},
onAccounts: () -> Unit = {}, onDevices: () -> Unit = {}, onAcceptInvitation: () -> Unit = {},
homeContent: (@Composable () -> Unit)? = null,
onOpenCourse: ((String) -> Unit)? = null,
learnHeader: (@Composable () -> Unit)? = null,
onAlerts: (() -> Unit)? = null, unreadAlertCount: Int? = null, onAlertRules: () -> Unit = {},
taskContent: @Composable () -> Unit = {},
secondaryContent: (@Composable () -> Unit)? = null, secondaryEditor: Boolean = false,
secondaryPattern: BpmPattern = BpmPattern.Panel, secondaryTitle: String? = null, secondaryBusy: Boolean = false, secondaryScrollKey: String? = null,
screenNotice: @Composable () -> Unit = {}, noticeVisible: Boolean = false, onUserActivity: () -> Unit = {},
onOrganizationSearch: (String)->Unit, onOrganizationNext: ()->Unit, onOrganizationPrevious: ()->Unit, onOrganizationRetry: ()->Unit,
onPageSearch: (WorkspaceResource, String)->Unit, onPageNext: (WorkspaceResource)->Unit, onPagePrevious: (WorkspaceResource)->Unit, onPageRetry: (WorkspaceResource)->Unit,
shortcuts: List<WorkspaceShortcut> = emptyList(), activeShortcut: WorkspaceShortcut? = null, onShortcut: (WorkspaceShortcut)->Unit = {},
```

### 2.3 Ramas de contenido en `WorkspaceScreen` (orden real, L114-243) — clave para el WORKER
1. `section == Tasks && workspace != null && secondaryContent == null` → `HostedContent(weight(1f), BpmPattern.Panel) { taskContent() }` (L114). **Sin `BpmPageHeader`, sin `screenNotice()`, sin `verticalScroll` del shell, sin `bpmAppear`**; `taskContent` debe scrollear por sí mismo (`OperationsScreen` lo hace).
2. `secondaryEditor && secondaryContent != null` → `HostedContent(secondaryPattern, preview = pattern != Form) { secondaryContent() }` (L115). Sin cabecera de página ni scroll del shell. **Aquí caen el curso (`aprender/{courseId}`, StepFlow), logros/rutas (Panel) e incidencias (`tareas/incidencias`, Panel)**.
3. `form != null` → `WorkspaceForm` dentro de `ServerOnly`. 4. `choosingOrganization` → picker.
5. Resto: `Box(weight(1f).bpmAppear().verticalScroll(contentScroll)) { BpmScreenFrame(Panel) { BpmPageHeader(title); screenNotice(); Errors; notice; … } }` (L127-243):
   - Home: `homeContent?.invoke(); Home(...)` (L183). Learn: `BpmIntroCard("Un hábito a la vez.", "Consulta las capacitaciones publicadas por tu organización.", learning=true)` → `learnHeader?.invoke()` → `WorkspacePagedList(WorkspaceResource.Courses…) { Entries(current.courses, "Sin capacitaciones para mostrar", …, onOpenCourse) }` (L231-237).
   - Account: tarjeta «Acciones» (botones «Mi aprendizaje» si Learn disponible, «Mis dispositivos», «Aceptar una invitación», «Cerrar sesión») + tarjeta con nombre/correo/`roleLabel` (L133-168).
   - Tasks: `Unit` en el `when` (L181) — su contenido es la rama 1.
- `HostedContent(modifier, pattern, preview=true, content)` L271-277: padding `BpmSpacing.gutter(windowClass)`, `widthIn(max = BpmPatternRules.contentMax(pattern, windowClass, preview))`.
- `IncidentBanner`/`IncidentEntryCard`/`ActionCard` privados (L291-323); `IncidentBanner` solo en Operations/Supervision.

### 2.4 Dónde se construye en `B/app/App.kt` (940 líneas)
- `WorkspaceScreen(` L590 … `onLogout = ::requestLogout, )` L855. Se instancia **una vez por (destination, route)** dentro de `WorkspaceDestination.entries.flatMap{…}.forEach{ composable(route) }` (L384-~858). Mapa destino→rutas L385-395: `Learn → [aprender, aprender/{courseId}, aprender-logros, aprender-rutas]`; `Tasks → [tareas, tareas/incidencias]`; `Home → [inicio, organizaciones, avisos, estudio, investigacion, reportes]`; `Account → [cuenta, cuenta/dispositivos, cuenta/invitacion?token=…]`.
- Parámetros relevantes ya cableados (líneas): `viewer` 591; `unreadAlertCount = alertsState.unreadCount` 595; `homeContent` 603-611 (solo si `ReportAccess.canView(reportRole)` (ADMIN/CHEF/SUPER_ADMIN) o rol ADMIN → **para WORKER es `null`**); `onOpenCourse` 612 (`nav.navigate("aprender/$id")`); `learnHeader` 613-619 (solo `destination == Learn && currentWorkspace != null`: `TrainingLearnerHeader(profile.id, org.id, member = platformRole != "SUPER_ADMIN", trainingLearner, onOpenCourse, onAchievements = nav TRAINING_ACHIEVEMENTS, onPaths = nav TRAINING_PATHS)`); `taskContent` 620-626; `secondaryContent` 627-~724; `secondaryEditor` 725; `secondaryPattern` 727-732 (`studySurvey||learningCourse||running||offline → StepFlow`); `secondaryTitle` 733; `screenNotice` 779-808; `checklistContent` 809-853 (solo Operations/Supervision).
- `screenNotice` en Home (L779+): tarjeta «Encuesta del estudio» + botón «Abrir encuesta del estudio» (`STUDY_SURVEY`) si `currentWorkspace != null && platformRole != SUPER_ADMIN` → **se ve para WORKER en Home**; tarjeta «Investigación» solo si `researchState.access.canResearch||canManageGrants`; tarjeta «Retomar control» solo `destination in [Home, Operations] && runState.resumeId != null` (runs solo se abren con Operations → WORKER nunca); `accessNotice` y `identityState.notice`.

### 2.5 Estado disponible en App.kt para un WORKER (L72-145)
| Objeto | Línea | Disponibilidad / campos para WORKER |
|---|---|---|
| `profile = identityState.profile` | 145 | `Profile(id, email, displayName, organizations: List<Organization(id,name,role)>, platformRole = "USER")` (`features/identity/domain/Identity.kt:5-13`). Nombre de pila: `profile.displayName.substringBefore(' ')`. |
| `workspaceState: WorkspaceState` | 143 | `workspace: Workspace?` con `role` ("WORKER"), `organization`, `locations: List<Location(id,name,address,district,contactPhone,timezone)>`, `courses/checklists/incidents: List<Entry(id,title,detail,status)>`, `totals: Map<String,Int>`, `canManage=false`; más `loading, saving, selected, errors, notice, pages`. |
| `trainingLearner: TrainingLearnerViewModel` | 117 | Solo se hace `open(...)` (carga asignaciones) cuando se compone `TrainingLearnerHeader` (ruta `aprender`) o `TrainingLearnerContent` (logros/rutas). **En Home NO se abre**; si el Home nuevo quiere asignaciones/XP hay que llamar `trainingLearner.open(profile.id, org.id, member=true)` allí (ver §3.4). Se resetea en cambio de identidad/org (`resetTrainingPlus()` L~160). |
| `training: TrainingViewModel` | 106 | Estado del curso abierto (`TrainingState`). Se abre/cierra en `TrainingCourseContent` (`app/TrainingContent.kt:28-33`; `DisposableEffect → training.reset()`). |
| `assignments: ChecklistAssignmentsViewModel` (`assignmentState`) | 95-96 | **NO funciona para WORKER**: se abre solo si `currentWorkspace.canManage || Operations in workspaceDestinations(...)` (L~280-283); WORKER no cumple → `assignments.reset()`. Tampoco `checklists`, `runs`, `offlineRuns`, `history` (se abren solo con Operations/Supervision). No hay «controles programados» para WORKER. |
| `dailyOperations: OperationsViewModel` / `dailyState` | 99-100 | Sí para WORKER vía `taskContent` (`DailyOperationsContent`, rol `"WORKER"`). Estado `OperationsState` (§5.2). `dailyState.busy/form/saving` se usan en App. |
| `incidents: IncidentsViewModel` | 105 | Sí para WORKER en `tareas/incidencias` (`IncidentsContent`). `incidents.refreshIfStale()` en rutas `INCIDENT_*` (L~190). El estado `IncidentsState` se colecta dentro de `IncidentsContent`, no en App. |
| `alerts: AlertsViewModel` / `alertsState` | 102-103 | `alertsState.unreadCount: Int?` (L46 de `AlertsViewModel.kt`) ya se pasa a la campana. Se abre en `LaunchedEffect` (L~212) con rol `currentWorkspace.role`; la bandeja es ruta `avisos` (Home destino). `alertsState.inbox: AlertsPage<Alert>` disponible para listar avisos. |
| `reportsDashboardState` | 111 | Se abre solo si `ReportAccess.canView(reportRole)` (ADMIN/CHEF/SUPER_ADMIN) → **WORKER no**. |
| `usage` | ~123 | `usageModuleFor(route)` (`app/UsageModules.kt`): `tareas*`→CHECKLISTS, `aprender*`→TRAINING, `inicio`→HOME, `cuenta`→SETTINGS (si se añaden rutas nuevas hay que mapearlas aquí o caen en `APP`). |
- Redirección de acceso: si `destination !in available` → `resetTo(Home)` con `accessNotice` (L~500-510). Una ruta nueva de WORKER debe agregarse al `when` del mapa destino→rutas (L385-395) y, si es destino nuevo del enum, a `workspaceDestinations`.
- `refreshVisibleData()` (L187-200): refresca `dailyOperations` en rutas `[DAILY_MANAGEMENT, DAILY_EXECUTION, "tareas"]`, `incidents` en `INCIDENT_*`, `workspace.refreshIfStale()` en rutas primarias (cualquier `WorkspaceDestination.route`).
- Navegación de retorno: `closeForm()` (L~432-460): `learningCourse → training.back() || navigateBack(destination.route)`; `learningExtras → trainingLearner.back()` si rutas; `incidentRoute → incidents.prepareBack{…}`; `daily → …`.

---------------------------------------------------------------------------------------------------
## 3. Datos del flujo de formación (learner)

### 3.1 Dominio `B/features/training/domain/Training.kt` (144 líneas)
```kotlin
data class TrainingCourseInfo(id, code, title, description, estimatedDurationMinutes: Int)          // L4-10
data class TrainingVersionInfo(id, versionNumber: Int, passingScore: Int)                           // L12
data class LessonVideo(mediaId, mimeType, sizeBytes: Long, sha256: String? = null)                  // L15
data class ExternalVideo(videoId) { val embedUrl; val watchUrl }                                     // L18-22 (YouTube nocookie)
data class TrainingLesson(id, title, content: String /*texto plano, hace de transcripción*/, displayOrder: Int,
                          video: LessonVideo?, externalVideo: ExternalVideo? = null, offlineAvailable: Boolean = true)  // L24-35
data class TrainingQuestion(id, lessonId: String?, questionText, options: List<String>, displayOrder: Int)  // L38-44  (SÍ enlaza lección: lessonId nullable)
enum class ProgressStatus { NOT_STARTED, IN_PROGRESS, COMPLETED }                                     // L46
data class TrainingProgress(status: ProgressStatus, lastLessonId: String?, bestScore: Int?, experiencePoints: Int, completedAt: String?)  // L48-54
data class TrainingCertificate(id, verificationCode, issuedAt)                                        // L56
data class TrainingCourse(course: TrainingCourseInfo, version: TrainingVersionInfo, lessons: List<TrainingLesson>,
     questions: List<TrainingQuestion>, progress: TrainingProgress, certificate: TrainingCertificate?, cachedAt: Long? = null) {
     fun resumeLesson(): TrainingLesson? }                                                            // L58-71
data class VideoTicket(url, mimeType, expiresAt, local: Boolean = false)                              // L74
data class StudyFeedback(correct: Boolean, correctOption: Int, explanation: String)                   // L77
enum class AttemptType { DIAGNOSTIC, FINAL, MODULE }                                                  // L80
data class AttemptAnswer(questionId, selectedOption); data class AttemptSubmission(id, clientOperationId, versionId, attemptType, startedAt, clientCreatedAt, answers, lessonId: String? = null)
data class QuestionResult(questionId, selectedOption, correct, correctOption, explanation)            // L100-106
data class AttemptSummary(id, attemptType, score: Int, passed: Boolean, completedAt)                  // L108
data class AttemptOutcome(attempt: AttemptSummary, results: List<QuestionResult>, progress: TrainingProgress, certificate: TrainingCertificate?, achievements: List<Achievement> = emptyList())  // L110-117
enum class TrainingFailure { CONNECTION, SESSION, DENIED, NOT_FOUND, VERSION_OUTDATED, CONFLICT, DRAFT_CONFLICT, CODE_TAKEN, ANSWERS_INVALID, DRAFT_INCOMPLETE, INVALID, CONTENT_INVALID, TOO_LARGE, RATE_LIMITED, UNAVAILABLE, INVALID_RESPONSE, ASSIGNMENT_EXISTS, PATH_CONFLICT, PATH_ARCHIVED, COURSE_NOT_PUBLISHED, QUEUED }  // retryable = CONNECTION|UNAVAILABLE|RATE_LIMITED|INVALID_RESPONSE
interface TrainingRepository { course; videoTicket; saveProgress(org, courseId, lessonId); checkStudyAnswer(org, courseId, questionId, selected); submitAttempt(org, courseId, attempt) }  // L138-144
```
**NO existen**: porcentaje de curso, lista de lecciones completadas, estado por lección, `summary`/resumen de lección, racha actual. El avance es solo `status` + `lastLessonId` + `bestScore` + `experiencePoints`. Cuerpo de lección = `TrainingLesson.content` (texto plano con `\n`, hasta 20 000 caracteres: `TrainingLimits.LESSON_CONTENT_MAX`).

### 3.2 `TrainingPlus.kt` (314 líneas)
```kotlin
enum class AssignmentProgress { PENDING, IN_PROGRESS, COMPLETED, OVERDUE }                           // L66
data class MyAssignment(assignmentId, course: TrainingCourseInfo, dueAt: String?, assignedAt: String, status: AssignmentProgress,
                        progressStatus: ProgressStatus, bestScore: Int?, completedAt: String?)       // L68-77
enum class AchievementCriterion { COURSES_COMPLETED, PERFECT_SCORE, STREAK_DAYS }                    // L105
data class Achievement(id, code, name, description, points: Int, criterion, criterionThreshold: Int, active: Boolean, awardedAt: String?)  // L107-118
data class ExperienceTotals(experiencePoints, courseExperiencePoints, moduleExperiencePoints, achievementPoints)  // L120
data class MyAchievements(totals: ExperienceTotals, achievements: List<Achievement>) { val earned; val pending }  // L122-125
enum class PathStatus { DRAFT, PUBLISHED, ARCHIVED }; enum class CourseStatus { DRAFT, PUBLISHED, ARCHIVED }  // CourseStatus en TrainingAuthoring.kt:3
data class PathCourse(courseId, code, title, position, courseStatus, progressStatus: ProgressStatus?)  // L151-159
data class PathProgress(status: ProgressStatus, completedCourses, totalCourses, percent: Int, nextCourseId: String?)  // L161
data class LearningPath(id, title, description, status, rowVersion, publishedAt, courses: List<PathCourse>, progress: PathProgress?)  // L163-173
interface TrainingLearnerRepository { myAssignments(org, pageToken); myAchievements(org); paths(org, pageToken); path(org, pathId) }  // L217-222
data class TrainingPage<T>(items, nextToken: String?)
enum class CertificateStatus { VALID, REVOKED }; data class CertificateVerification(status, courseTitle, versionNumber, issuedAt, organization, holder)  // L194-204
```
- **Racha**: `STREAK_DAYS` existe solo como criterio de insignia (`criterionLabel`: «Evalúa N día(s) seguidos»). No hay ningún campo de «racha actual» en dominio, DTO ni estado (grep `streak|racha` solo aparece en el criterio). Calcularlo requiere backend o aproximarlo (no hay historial de fechas de intentos salvo `AttemptRecord.queuedAt`/`AttemptSummary.completedAt` del curso abierto).
- **Constancia** (`TrainingCertificate`): `id`, `verificationCode` (24 chars `[A-Z2-7]`), `issuedAt`. Verificación pública: `CertificateVerification` (ruta `verificar/{codigo}`, `CertificateVerificationViewModel` en `TrainingPlusAdminViewModels.kt:558`, estado `VerificationState(input, checking, outcome: VerificationOutcome? = Found|NotFound|Failed)`).
- `TrainingOffline.kt`: `AttemptRecord(attemptId, courseId, type, queuedAt: Long, result: AttemptSummary?) { graded }`, `TrainingAttemptLedger.observe(courseId): Flow<List<AttemptRecord>>`, `DownloadsState(supported, usedBytes, limitBytes, videos: Map<mediaId,VideoDownload>, activeCourseId) { of(); downloaded() }`, `VideoDownload(mediaId, status: VideoDownloadStatus{NOT_DOWNLOADED,DOWNLOADING,PAUSED,DOWNLOADED}, downloadedBytes, totalBytes, issue: DownloadIssue?{NETWORK,CORRUPT,LIMIT,UNSUPPORTED,TICKET})`, `TrainingDownloads` (download/cancel/delete/deleteCourse/deleteAll/setLimit/localVideo; `LIMIT_CHOICES`), `formatBytes(Long)`.

### 3.3 Estados de presentación `B/features/training/presentation/TrainingState.kt` (100 líneas)
```kotlin
enum class TrainingPane { OVERVIEW, LESSON, STUDY, EVALUATION }                                     // L5
sealed interface VideoState { Idle; Loading; Ready(ticket: VideoTicket); Failed(reason) }            // L8-13
data class StudyState(questions: List<TrainingQuestion>, lessonId: String? = null, index: Int = 0, selected: Int? = null,
                      feedback: StudyFeedback? = null, checking: Boolean = false, failure: TrainingFailure? = null) { finished; current }  // L16-27
data class EvaluationState(type: AttemptType, startedAt: String, lessonId: String? = null, answers: Map<String, Int> = emptyMap(),
                      pending: AttemptSubmission? = null, submitting: Boolean = false, failure: TrainingFailure? = null,
                      outcome: AttemptOutcome? = null, queued: Boolean = false)                      // L33-45  (NO hay "index": se muestran TODAS las preguntas en una sola página)
data class TrainingState(userId, organizationId, courseId, canPersist: Boolean = true, loading, failure: TrainingFailure?, course: TrainingCourse?,
                      screen: TrainingPane = OVERVIEW, lessonId: String?, video: VideoState = Idle, progressNotSaved: Boolean,
                      study: StudyState?, evaluation: EvaluationState?, notice: String?, downloads: DownloadsState, attempts: List<AttemptRecord>) {
    val lesson: TrainingLesson?; val evaluationQuestions: List<TrainingQuestion> }                   // L47-77
fun trainingFailureMessage(failure: TrainingFailure): String                                         // L79-100
```
`TrainingLearnerViewModel.kt:12-33`:
```kotlin
data class LearnerState(userId, organizationId, member: Boolean = true,
  assignments: List<MyAssignment>, assignmentsLoaded, assignmentsLoading, assignmentsFailure: TrainingFailure?, assignmentsNext: String?,
  achievements: MyAchievements?, achievementsLoading, achievementsFailure,
  paths: List<LearningPath>, pathsLoaded, pathsLoading, pathsFailure, pathsNext,
  pathDetail: LearningPath?, pathDetailLoading, pathDetailFailure)
```

### 3.4 ViewModels (métodos públicos)
`TrainingLearnerViewModel(loadAssignments: LoadMyAssignments, loadAchievements: LoadMyAchievements, loadPaths: LoadLearningPaths, syncCatalog: (suspend (String)->Unit)? = null, localFirst: LocalFirstReader = Direct)`, `val state: StateFlow<LearnerState>`:
`reset()` L55 · `open(userId, organizationId, member: Boolean)` L63 (mismo contexto ⇒ solo `refreshAssignments()`; contexto nuevo ⇒ reinicia, carga asignaciones local-first y dispara `syncCatalog`) · `refreshAssignments()` L76 · `moreAssignments()` L101 · `loadAchievements()` L123 · `loadPaths()` L140 · `morePaths()` L154 · `openPath(pathId)` L174 · `closePath()` L188 · `back(): Boolean` L194. (`member=false` para SUPER_ADMIN: no pide asignaciones ni logros.)

`TrainingViewModel(loadCourse, requestVideo, saveProgress, checkAnswer, submitAttempt, newId, now, clock = {Clock.System.now()}, downloads = NoTrainingDownloads, attempts = NoTrainingAttemptLedger, syncCatalog = null)` L20-34, `val state: StateFlow<TrainingState>`:
`reset()` 52 · `open(userId, organizationId, courseId, canPersist = true)` 60 · `refresh()` 81 · `dismissNotice()` 115 · `resume()` 120 · `selectLesson(lessonId)` 122 (guarda `lastLessonId` si `canPersist`) · `nextLesson()` 147 · `previousLesson()` 148 · `loadVideo()` 160 · `onVideoError()` 184 · `downloadCourse()` 200 · `cancelDownload()` 207 · `deleteCourseDownloads()` 209 · `deleteAllDownloads()` 214 · `setDownloadLimit(bytes)` 216 · `closeVideo()` 218 · `startStudy(lessonId: String? = null)` 223 · `answerStudy(option)` 236 · `skipStudyQuestion()` 259 · `retryStudyQuestion()` 266 · `nextStudyQuestion()` 272 · `restartStudy()` 278 · `startModuleEvaluation(lessonId)` 283 · `startEvaluation(type: AttemptType)` 293 (MODULE rechazado) · `answerEvaluation(questionId, option)` 304 · `canSubmitEvaluation(): Boolean` 312 · `submitEvaluation()` 320 · `closeEvaluation()` 361 · `back(): Boolean` 369 (`true` si resolvió dentro del curso).
- Comportamiento relevante: `selectLesson` pasa `screen=LESSON` y guarda progreso en background (`progressNotSaved` si falla). Al enviar un intento exitoso actualiza `course.progress` y `certificate`; `QUEUED` ⇒ `evaluation.queued=true`; `VERSION_OUTDATED` recarga y reinicia (`keepEvaluation`). Una lección NO se marca «completada» individualmente: solo `lastLessonId`.
- Los tests construyen estos data classes **posicionalmente** (`TrainingProgress(ProgressStatus.NOT_STARTED, null, null, 0, null)`, `TrainingLesson(id,title,content,order,video)` en `T/features/training/TrainingFixtures.kt`): cualquier campo nuevo debe ir al final con valor por defecto.

---------------------------------------------------------------------------------------------------
## 4. Pantallas de formación

### 4.1 Cómo se decide la vista (`TrainingScreen.kt:26-54`)
`TrainingScreen(viewModel: TrainingViewModel, modifier = Modifier)` (público): `Column(fillMaxSize().widthIn(max = 860.dp).verticalScroll(scroll).bpmAppear(), spacedBy(BpmSpacing.section))`; `LaunchedEffect(state.screen, state.lessonId, state.study?.index) { scroll.scrollTo(0) }`.
Orden: barra `LinearProgressIndicator` si `loading && course == null` → tarjeta de `failure` (+«Volver a intentar» si `retryable || course == null`) → tarjeta de `notice` («Entendido») → `val course = state.course ?: return@Column` → `when (state.screen)`:
`OVERVIEW → CourseOverview` · `LESSON → state.lesson?.let { LessonViewer }` · `STUDY → state.study?.let { StudyPractice }` · `EVALUATION → state.evaluation?.let { EvaluationPanel }` (dentro, si `evaluation.outcome != null` → `EvaluationResult`; si `queued` → tarjeta «Evaluación enviada…»).
Todo es estado interno: **el curso, la lección, el estudio y la evaluación NO tienen rutas propias** (una sola ruta `aprender/{courseId}`); el botón Atrás del shell llama `training.back()`.

### 4.2 Composables de `TrainingScreen.kt` (376 líneas)
| Composable | Líneas | Qué dibuja / llamadas al VM |
|---|---|---|
| `TrainingScreen` (public) | 26-54 | Contenedor descrito arriba; `viewModel::refresh`, `viewModel::dismissNotice`. |
| `progressLabel(status)` (internal fun) | 56-60 | «Sin iniciar/En progreso/Completado» (también lo usa `LearningPathsScreen`). |
| `CourseOverview` (private) | 62-116 | Aviso de copia local (`cachedAt`, `relativeAge`), `PendingBadge(info.id)`, título `headlineMedium`, descripción, `code · min · versión`; `BpmCard(primaryContainer)` «TU AVANCE» (estado, mejor puntaje, XP, `RaisedButton("Continuar donde quedaste"/"Empezar el curso", viewModel::resume)`); `CertificateCard`; «Lecciones» en `BpmCardGrid(minCardWidth=320.dp, maxColumns=2)` con `Eyebrow("LECCIÓN n [· ÚLTIMA QUE VISTE]")`, indicador de video, `BpmSecondaryButton("Abrir lección", selectLesson)`; `DownloadsCard`; tarjeta «MODO ESTUDIO · SIN CALIFICACIÓN» (`startStudy()`); tarjeta «MODO EVALUACIÓN · SE CALIFICA» (`startEvaluation(DIAGNOSTIC)` secundario, `startEvaluation(FINAL)` `RaisedButton`) o aviso si `!canPersist`; `AttemptsHistory`. |
| `AttemptsHistory` | 118-131 | Hasta 5 `AttemptRecord`: «enviada, se calificará al conectarse» o «N de 100 · Aprobado/No aprobado (mínimo X)». |
| `DownloadsCard` + `downloadIssueMessage` | 133-188 | «VER SIN CONEXIÓN»: videos guardados, progreso, límites; llama `downloadCourse/cancelDownload/deleteCourseDownloads/setDownloadLimit/deleteAllDownloads`. |
| `CertificateCard` | 190-199 | «CONSTANCIA · Completaste este curso · Código de verificación · Emitida: fecha». |
| `LessonViewer` | 201-256 | `Eyebrow("LECCIÓN i DE n")`, título, aviso `progressNotSaved`; video externo (`ExternalLessonVideo(embedUrl, watchUrl, aspectRatio 16/9)` + «Abrir video en el navegador»); video privado según `VideoState` (`loadVideo`, `LessonVideoPlayer(ticket.url, ticket.mimeType, viewModel::onVideoError, aspectRatio 16/9)`, `closeVideo`); tarjeta «TRANSCRIPCIÓN Y TEXTO»/«CONTENIDO» con `lesson.content`; botones Anterior/Siguiente (`previousLesson/nextLesson`; en última lección «Terminar lecciones» = `back`); en Compact se apilan, si no `Row` 50/50; «Practicar esta lección» (`startStudy(lesson.id)`), «Evaluar esta lección · se califica» (`startModuleEvaluation`, solo `canPersist` y si hay preguntas de esa lección); «Volver al curso». |
| `StudyPractice` | 258-306 | `study.finished` ⇒ tarjeta fin («Practicar de nuevo» `restartStudy`, «Volver al curso» `back`); si no: «Pregunta i de n», enunciado `titleLarge`, `BpmChoiceRow(text, selected, enabled, supportingText = «Respuesta correcta»/«Tu respuesta»)` → `answerStudy(option)`; `LinearProgressIndicator` si `checking`; fallo (CONNECTION ⇒ botón `skipStudyQuestion`); `feedback` ⇒ tarjeta «¡Correcto!/Todavía no» + explicación + `retryStudyQuestion` + `nextStudyQuestion`; «Salir de la práctica». |
| `EvaluationPanel` | 308-345 | Eyebrow «EVALUACIÓN FINAL/DE LECCIÓN/DIAGNÓSTICA · SE CALIFICA»; outcome ⇒ `EvaluationResult`; queued ⇒ tarjeta; si no: «Responde todas las preguntas», contador «Respondidas: a de n», **todas las preguntas en una lista** (cada una en `BpmCard` con `BpmChoiceRow` → `answerEvaluation(question.id, option)`), progreso de envío, error, `RaisedButton("Enviar evaluación"/"Reintentar envío", submitEvaluation, enabled = canSubmitEvaluation())`, «Salir sin enviar» (`back`). |
| `EvaluationResult` | 347-376 | Tarjeta «Aprobado/No aprobado» + «Puntaje: N de 100 (mínimo X)» + XP (solo FINAL); `AchievementCelebration(outcome.achievements)`; nota para MODULE; `CertificateCard`; «Resultado por pregunta» (CORRECTA/INCORRECTA, tu respuesta, correcta, explicación); si `!passed || DIAGNOSTIC`: «Repasar en modo estudio» (`startStudy`) y «Intentar otra vez»; «Volver al curso» (`closeEvaluation`). |

Reproductor: `LessonVideoPlayer.kt` (36 líneas, `features/training/presentation/`):
```kotlin
@Composable expect fun LessonVideoPlayer(url: String, mimeType: String, onError: () -> Unit, modifier: Modifier = Modifier)  // L17-18
@Composable expect fun rememberLessonVideoPicker(maxBytes: Int, onResult: (VideoSelection) -> Unit): () -> Unit             // L25-26 (autoría)
@Composable expect fun ExternalLessonVideo(embedUrl: String, watchUrl: String, modifier: Modifier = Modifier)               // L35-36
```
`actual`: `androidMain/.../LessonVideoPlayer.android.kt`, `ExternalLessonVideo.android.kt` (Media3 / WebView); `iosMain/.../LessonVideoPlayer.ios.kt`, `ExternalLessonVideo.ios.kt` (AVKit / WKWebView); `wasmJsMain/.../LessonVideoPlayer.wasmJs.kt`, `ExternalLessonVideo.wasmJs.kt` (`<video>`/iframe superpuesto); `jvmMain/.../LessonVideoPlayer.jvm.kt` (abre navegador; no compila Android/iOS sin SDK/macOS). El video/iframe web se superpone sobre el área: conservar `Modifier.fillMaxWidth().aspectRatio(16f/9f)` y evitar scroll/clip que lo desalineen.

### 4.3 `TrainingLearnerScreens.kt` (251 líneas)
| Composable | Líneas | Qué dibuja / VM |
|---|---|---|
| `assignmentStatusLabel` (internal) | 31-36 | PENDING→«Pendiente», IN_PROGRESS→«En curso», COMPLETED→«Completada», OVERDUE→«Vencida». |
| `dateLabel(instant)` (internal) | 38 | `instant?.substringBefore('T')` (fecha ISO `AAAA-MM-DD`, sin formato local). |
| `AchievementCelebration(achievements)` (public) | 49-65 | `AnimatedVisibility` (fade+scale, respeta `rememberReduceMotion`) con tarjeta «NUEVA(S) INSIGNIA(S)»; usa `BpmMotion`. |
| `MyAssignmentsPanel(viewModel, onOpenCourse, onAchievements, onPaths)` (public) | 70-107 | «Mis capacitaciones» `headlineSmall`; `FlowRow` con «Mis logros» (si `member`) y «Rutas de aprendizaje»; `EmptyState("Sin capacitaciones asignadas", …)`; `BpmGridLayout(state.assignments)` con `BpmCard` (tint `errorContainer` si OVERDUE): Eyebrow estado, título, `code · min`, «Fecha límite», «Completada el», «Mejor puntaje», botón «Repasar curso»/«Empezar»/«Continuar» → `onOpenCourse(course.id)`; «Ver más capacitaciones» → `moreAssignments`; reintento → `refreshAssignments`. |
| `AchievementsScreen(viewModel, modifier)` (public) | 111-137 | «Mis logros»; tarjeta XP total `headlineMedium` con desglose «Cursos · Lecciones · Insignias»; «Insignias obtenidas (n)»; «Por lograr»; `AchievementCard` (L145-154: Eyebrow OBTENIDA/POR LOGRAR, nombre, descripción, `criterionLabel + puntos XP`, fecha). Llama `loadAchievements` en reintento. |
| `criterionLabel(a)` (internal) | 139-143 | «Completa N curso(s)» / «Logra N evaluación(es) con puntaje perfecto» / «Evalúa N día(s) seguidos». |
| `LearningPathsScreen(viewModel, onOpenCourse, modifier)` (public) | 164-189 | Lista de rutas con `LinearProgressIndicator(percent/100)` y «x de y cursos · p %»; «Ver ruta» → `openPath`; «Ver más rutas». `PathDetail` (191-215): avance, cursos con Eyebrow «CURSO n [· SIGUIENTE]» (usa `progress.nextCourseId`), «Continuar/Abrir curso» solo si `courseStatus == PUBLISHED`, «Volver a las rutas» → `closePath`. |
| `CertificateVerificationScreen(viewModel: CertificateVerificationViewModel, onHome, modifier)` | 220-251 | Pantalla pública (`BpmTextField`, `RaisedButton("Verificar")`, resultado en tarjeta). |

Puentes en `app/TrainingContent.kt` (118 líneas): `TrainingCourseContent(userId, organizationId, courseId, canPersist, training, modifier)` L25-33; `TrainingAdminContent` L36-41; `TrainingMemberSelector`; `TrainingPlusAdminContent` (admin); `TrainingLearnerHeader(userId, organizationId, member, learner, onOpenCourse, onAchievements, onPaths)` L98-102 (hace `learner.open` y pinta `MyAssignmentsPanel`); `TrainingLearnerContent(userId, organizationId, member, paths: Boolean, learner, onOpenCourse, modifier)` L106-112 (abre y `loadPaths()`/`loadAchievements()`); `CertificateVerificationContent` L115-118.

### 4.4 Rutas de formación (App.kt L924-935) y shell
`TRAINING_COURSE="aprender/{courseId}"` (param validado `[A-Za-z0-9_-]{1,64}`), `TRAINING_ACHIEVEMENTS="aprender-logros"`, `TRAINING_PATHS="aprender-rutas"`, `TRAINING_MANAGEMENT="empresa/academia"` (solo canManage), `VERIFY_CERTIFICATE="verificar/{codigo}"` (pública).
Las tres rutas de aprendizaje del WORKER se alojan con `secondaryEditor = true` ⇒ **sin `BpmPageHeader`, barra inferior oculta, atrás en la cabecera**. `secondaryTitle` es `null` para ellas (el título lo pinta cada pantalla: `Text(..., headlineMedium)`).
Alertas: `"TRAINING_ASSIGNMENT" → nav Learn.route` (App.kt L~643-?; `onOpenSource` de `AlertsScreen`).
Sin `BpmActionBar` en formación: ningún flujo de aprendizaje publica barra fija hoy (los botones de acción van inline).

---------------------------------------------------------------------------------------------------
## 5. Tareas («Mis tareas»)

### 5.1 Qué monta App.kt para `Tasks` (L620-626)
```kotlin
taskContent = { Column(Modifier.fillMaxSize()) {
    BpmSecondaryButton(onClick = { leaveRun { nav.navigate(INCIDENT_TASKS) } }, enabled = !dailyState.busy) { Text("Incidencias y correcciones") }
    DailyOperationsContent(profile.id, workspaceState.workspace?.role.orEmpty(), workspaceState, workspace, dailyOperations, Modifier.weight(1f),
        onCleaningPhotos = { execution -> openCleaningPhotos(execution) })
} }
```
Rutas: `tareas` (`WorkspaceDestination.Tasks.route`, tratada como `daily`) y `tareas/incidencias` (`INCIDENT_TASKS`, `incidentRoute`). No se pasan `shifts`/`stock` (null) ⇒ sin chips de turnos/consumos (`showShifts = shifts != null && role != "WORKER"` es falso para WORKER aunque se pasara el VM).

### 5.2 `app/DailyOperationsContent.kt` (117 líneas) — `internal fun DailyOperationsContent(userId, role, workspace: WorkspaceState, workspaceActions: WorkspaceViewModel, operations: OperationsViewModel, modifier, sourceSiteId: String? = null, sourceSiteName: String = "", sourceReceiptId: String? = null, shifts: ShiftsViewModel? = null, stock: StockMovementsViewModel? = null, onCleaningPhotos: ((CleaningExecution)->Unit)? = null)`
- `siteId`/`siteName`/`choosingSite` en `rememberSaveable`. **La sede NO se auto-selecciona**: si `state.resource.needsSite && siteId == null` ⇒ `choosingSite = true` y se muestra «¿En qué sede trabajarás?» + `WorkspaceSiteSelector(workspace, workspaceActions, siteId, siteName, enabled){ siteId=it.id; siteName=it.name }` (`app/WorkspaceSiteSelector.kt`, 42 líneas). Luego «Sede: X» + `BpmTextButton("Cambiar sede")`.
- `LaunchedEffect(userId, org, role, siteId) { operations.open(userId, org, role, siteId) }`; `DisposableEffect → operations.reset()` al salir.
- Dibuja `OperationsScreen(operations, Modifier.weight(1f), onLotMovement = null (sin stock), onCleaningPhotos)` solo cuando `state.userId/organizationId/role/siteId` coinciden; si no, `LinearProgressIndicator`.

### 5.3 `features/operations/presentation/OperationsScreen.kt` (200 líneas)
`OperationsScreen(viewModel: OperationsViewModel, modifier = Modifier, onLotMovement: ((StorageLot)->Unit)? = null, onCleaningPhotos: ((CleaningExecution)->Unit)? = null)` L20-27 — `Column(fillMaxWidth().verticalScroll(...), spacedBy(12.dp))`:
- Título: `if (state.role == "WORKER") "Mis tareas de limpieza" else "Operación diaria"` (`headlineSmall`) — **título duplicado con el de la sección** (la rama Tasks no pinta `BpmPageHeader`).
- Chips `BpmFilterChip` por `OperationsResource` con `OperationsAccess.canRead(role, it)`: para WORKER solo **`PLANS` («Planes de limpieza») y `EXECUTIONS` («Limpieza realizada»)**; recurso inicial WORKER = `EXECUTIONS` (`OperationsViewModel.open` L57).
- Avisos: `failureText`, `state.notice`, `PendingOperationsNotice("supply-receipts","cleaning-executions")`, `LinearProgressIndicator`.
- Lista (`listBody`, L59-139): `BpmSearchField`; `RaisedButton("Registrar limpieza")` si `OperationsAccess.canCreate(role, resource)` (WORKER: solo `EXECUTIONS`; no puede crear planes ni recepciones); `EmptyState("Sin registros", …)`; `BpmGridLayout(rows) { BpmCard { title(); subtitle(); BpmSecondaryButton("Ver detalle"); BpmTextButton("Fotos de la limpieza") si ExecutionEntry && onCleaningPhotos } }`; `PageNavigation`. Detalle con `BpmListDetail`; formulario `OperationForm` (L153, columna 720 dp); picker `OperationsPicker` (L165-200).
- `OperationsEntry` (`domain/ManageOperations.kt:29-62`): `SupplierEntry, ProductEntry, ReceiptEntry, LotEntry, PlanEntry(CleaningPlan), ExecutionEntry(CleaningExecution)`. Título (`OperationsDetails.kt:63`): `"${area} · ${task}"` para plan y ejecución; subtítulo plan: `"${frequencyLabel} · Activo/Inactivo"`; subtítulo ejecución: `"${resultLabel} · ${displayDateTime(executedAt)}"`.
- Un WORKER **no puede**: recepciones, temperatura, lotes/stock, proveedores, productos, planes (ni turnos ni consumos). Solo planes de limpieza (lectura) y registrar/ver ejecuciones de limpieza (propias; si el servidor devuelve ejecuciones de otra persona la lista se vacía, `OperationsViewModel` ~L135-145).

### 5.4 Modelo de un «pendiente»
- **No existe un modelo de «tarea pendiente de hoy» para WORKER.** Solo `CleaningPlan(id, locationId, area, task, instructions = "", productName = "", frequency: CleaningFrequency = DAILY, dueTime: String = "", active: Boolean = true)` (`Operations.kt:156-166`) con `dueTime` en formato `HH:mm` (vacío permitido; regla `time`: `(?:[01][0-9]|2[0-3]):[0-5][0-9]`) y `CleaningFrequency { DAILY, WEEKLY, MONTHLY, PER_SHIFT }`; no trae estado «hecho hoy».
- `CleaningExecution(input: CleaningExecutionInput(id, locationId, cleaningPlanId, executedAt, result: CleaningResult{COMPLETED, NOT_COMPLETED, OBSERVED}, observation, clientOperationId, clientCreatedAt, shiftId?), area, task, responsibleUserId, responsibleName, createdAt, shiftName = "")`.
- Para saber «pendiente/hecho hoy» habría que cruzar `PLANS` con `EXECUTIONS` de la misma sede y fecha en cliente (hoy no se hace; dos recursos se cargan por separado, uno a la vez en `OperationsState.rows`, `resource`).
- `OperationsState` (`OperationsState.kt:6-55`): `userId, organizationId, role, siteId, resource, rows: List<OperationsEntry>, loading, busy, failure: OperationsFailure?, fields, query, page, previous, token, nextToken, form: OperationsForm?, dirty, pending: PendingOperation?, review, localStatus: DraftSaveStatus, saved, detail, notice, picker, pickerRows…, products, selectedPlan: CleaningPlan?, shifts: List<Shift>, shiftsFailed` + `editable`, `saving`.
- `OperationsViewModel` (1007 líneas) públicos: `reset, open(userId, organizationId, role, siteId=null), resource(value), refreshIfStale(), refresh(), query(value), next(), previous(), newForm(), protect(), leave(onLeft), field(key,value), item(index, change), removeItem(index), pick(resource), closePicker(), pickerQuery, pickerNext, pickerPrevious, select(entry), configure(product), review(), editReview(), save(), closeForm(), discard(), detail(entry), receiptDetail(id), closeDetail()`.
- El formulario de limpieza (`OperationsFormContent.kt:206+`): plan (picker «Buscar plan de limpieza»), turno (obligatorio si el plan es `PER_SHIFT`; `state.shifts` se carga con el lambda `manageShifts.list(org, site, role, false)`; `ShiftAccess.canRead` incluye WORKER, solo se deniega `includeInactive=true` a quien no gestiona, por eso el WORKER sí ve los turnos activos), resultado, observación; revisión (`review`), `save` (cola offline `QUEUED` ⇒ aviso «Guardado en este dispositivo…»), fotos vía `onCleaningPhotos` (solo propia y < 24 h: `openCleaningPhotos` en App.kt L~224-232).

### 5.5 Incidencias del WORKER (`INCIDENT_TASKS = "tareas/incidencias"`)
- Composable raíz: `app/IncidentsContent.kt` (73 líneas) `IncidentsContent(userId, role, workspace, workspaceActions, incidents: IncidentsViewModel, onEvidence: (incidentId, actionId?, stage, writable)->Unit, modifier)`: misma mecánica de sede («¿En qué sede trabajarás?» obligatoria, `choosing=true` al inicio; `zone = it.timezone`), `incidents.open(userId, org, role, siteId, zone)`, luego `IncidentsScreen(incidents, Modifier.weight(1f), onEvidence)`.
- `features/incidents/presentation/IncidentsScreen.kt` (393 líneas): `IncidentsScreen(viewModel, modifier, onEvidence)` L17; `IncidentChoices` L198; `IncidentDetailContent` L260; helpers `incidentActionLabel/RiskLabel/StatusLabel/FailureText` L343-380. Para WORKER muestra «Aquí ves las incidencias que reportaste o te asignaron.» (L124); no ve «Configurar criterios» (`IncidentsAccess.canAuthor` = ADMIN/SUPER_ADMIN).
- Estado `IncidentsState` (`IncidentsState.kt:23-44`): `notice, userId, organizationId, siteId, role, timeZone, authorized, loading, failure: IncidentsFailure?, catalogMode, status: IncidentStatus?, incidents: IncidentPage<IncidentSummary>, catalog, detail: IncidentDetail?, picker: IncidentPicker?{CATALOG,ASSIGNEE}, choices, catalogChoices, history, showHistory`. `IncidentSummary(id, locationId, catalogId, name, area, riskLevel: IncidentRisk{LOW..CRITICAL}, status: IncidentStatus{OPEN, ASSIGNED, IN_PROGRESS, RESOLVED, CLOSED, VERIFIED}, description, reportedByName, createdAt, rowVersion)`; `CorrectiveAction.dueAt: String` (fecha límite). `IncidentsAccess.canReport/canAttend = ADMIN|CHEF|WORKER`.
- Shell: `incidentRoute` ⇒ `secondaryEditor=true`, `secondaryTitle="Incidencias y correcciones"`, pattern Panel; el Back usa `incidents.prepareBack{}`/`prepareLeave`. `IncidentDeadline.kt` es `expect fun incidentDeadline(date, time, timeZone): String?`.
- El WORKER NO puede usar el atajo «Incidencias» de la nav (solo CHEF); entra por el botón de «Mis tareas».

---------------------------------------------------------------------------------------------------
## 6. Sistema de diseño (`B/core/designsystem/`, 22 archivos) y offline UI

Nota: **no existe un objeto `BpmTheme` de colores**. `BpmTheme(content)` instala `MaterialTheme(lightColorScheme, typography, shapes)` (solo tema claro; no hay `isSystemInDarkTheme`). Los tokens crudos están en `object BpmTokens`.

### 6.1 `BpmTheme.kt` (160 líneas)
- `object BpmTokens`: `primary 0xFF005B59`, `text 0xFF233F4D`, `muted 0xFF58676D`, `background 0xFFF5F6F6`, `soft 0xFFEDF5F2`, `accent 0xFFED5700`, `border 0xFFD9DFE0`, `fieldBorder 0xFF859297`, `primaryRelief 0xFF003D3C`, `secondaryRelief 0xFF99BDBC`, `successText 0xFF216242`, `successSurface 0xFFE7F4EC`, `warningText 0xFF765400`, `warningSurface 0xFFFFF2D1`, `dangerText 0xFFA42A24`, `dangerSurface 0xFFFFF0EE`.
- ColorScheme: `primary=primary`, `secondary=accent` (naranja, «solo detalles»), `primaryContainer=soft`, `secondaryContainer=0xFFFFE8D9`, `background`, `surface=White`, `onSurface/onBackground=text`, `onSurfaceVariant=muted`, `surfaceContainer=soft`, `outline=fieldBorder`, `outlineVariant=border`, `error=dangerText`, `errorContainer=dangerSurface`, `onErrorContainer=dangerText`. No hay `tertiary`; éxito/advertencia solo vía `BpmTokens`.
- Tipografía: `headlineLarge/Medium/Small` y `titleLarge` = `ExtraBold`; `titleMedium` = `Bold`; `bodySmall` 13/18 sp; `labelMedium` 13/16; `labelLarge` 15/20 `Bold`. Roles: página `headlineMedium`, sección `titleLarge`, tarjeta `titleMedium`, cuerpo `bodyMedium`, apoyo `bodySmall`.
- `object BpmType` (`@Composable get()`): `bodyStrong`, `bodyMediumStrong`, `bodySmallStrong`, `labelStrong`.
- Formas: small 12 dp, medium 16 dp, large 24 dp.
- `object BpmSpacing`: `gutter(wide: Boolean)`: 32/20; `gutter(windowClass)` = 16/24/32/32; `section 20`, `grid 16`, `card 24`, `cardCompact 16`, `touch 48`, `button 52`, `buttonCompact 44`, `buttonMinWidth 160`, `reliefDepth 3`, `contentMax 1200`, `searchMax 560`; escala `xs 4, sm 8, md 12, lg 16, xl 24, xxl 32, xxxl 48, huge 64`.
- `enum BpmButtonSize { Normal, Compact }`; `enum BpmCardVariant { Standard, Compact, Featured }`.

### 6.2 Tarjetas y botones
- `Components.kt` (88 líneas): `object BpmBrand { name = "BPM" }`; `Brand(compact=false)`; `Mascot(modifier, learning=false)` (imágenes `mascot_learning`/`mascot_welcome`); `BpmCard(modifier, tint: Color = Unspecified, variant: BpmCardVariant = Standard, content: ColumnScope.()->Unit)` (forma 24 dp, borde 2 dp `outlineVariant`, relieve 3 dp, `Column` con `spacedBy(12.dp)`, padding 24/16; `tint` manda sobre `variant`); `RaisedButton(label: String, onClick, modifier, enabled = true, size = Normal)` (**recibe `String`, no slot**; se deshabilita con `serverActionBlocked()`); `Eyebrow(text)` (`BpmType.labelStrong` color primary); `EmptyState(title, description)` (BpmCard con título `titleMedium` + descripción `onSurfaceVariant`) — **EmptyState está en Components.kt:85**.
- `BpmButtons.kt` (92): `BpmSecondaryButton(onClick, modifier, enabled=true, size=Normal, content: RowScope.()->Unit)`; `BpmTextButton(onClick, modifier, enabled=true, contentPadding=PaddingValues(12,8), content: RowScope.()->Unit)`; `@Composable Modifier.bpmPrimaryButtonWidth()` (Compact ⇒ `fillMaxWidth()`, si no `widthIn(min=160)`).
- `BpmRelief.kt`: `Modifier.bpmRelief(color, shape, depth)`. `BpmFeedback.kt`: `BpmFeedback(color, content)` (ripple).
- `BpmActionBar.kt` (57): `class BpmActionBarSlot` (`@Stable`, `internal var content by mutableStateOf`); `val LocalBpmActionBarSlot = compositionLocalOf<BpmActionBarSlot?> { null }`; `@Composable fun BpmActionBar(content)` — en Compact con slot publica `content` en la ranura (`DisposableEffect`, lo borra al salir); en no-Compact o sin slot lo dibuja inline; `@Composable fun BpmActionBarHost(slot)` — `Surface` con borde `outlineVariant`, padding `gutter × md`, lo dibuja `WorkspaceScreen` justo sobre la `NavigationBar` (L246). **`BpmActionBar` no lo usa ninguna pantalla hoy** (solo el host en el shell). Si se usa, el shell oculta la barra de nav solo cuando `secondaryPattern == StepFlow` y `secondaryContent != null`.

### 6.3 Layout y patrones
- `BpmLayoutComponents.kt` (220): `BpmPageHeader(title, modifier, description: String? = null, actions: (@Composable RowScope.()->Unit)? = null)` (título `headlineMedium` con `semantics{heading()}`; acciones a la derecha desde 600 dp); `BpmStatTile(value: String, label: String, modifier, support: String? = null, featured: Boolean = false)` (`BpmCard` Compact/Featured, cifra `headlineMedium`/`headlineLarge` color primary); `BpmActionTile(symbol: BpmSymbol, title, description, onClick, modifier)` (fila clicable con icono en recuadro 40 dp y flecha); `BpmListRow(title, modifier, detail: String? = null, chip: String? = null, actions: (@Composable RowScope.()->Unit)? = null)` (avatar iniciales; fila densa ≥ 840 dp, si no tarjeta compacta); `BpmInlineNotice(message, modifier, onDismiss: (()->Unit)? = null, dismissLabel = "Entendido", warning = false)`; `internal fun initialsOf(name)`.
- `BpmScreenPatterns.kt` (298): `enum BpmPattern { Panel, ListDetail, Grid, StepFlow, Form, Access }`; `object BpmPatternRules` (`listWidth 360`, `readingWidth 720`, `accessWidth 440`, `gridMinCard 300`, `contentMax(pattern, windowClass, preview=true)`, `LARGE_FONT_SCALE 1.3`, `indicatorColumns(windowClass, count=4, fontScale=1f)`, `gridColumns(windowClass)` (Compact 1, Medium 2, Expanded null/auto, Large 3), `detailBesideList`, `panelSplit` (solo Large), `formTwoColumns`, `formPreview`); `BpmScreenFrame(pattern, modifier, content: ColumnScope.()->Unit)` (margen por clase, tope de ancho, `spacedBy(BpmSpacing.xl)`); `BpmScreen(title, pattern, modifier, description, actions, content)`; **`BpmPanelLayout(modifier, indicators: List<@Composable (Modifier)->Unit> = emptyList(), support: (@Composable ColumnScope.()->Unit)? = null, main: @Composable ColumnScope.()->Unit)`** (indicadores en `BpmCardGrid(fixedColumns = indicatorColumns(...))`: Compact 2 col (3 si `count==3 && fontScale<1.3`), si no `count.coerceIn(1,4)`; en Large main 2/3 + support 1/3; antes, support debajo); `BpmListDetail(modifier, detail, emptyDetail, list)` (lista de 360 dp dentro con `ProvideBpmWindowClass(Compact)`); **`BpmGridLayout(items: List<T>, modifier, content: @Composable (T, Modifier)->Unit)`** (usa `gridColumns`, `minCardWidth = gridMinCard`, `maxColumns=4`); `BpmStepFlow(modifier, actionBar, content)`; `BpmFormLayout(modifier, preview, actions, content)`; `BpmShortFields(first, second, modifier)`; `BpmCenteredCard(modifier, content)`; `BpmAccessScreen(modifier, pattern = Access, scrollable = true, content)`. `BpmScreen`, `BpmStepFlow`, `BpmFormLayout` **no se usan fuera de designsystem** (verificado por grep).
- `BpmCardGrid.kt`: `BpmCardGrid(items: List<T>, modifier, minCardWidth: Dp = 360.dp, maxColumns: Int = 3, fixedColumns: Int? = null, content: @Composable (T, Modifier)->Unit)` — filas con `IntrinsicSize.Max` (misma altura) y `bpmAppear(índice)` por tarjeta.
- `BpmWindowClass.kt` (97): `enum BpmWindowClass(margin) { Compact(16), Medium(24), Expanded(32), Large(32) }` con `sideNavigation`, `companion { MEDIUM_FROM=600, EXPANDED_FROM=840, LARGE_FROM=1200, fromWidth(Dp) }`; `enum BpmNavigationMode(width) { BottomBar(0), Rail(80), SideMenu(240) }` con `MIN_SIDE_HEIGHT = 480.dp`, `of(windowClass, height)`; `val LocalBpmWindowClass` (default Compact), `val LocalBpmWindowHeight` (800.dp), `currentWindowClass`, `currentWindowHeight` (`@ReadOnlyComposable`), `ProvideBpmWindowClass(windowClass, height, content)`, `BpmWindowBox(modifier, content: BoxScope.(BpmWindowClass, Dp)->Unit)`.

### 6.4 Controles
- `BpmChoiceRow(label: String, selected: Boolean, enabled: Boolean = true, supportingText: String? = null, onSelect: () -> Unit)` (selección única, fila ≥ 48 dp, anima color).
- `BpmFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier = Modifier, enabled = true)`.
- `BpmIntroCard(title: String, description: String, learning: Boolean = false, actions: ColumnScope.()->Unit = {})` — tarjeta `primaryContainer` + `Mascot` (128 dp si ≥600 dp ancho, 64 dp si no).
- `BpmCheckboxRow(label, checked, enabled=true, onChecked)`; `BpmFieldPair(first, second, modifier)`; `BpmSearchField(value, onValueChange, label, modifier, enabled, loading, error, supportingText, placeholder, suggestions, onSelect, keyboardOptions, navigation, …)`; `BpmTextField(value, onValueChange, label, modifier, error, enabled, …)`; `BpmPagedChoiceSelector(options: List<BpmChoiceOption(id,label)>, selectedId, selectedDescription, query, page, loading, …)`; `BpmFormNavigation`/`rememberBpmFormNavigation(ids)`; `BpmInputKind { Text, PersonName, Email, Password, Phone, Decimal, … }`; `PageNavigation(number, canPrevious, canNext, loading, onPrevious, onNext)`; `DiscardDraftConfirmation(onKeep, onDiscard, enabled)`; `DraftSaveNotice(status, onRetry)`; `LocalUserActivity`, `Modifier.trackUserActivity`.
- `BpmIcon.kt` (30): `enum BpmSymbol { Home, Learn, Control, Team, Company, Account, Sprout, Refresh, ChevronDown, Back, Logout, Check, Bell, Clock, Alert }` (recursos Lucide `ic_*`); `BpmIcon(symbol, contentDescription: String? = null, modifier, tint = LocalContentColor.current)` (siempre 24 dp). **Solo hay 15 símbolos**: no existen iconos de trofeo, fuego/racha, play, candado, estrella, libro cerrado, etc.; añadir uno = nuevo `ic_*.xml/svg` en `composeApp/src/commonMain/composeResources/drawable` + entrada en el enum.
- `BpmMotion.kt` (115): `object BpmMotion { SHORT 150, MEDIUM 300, LONG 450, STAGGER 40, STAGGER_MAX_ITEMS 5, enter, exit, spatial<T>(), fadeIn<T>() }`; `expect fun rememberReduceMotion(): Boolean`; `Modifier.bpmAppear(index: Int = 0)` (`@Composable`, aparece una vez al componerse: sube 16 dp y fundido 300 ms con retraso `index*40` ms hasta 5); `BpmReveal(modifier, content)` (fade+expand, sale con shrink; ya en `WorkspaceScreen` para `state.notice`).

### 6.5 `core/offline/OfflineUi.kt` (UI pública)
```kotlin
val LocalOnline = compositionLocalOf { true }
val LocalServerOnlyActions = compositionLocalOf { false }
@Composable fun serverActionBlocked(): Boolean
@Composable fun ServerOnly(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)      // L42-43 (provee LocalServerOnlyActions=true, muestra OfflineRequiredNotice, deshabilita RaisedButton/BpmSecondaryButton sin red)
val LocalPendingMarks = compositionLocalOf { PendingMarks() }                                              // PendingMarks.isPending(entityId), forResource(vararg)
@Composable fun PendingBadge(entityId: String, modifier: Modifier = Modifier)                              // L56-57 «Pendiente de enviar»/«Necesita tu revisión»
@Composable fun PendingOperationsNotice(vararg resources: String, modifier: Modifier = Modifier)           // L70-71 BpmCard liveRegion
@Composable fun OfflineRequiredNotice(modifier)  ;  OfflineStatusBar(state, onOpen, onSync, modifier) ; PendingChangesScreen(...) ; OtherAccountNotice(state, modifier) ; LogoutDialog(...)
fun relativeAge(nowMillis: Long, thenMillis: Long): String                                                 // OfflineViewModel.kt:79
```
`OfflineStatusBar` se dibuja sobre toda la app (banner de `SessionContent`), ya ocupa espacio superior.

---------------------------------------------------------------------------------------------------
## 7. Tests relacionados

Stack: `kotlin.test` + `runTest`; **no hay tests de Compose UI** (grep de `runComposeUiTest|createComposeRule|onNodeWithText|assertIsDisplayed` en commonTest/jvmTest/iosTest = 0 resultados); no hay screenshot tests; ningún test afirma textos de pantallas de formación/Home/Tareas (los textos `todavía no tiene preguntas…` etc. del VM no se afirman; verificado por grep). Los textos que SÍ se afirman son solo las etiquetas de destinos.

| Archivo | Una línea |
|---|---|
| `T/features/workspace/WorkspaceNavigationTest.kt` | Etiquetas y orden de nav por rol (ver §1.3) — el único que fija `"Inicio","Mis tareas","Aprender"`. |
| `T/features/workspace/OperatingAccessTest.kt` | Quién puede operar y el primary de ADMIN. |
| `T/features/workspace/WorkspaceTest.kt` | Repositorio/VM del workspace: requiere sesión, errores sanitizados, cambio de tenant/logout cancela cargas. |
| `T/features/workspace/WorkspacePlaceholderTest.kt`, `WorkspaceFormAccessTest.kt`, `WorkspaceValidationTest.kt`, `WorkspacePaginationTest.kt`, `WorkspaceDraftRecoveryTest.kt`, `MemberEligibilityTest.kt`, `DataFreshnessTest.kt` | Reglas de placeholder, acceso a formularios, validación, paginación, borradores, elegibilidad, frescura (sin UI). |
| `T/features/training/TrainingViewModelTest.kt` | `TrainingViewModel`: abrir/retomar guarda `lastLessonId`, fallo de progreso no bloquea, video bajo demanda, estudio sin puntaje, evaluación (bloqueo hasta responder todo, resultados, reintento idéntico, doble tap, rechazo definitivo, `VERSION_OUTDATED`), descarte de respuestas tardías. Lee `state.value.screen/lessonId/study/evaluation/course/video/notice/progressNotSaved/evaluationQuestions` y usa `TrainingPane.OVERVIEW/LESSON/STUDY/EVALUATION`. |
| `T/features/training/TrainingViewModelOfflineTest.kt` | Intento en cola bloquea respuestas (`evaluation.queued`), estudio sin red permite saltar, video descargado se reproduce local. |
| `T/features/training/TrainingPlusViewModelTest.kt` | `TrainingLearnerViewModel` (asignaciones con paginación sin duplicados, SUPER_ADMIN no pide datos, logros/ruta detalle y `back()`), VM de administración, verificación pública y evaluación por módulo. Lee `LearnerState.assignments/assignmentsNext/assignmentsFailure/achievements/paths/pathDetail/pathDetailFailure`. |
| `T/features/training/TrainingUseCasesTest.kt`, `TrainingPlusDomainTest.kt`, `TrainingDraftRulesTest.kt` | Casos de uso/reglas de dominio (preparación de intentos, `resumeLesson`, `retryable`, YouTube, fechas de asignación, límites del borrador). |
| `T/features/training/RemoteTrainingRepositoryTest.kt`, `RemoteTrainingPlusRepositoryTest.kt`, `OfflineTrainingTest.kt`, `TrainingFixtures.kt` | Contratos HTTP/DTO, catálogo y descargas offline; `TrainingFixtures` construye los modelos de forma posicional. |
| `T/features/operations/OperationsRulesTest.kt` | `OperationsAccess`: WORKER no lee PRODUCTS, no crea RECEIPTS, sí crea EXECUTIONS. También `OperationsViewModelTest`, `CleaningShiftTest`, `ShiftsTest` (WORKER no lista turnos inactivos), `StockMovementsTest`. |
| `T/features/incidents/*` (`IncidentsViewModelTest`, `IncidentsRulesTest`, `IncidentDeadlineTest`, …) | Lógica de incidencias; sin UI. |
| `T/core/designsystem/BpmWindowClassTest.kt` (commonTest) | Cortes 600/840/1200, márgenes 16/24/32/32, modo de navegación (BottomBar si alto < 480), reglas de patrón (`BpmPatternRules`). |
| `jvmTest/.../core/designsystem/BpmLayoutComponentsTest.kt` | Solo `initialsOf` (5 casos). |
| `T/core/offline/*` | Outbox/sync/LocalFirst/PrivateFiles; `OfflinePhase2Test`, etc. (no UI). |
- Cambiar firmas públicas de `TrainingViewModel`, `TrainingLearnerViewModel`, `TrainingState`, `EvaluationState`, `StudyState`, `LearnerState` o `OperationsAccess` rompe tests de lógica; cambiar solo UI/composables no rompe ningún test existente excepto los de `WorkspaceNavigationTest` si se tocan `label`/orden.
- Verificación del repo (AGENTS.md): `./gradlew :composeApp:jvmTest :composeApp:wasmJsBrowserDistribution :composeApp:assembleDebug` + `node --test web/server.test.mjs`; iOS en macOS.

---------------------------------------------------------------------------------------------------
## 8. Observaciones (restricciones y riesgos)

1. **Shared con ADMIN/CHEF**: `Home()` (WorkspaceContent.kt), la rama `Learn` de `WorkspaceScreen`, `TrainingScreen`, `MyAssignmentsPanel`, `AchievementsScreen`, `LearningPathsScreen`, `OperationsScreen`, `IncidentsScreen` y `Entries` los usan también ADMIN y CHEF (Learn está en sus destinos; `learningAllowed` en Home). Un rediseño solo para WORKER exige ramificar por `workspace.role == "WORKER"` o crear composables nuevos/`WorkspaceDestination` nuevos sin tocar los compartidos; `TrainingScreen` no recibe rol (solo `canPersist`).
2. **Home del WORKER hoy es genérico** (Sedes/Cursos/Controles + «Estado de tu espacio»): `homeContent` es `null` para WORKER; `trainingLearner`, `assignments` de controles y reportes no se abren en Home. Para datos personalizados («Hoy») hay que abrir `trainingLearner.open(profile.id, org.id, member=true)` en Home y pasar un `homeContent`; los controles programados (`ChecklistAssignmentsViewModel`) están vedados para WORKER (solo se abren con `Operations`/`canManage`).
3. **«Mis tareas» no tiene modelo de pendientes** (§5.4): no hay estado «hecho hoy» ni lista combinada plan+ejecución; `dueTime` es opcional (`HH:mm` o vacío) y la frecuencia incluye `WEEKLY/MONTHLY/PER_SHIFT`, cuya vigencia «hoy» el cliente no calcula. Un rediseño tipo «pendientes de hoy» exige cruzar `PLANS` y `EXECUTIONS` (hoy `OperationsViewModel` carga un solo `resource` a la vez en `rows`) o nuevo endpoint; la sede debe elegirse manualmente cada vez (`rememberSaveable`, sin sede por defecto aunque el WORKER tenga una sola).
4. **La rama `Tasks` del shell no pinta `BpmPageHeader` ni `screenNotice()` ni scroll** y `OperationsScreen` añade su propio título «Mis tareas de limpieza»; las rutas hijas (curso, logros, rutas, incidencias) tampoco llevan cabecera de página y ocultan la barra inferior (`secondaryEditor`), por lo que cada pantalla gestiona su título y su scroll.
5. **Racha, % de curso, lecciones completadas y resumen de lección no existen en el modelo/contrato**: solo `ProgressStatus`, `lastLessonId`, `bestScore`, `experiencePoints`, asignaciones con `dueAt/status`, totales XP y rutas con `percent`/`nextCourseId`. El criterio `STREAK_DAYS` es solo texto. Cualquier barra de progreso por curso/lección o «racha» necesita backend/DTO nuevo (`TrainingDto.kt`, `TrainingPlusDto.kt` con `@Serializable`) y campos con valor por defecto al final (los tests construyen modelos posicionalmente; `RemoteTraining*RepositoryTest` validan el contrato).
6. **La evaluación muestra todas las preguntas en una sola página** (`EvaluationState` sin índice); `canSubmitEvaluation()`/`answers.size == total` dependen de ello. Un asistente pregunta-a-pregunta requeriría un índice en `EvaluationState` (UI-only) sin cambiar el cuerpo enviado (el intento se arma con todas las respuestas; `AttemptSubmission` no cambia). El modo estudio sí es pregunta a pregunta (`StudyState.index`).
7. **Navegación interna del curso es estado, no rutas**: `TrainingPane` (OVERVIEW/LESSON/STUDY/EVALUATION) vive en `TrainingState`; el Back del navegador/cabecera pasa por `training.back()`; deep-link a una lección/evaluación no existe. Mantener esto evita tocar `App.kt` (`closeForm`, `usageModuleFor`, mapa de rutas) y el redireccionamiento de acceso.
8. **Iconos limitados** (`BpmSymbol` con 15 valores; `Tasks`, `Operations` y `Supervision` comparten `Control`) y **solo tema claro**; para diferenciar «Hoy»/«Mis tareas»/«Aprender» o mostrar logros/racha hay que añadir drawables `ic_*` + valores al enum. `WorkspaceIcon` es `internal` en workspace; `BpmIcon` es el único punto de iconos. La nav de WORKER son 3 ítems (la barra admite hasta 5): hay holgura sin tocar la lógica de recorte.
9. **Restricciones de plataforma**: el reproductor y el selector de video son `expect/actual` (Android/iOS no se compilan sin SDK/macOS); en web el `<video>`/iframe se superpone al área de Compose, por lo que `LessonVideoPlayer`/`ExternalLessonVideo` deben conservar `fillMaxWidth().aspectRatio(16f/9f)` y no quedar bajo `clip`/`graphicsLayer` ajenos. El video privado usa URL firmada efímera (`VideoTicket`), nunca se persiste; los videos de YouTube requieren conexión (`offlineAvailable=false`).
10. **Offline y permisos**: cualquier acción nueva de escritura debe tratar `QUEUED` como éxito local (aviso + `PendingBadge`/`PendingOperationsNotice`); lo que requiera servidor va en `ServerOnly` (deshabilita `RaisedButton`/`BpmSecondaryButton` sin red — los botones nuevos deben usar estos dos componentes o `serverActionBlocked()`). Las alertas con `sourceType == "RECEIPT_ITEM"` navegan a `DAILY_EXECUTION` (ruta de Operations), que un WORKER no tiene permitida: el shell lo redirige a Home con «Acceso restringido»; `TRAINING_ASSIGNMENT` sí lleva a Learn. Nuevas rutas deben registrarse en el mapa destino→rutas de `App.kt:385-395` y en `usageModuleFor`.
