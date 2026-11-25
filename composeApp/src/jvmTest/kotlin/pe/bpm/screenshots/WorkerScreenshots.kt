package pe.bpm.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_learning
import pe.bpm.resources.mascot_welcome
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import pe.bpm.app.DailyOperationsContent
import pe.bpm.app.TrainingCourseContent
import pe.bpm.app.TrainingLearnerHeader
import pe.bpm.app.WorkerProfileContent
import pe.bpm.app.WorkerTodayContent
import pe.bpm.core.designsystem.BpmPattern
import pe.bpm.core.storage.TestDraftStorage
import pe.bpm.features.incidents.domain.ManageIncidents
import pe.bpm.features.incidents.presentation.IncidentPicker
import pe.bpm.features.incidents.presentation.IncidentsViewModel
import pe.bpm.features.incidents.presentation.WorkerReportContent
import pe.bpm.features.operations.presentation.QuickStep
import pe.bpm.features.operations.presentation.WorkerQuickTask
import pe.bpm.features.operations.data.LocalOperationsDraftRepository
import pe.bpm.features.operations.domain.ManageOperations
import pe.bpm.features.operations.domain.ManageOperationsDraft
import pe.bpm.features.operations.domain.OperationsResource
import pe.bpm.features.operations.presentation.OperationsViewModel
import pe.bpm.features.training.FakeTrainingRepository
import pe.bpm.features.training.domain.*
import pe.bpm.features.training.presentation.*
import pe.bpm.features.workspace.domain.*
import pe.bpm.features.workspace.presentation.*
import pe.bpm.screenshots.WorkerFixtures as F

/**
 * Arnés de capturas del rol WORKER («Personal»): dibuja las pantallas reales dentro del shell real
 * (`WorkspaceScreen`: barra inferior, riel o menú lateral) con ViewModels reales y repositorios falsos, en 3 anchos.
 *
 * Solo corre al pedirlo por nombre (el build activa la propiedad `bpm.screenshots`):
 * `./gradlew :composeApp:jvmTest --tests '*WorkerScreenshots*'` → `composeApp/build/screenshots/`
 *   - `<pantalla>-<ancho>.png`        ventana del dispositivo (lo que se ve sin desplazar).
 *   - `<pantalla>-<ancho>-full.png`   todo el contenido, solo si no cabe en la ventana.
 *   - `sheet-<pantalla>.png`          los tres anchos lado a lado (máx. 2000 px).
 * Para agregar una pantalla basta con añadir un [ScreenSpec] a [workerScreens].
 */
/** [full] en falso omite la captura «completa»: las pantallas de juego llenan la ventana y desplazan por dentro. */
internal class ScreenSpec(val name: String, val full: Boolean = true, val build: () -> @Composable () -> Unit)

private var idCounter = 0
private fun nextId() = "00000000-0000-4000-8000-%012d".format(++idCounter)
private const val NOW = "2026-11-20T10:00:00Z"

// ---- Piezas comunes --------------------------------------------------------------------------------

private val workspaceState = WorkspaceState(
    organizations = listOf(F.organization), selected = F.ORG, workspace = F.workspace,
    pages = WorkspaceResource.entries.associateWith { WorkspacePageState() },
)

private fun learnerVm(): TrainingLearnerViewModel {
    val repo = F.LearnerRepository()
    return TrainingLearnerViewModel(LoadMyAssignments(repo), LoadMyAchievements(repo), LoadLearningPaths(repo), loadCourse = LoadTrainingCourse(F.CourseRepository()))
        .also { it.open(F.USER, F.ORG, member = true); it.loadAchievements(); it.loadPathCourses() }
}

private fun operationsVm(resource: OperationsResource? = null, empty: Boolean = false): OperationsViewModel =
    OperationsViewModel(
        ManageOperations(F.OperationsFixtureRepository(empty), F.NoopTransport),
        ManageOperationsDraft(LocalOperationsDraftRepository(TestDraftStorage(), "https://example.test")),
        ::nextId, { NOW },
    ).also {
        it.open(F.USER, F.ORG, "WORKER", F.SITE)
        if (resource != null) it.resource(resource)
        it.refreshToday()
    }

private fun workspaceVm(): WorkspaceViewModel {
    val r = F.IdleWorkspaceRepository
    return WorkspaceViewModel(LoadOrganizations(r), LoadWorkspace(r), CreateOrganization(r), CreateLocation(r), AddMember(r), LoadWorkspacePage(r))
}

/** Curso de Academia ya cargado en el ViewModel y puesto en la pantalla que pide [drive]. */
private fun courseScreen(initialPick: Int? = null, drive: (TrainingViewModel, FakeTrainingRepository) -> Unit): @Composable () -> Unit {
    val repo = FakeTrainingRepository().apply {
        course = TrainingResult.Success(F.course())
        study = { questionId, selected ->
            val index = F.questionIds.indexOf(questionId)
            TrainingResult.Success(StudyFeedback(selected == F.correctOptions[index], F.correctOptions[index], F.explanations[index]))
        }
        attemptResults.addLast { attempt -> F.result(attempt) }
    }
    val vm = TrainingViewModel(LoadTrainingCourse(repo), RequestLessonVideo(repo), SaveLessonProgress(repo), CheckStudyAnswer(repo),
        SubmitTrainingAttempt(repo), newId = ::nextId, now = { NOW })
    vm.open(F.USER, F.ORG, F.COURSE, canPersist = true)
    drive(vm, repo)
    val state = vm.state.value
    return {
      androidx.compose.runtime.CompositionLocalProvider(LocalInitialPick provides initialPick) {
        Shell(
            WorkspaceDestination.Learn,
            secondaryContent = { TrainingCourseContent(F.USER, F.ORG, F.COURSE, true, vm, onOpenCourse = {}, worker = WorkerCourseContext(onClose = {}, streakDays = 7, moduleNumber = 1, moduleTotal = 4)) },
            secondaryEditor = true,
            // Igual que App.kt: la vista general es un panel; lo demás, un flujo por pasos.
            secondaryPattern = if (state.screen == TrainingPane.OVERVIEW) BpmPattern.Panel else BpmPattern.StepFlow,
            secondaryWideOnExpanded = wantsWidePane(state),
            // Igual que App.kt: modo enfoque fuera de la vista general (sin navegación; cabecera con X y nombre del curso).
            focusMode = isFocusMode(state), focusTitle = state.course?.course?.title, playFocus = true,
        )
      }
    }
}

/** El shell real del WORKER con los mismos parámetros que `App.kt`; cada pantalla aporta su contenido. */
@Composable
private fun Shell(
    section: WorkspaceDestination,
    homeContent: (@Composable () -> Unit)? = null,
    learnHeader: (@Composable () -> Unit)? = null,
    taskContent: @Composable () -> Unit = {},
    secondaryContent: (@Composable () -> Unit)? = null,
    secondaryEditor: Boolean = false,
    secondaryPattern: BpmPattern = BpmPattern.Panel,
    secondaryWideOnExpanded: Boolean = false,
    focusMode: Boolean = false,
    focusTitle: String? = null,
    secondaryTitle: String? = null,
    playFocus: Boolean = false,
    workerLearn: (@Composable (@Composable () -> Unit) -> Unit)? = null,
    workerProfile: (@Composable () -> Unit)? = null,
) {
    WorkspaceScreen(
        viewer = WorkspaceViewer(F.USER, F.DISPLAY_NAME, "lucia.quispe@cocinaandina.pe", false),
        state = workspaceState, section = section, onNavigate = {},
        onVerifySession = {}, sessionBusy = false, sessionMessage = null, sessionConfirmation = null,
        onSelect = {}, onRefresh = {}, onClear = {}, onLogout = {},
        formDraft = WorkspaceFormState(), onSubmitForm = {}, onFormRetryAccount = {}, onFormSelectAccount = {}, onFormEdit = {},
        onFormRetry = {}, onFormDiscard = {},
        form = null, onOpenForm = {}, onCloseForm = {},
        choosingOrganization = false, onChooseOrganization = {}, checklistContent = {}, onManageTemplates = {},
        homeContent = homeContent, onOpenCourse = {}, learnHeader = learnHeader,
        hiddenCourseIds = F.assignments.map { it.course.id }.toSet(),
        onAlerts = {}, unreadAlertCount = 2,
        taskContent = taskContent,
        secondaryContent = secondaryContent, secondaryEditor = secondaryEditor, secondaryPattern = secondaryPattern,
        secondaryWideOnExpanded = secondaryWideOnExpanded,
        focusMode = focusMode, focusTitle = focusTitle, secondaryTitle = secondaryTitle, playFocus = playFocus, workerLearn = workerLearn, workerProfile = workerProfile,
        onOrganizationSearch = {}, onOrganizationNext = {}, onOrganizationPrevious = {}, onOrganizationRetry = {},
        onPageSearch = { _, _ -> }, onPageNext = {}, onPagePrevious = {}, onPageRetry = {},
    )
}

// ---- Las pantallas del personal ---------------------------------------------------------------------

internal val workerScreens: List<ScreenSpec> = listOf(
    ScreenSpec("u1-componentes") { { U1Components() } },
    ScreenSpec("j1-componentes") { { J1Components(part = 1) } },
    ScreenSpec("j1-componentes-b") { { J1Components(part = 2) } },
    ScreenSpec("hoy") {
        val learner = learnerVm()
        val operations = operationsVm()
        val content: @Composable () -> Unit = {
            Shell(WorkspaceDestination.Home, homeContent = {
                WorkerTodayContent(learner, F.USER, F.ORG, operations, F.workspace.locations,
                    onOpenCourse = {}, onContinueCourse = {}, onStudySurvey = {}, onLearn = {}, onTasks = {}, onReport = {}, onAchievements = {}, displayName = F.DISPLAY_NAME, onNormative = {})
            })
        }
        content
    },
    ScreenSpec("hoy-vacio") {
        val learner = learnerVm()
        val operations = operationsVm(empty = true)
        val content: @Composable () -> Unit = {
            Shell(WorkspaceDestination.Home, homeContent = {
                WorkerTodayContent(learner, F.USER, F.ORG, operations, F.workspace.locations,
                    onOpenCourse = {}, onContinueCourse = {}, onStudySurvey = {}, onLearn = {}, onTasks = {}, onReport = {}, onAchievements = {}, displayName = F.DISPLAY_NAME, onNormative = {})
            })
        }
        content
    },
    ScreenSpec("perfil") {
        val learner = learnerVm()
        val content: @Composable () -> Unit = {
            Shell(WorkspaceDestination.Account, workerProfile = {
                WorkerProfileContent(learner, F.USER, F.ORG, F.DISPLAY_NAME, "Cocina Andina", onSettings = {}, onOpenCourse = {}, onLearn = {})
            })
        }
        content
    },
    // Ajustes (accesibilidad, sonido, avisos y cuenta): la misma pantalla para todos los roles.
    ScreenSpec("ajustes") {
        val content: @Composable () -> Unit = {
            Shell(WorkspaceDestination.Account, secondaryContent = {
                pe.bpm.app.SettingsContent(BpmFeedbackSettings(), {}, onAlerts = {}, onDevices = {}, onAcceptInvitation = {}, onLogout = {})
            }, secondaryEditor = true, secondaryTitle = "Ajustes")
        }
        content
    },
    // Texto «Muy grande» (×1,3): comprueba que nada se recorta en las fichas y pantallas principales.
    ScreenSpec("hoy-texto-grande", full = false) {
        val learner = learnerVm()
        val operations = operationsVm()
        val content: @Composable () -> Unit = {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, 1.3f)) {
                Shell(WorkspaceDestination.Home, homeContent = {
                    WorkerTodayContent(learner, F.USER, F.ORG, operations, F.workspace.locations,
                        onOpenCourse = {}, onContinueCourse = {}, onStudySurvey = {}, onLearn = {}, onTasks = {}, onReport = {}, onAchievements = {}, displayName = F.DISPLAY_NAME, onNormative = {})
                })
            }
        }
        content
    },
    ScreenSpec("tareas") {
        val operations = operationsVm()
        val workspace = workspaceVm()
        val content: @Composable () -> Unit = { Shell(WorkspaceDestination.Tasks, taskContent = { Tasks(operations, workspace) }) }
        content
    },
    ScreenSpec("tarea-paso1", full = false) { quickTask(QuickStep.Result) { } },
    ScreenSpec("tarea-nota", full = false) { quickTask(QuickStep.Note) { it.field("result", "OBSERVED"); it.field("observation", "Falta detergente en el dispensador.") } },
    ScreenSpec("tarea-foto", full = false) { quickTask(QuickStep.Photo) { } },
    ScreenSpec("tarea-lista", full = false) { quickTask(QuickStep.Result) { it.save() } },
    ScreenSpec("reportar", full = false) {
        val fixture = F.IncidentsFixture()
        val incidents = IncidentsViewModel(ManageIncidents(fixture, fixture, fixture), ::nextId, { NOW })
        incidents.open(F.USER, F.ORG, "WORKER", F.SITE, "America/Lima")
        incidents.picker(IncidentPicker.CATALOG)
        incidents.newReport()
        incidents.editor.selectCatalog(F.incidentCatalog[1])
        val content: @Composable () -> Unit = {
            Shell(
                WorkspaceDestination.Tasks,
                secondaryContent = { WorkerReportContent(incidents, onClose = {}, onEvidence = { _, _, _, _ -> }, onOpenList = {}, modifier = Modifier.fillMaxSize()) },
                secondaryEditor = true, focusMode = true, playFocus = true,
            )
        }
        content
    },
    // Organización sin categorías creadas: las integradas siempre están y no hay nada que bloquee el reporte.
    ScreenSpec("reportar-sin-catalogo", full = false) {
        val fixture = F.IncidentsFixture(emptyList())
        val incidents = IncidentsViewModel(ManageIncidents(fixture, fixture, fixture), ::nextId, { NOW })
        incidents.open(F.USER, F.ORG, "WORKER", F.SITE, "America/Lima")
        incidents.picker(IncidentPicker.CATALOG)
        incidents.newReport()
        val content: @Composable () -> Unit = {
            Shell(
                WorkspaceDestination.Tasks,
                secondaryContent = { WorkerReportContent(incidents, onClose = {}, onEvidence = { _, _, _, _ -> }, onOpenList = {}, modifier = Modifier.fillMaxSize()) },
                secondaryEditor = true, focusMode = true, playFocus = true,
            )
        }
        content
    },
    ScreenSpec("esqueletos") { { SkeletonGallery() } },
    // Selector de fecha: cerrado, abierto con una fecha y con días anteriores a «min» deshabilitados.
    ScreenSpec("selector-fecha", full = false) {
        {
            androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                BpmDatePicker("", {}, "Fecha límite (opcional)", supportingText = "Vence al terminar ese día (hora universal).")
                androidx.compose.runtime.key("abierto") { BpmDatePicker(BpmCalendar.today().plusDays(3).iso, {}, "Fecha límite (opcional)", min = BpmCalendar.today().iso, supportingText = "Vence al terminar ese día (hora universal).", initiallyOpen = true) }
            }
        }
    },
    // Selector de hora: cerrado y sin hora, y abierto con una hora (atajos, horas y minutos).
    ScreenSpec("selector-hora", full = false) {
        {
            androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                pe.bpm.core.designsystem.BpmTimePicker("", {}, "Hora de referencia (opcional)", supportingText = "Hora de 24 horas, en la zona horaria de la sede.")
                androidx.compose.runtime.key("abierto") { pe.bpm.core.designsystem.BpmTimePicker("08:30", {}, "Hora de referencia (opcional)", supportingText = "Hora de 24 horas, en la zona horaria de la sede.", initiallyOpen = true) }
            }
        }
    },
    // Ayuda contextual, introducción de la primera vez y lista «Primeros pasos» (Personal y Administrador).
    ScreenSpec("ayuda") {
        { Shell(WorkspaceDestination.Account, secondaryContent = { pe.bpm.app.HelpContent(pe.bpm.app.HelpTopic.Home, pe.bpm.app.GuideRole.Worker, onReplay = {}, onSettings = {}) }, secondaryEditor = true, secondaryTitle = "Ayuda") }
    },
    ScreenSpec("primeros-pasos-intro", full = false) {
        { Shell(WorkspaceDestination.Home, secondaryContent = { pe.bpm.app.OnboardingIntro(pe.bpm.app.GuideRole.Worker, onFinish = {}) }, secondaryEditor = true, focusMode = true, playFocus = true) }
    },
    ScreenSpec("primeros-pasos-lista", full = false) {
        {
            val steps = pe.bpm.app.firstSteps(pe.bpm.app.GuideRole.Admin, pe.bpm.app.StepSignals(locations = 1))
            androidx.compose.runtime.CompositionLocalProvider(pe.bpm.app.LocalFirstSteps provides pe.bpm.app.FirstStepsState(steps, {}, {})) {
                Shell(WorkspaceDestination.Home, homeContent = { pe.bpm.app.FirstStepsCard() })
            }
        }
    },
    // Los esqueletos replican la disposición real en cada clase de ancho: pantalla de juego (columna + panel), rejilla y lista–detalle.
    ScreenSpec("esqueleto-hoy", full = false) { { Shell(WorkspaceDestination.Home, homeContent = { BpmSkeletonScreen() }) } },
    ScreenSpec("esqueleto-rejilla", full = false) {
        { Shell(WorkspaceDestination.Learn, secondaryContent = { BpmSkeletonGrid() }, secondaryEditor = true, secondaryTitle = "Cursos") }
    },
    ScreenSpec("esqueleto-lista-detalle", full = false) {
        { Shell(WorkspaceDestination.Learn, secondaryContent = { BpmSkeletonListDetail() }, secondaryEditor = true, secondaryTitle = "Equipo") }
    },
    ScreenSpec("camino") {
        val learner = learnerVm()
        val content: @Composable () -> Unit = {
            Shell(WorkspaceDestination.Learn, workerLearn = { catalog ->
                WorkerLearnContent(learner, F.USER, F.ORG, true, { _, _ -> }, {}, {}, catalog)
            })
        }
        content
    },
    ScreenSpec("guia-curso") { courseScreen { _, _ -> } },
    ScreenSpec("idea", full = false) { courseScreen { vm, _ -> vm.selectLesson(F.lessonIds[2]) } },
    ScreenSpec("pregunta", full = false) {
        courseScreen(initialPick = 0) { vm, _ -> vm.startStudy(F.lessonIds[2], repeatMissed = true) }
    },
    ScreenSpec("pregunta-correcta", full = false) {
        courseScreen { vm, _ ->
            vm.startStudy(F.lessonIds[2], repeatMissed = true)
            vm.answerStudy(F.correctOptions[4])
        }
    },
    ScreenSpec("pregunta-incorrecta", full = false) {
        courseScreen { vm, _ ->
            vm.startStudy(F.lessonIds[2], repeatMissed = true)
            vm.answerStudy(2)
        }
    },
    ScreenSpec("fin-leccion", full = false) {
        courseScreen { vm, _ ->
            vm.startStudy(F.lessonIds[2], repeatMissed = true)
            vm.answerStudy(F.correctOptions[4]); vm.nextStudyQuestion()
            vm.answerStudy(F.correctOptions[5]); vm.nextStudyQuestion()
        }
    },
    ScreenSpec("evaluacion", full = false) {
        courseScreen { vm, _ ->
            vm.startEvaluation(AttemptType.FINAL)
            vm.answerEvaluation(F.questionIds[0], 2)
            vm.answerEvaluation(F.questionIds[1], 1)
            vm.answerEvaluation(F.questionIds[2], 0)
            vm.answerEvaluation(F.questionIds[4], 0)
            vm.goToQuestion(2) // pregunta 3 de 8
        }
    },
    ScreenSpec("revision", full = false) {
        courseScreen { vm, _ ->
            vm.startEvaluation(AttemptType.FINAL)
            F.questionIds.forEachIndexed { i, id -> if (i != 5) vm.answerEvaluation(id, F.correctOptions[i]) } // la 6 queda sin responder
            vm.openReview()
        }
    },
    ScreenSpec("resultado", full = false) {
        courseScreen { vm, _ ->
            vm.startEvaluation(AttemptType.FINAL)
            F.questionIds.forEachIndexed { i, id -> vm.answerEvaluation(id, if (i == 5) 0 else F.correctOptions[i]) } // 7 de 8
            vm.submitEvaluation()
        }
    },
)

/** Registro rápido de una tarea (la vencida del turno) en el shell real, en modo enfoque; [drive] lleva el ViewModel al estado deseado. */
private fun quickTask(step: QuickStep, drive: (OperationsViewModel) -> Unit): @Composable () -> Unit {
    val operations = operationsVm()
    operations.newCleaningFor(F.todayPlans[1])
    drive(operations)
    return {
        Shell(
            WorkspaceDestination.Home,
            secondaryContent = { WorkerQuickTask(operations, null, "Volver a Hoy", Modifier.fillMaxSize(), initialStep = step) },
            secondaryEditor = true, focusMode = true, playFocus = true,
        )
    }
}

/** Piezas del paquete U1 (filas planas, fila de curso, acciones en línea) en los tres anchos, para revisarlas a la vista. */
@Composable
private fun U1Components() {
    BpmTheme {
        BpmWindowBox(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { _, _ ->
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BpmTaskRow("Desinfectar superficies de preparación de la cocina fría", BpmSymbol.Control, detail = "Cocina · Diaria",
                    trailing = { BpmStatusChip("Vencida", BpmTone.Warning) }, onClick = {})
                BpmCard(Modifier.fillMaxWidth()) {
                    BpmTaskRow("Registrar temperatura de cámaras", BpmSymbol.Clock, detail = "Almacén · Diaria", framed = false,
                        trailing = { BpmStatusChip("Hecha", BpmTone.Success) }, onClick = {})
                    BpmRowDivider()
                    BpmTaskRow("Lavar tablas de corte", BpmSymbol.Control, detail = "Cocina · Semanal", framed = false, onClick = {})
                }
                BpmCourseRow("Inocuidad alimentaria básica", "Buenas prácticas de manipulación para todo el personal de cocina y salón.",
                    BpmSymbol.Learn, onClick = {}, progress = 0.4f, status = { BpmStatusChip("En progreso", BpmTone.Info) })
                BpmInlineActions(secondary = { BpmSecondaryButton(onClick = {}) { Text("Anterior") } }, primary = { RaisedButton("Siguiente pregunta", {}) })
            }
        }
    }
}

/**
 * Piezas del paquete J1 (estilo de juego de Personal) con todos sus estados, en los tres anchos. La parte 1 reúne
 * botones, opciones, fichas, misiones, contadores y avance; la parte 2, respuesta, celebración, tomate, camino y navegación.
 */
@Composable
private fun J1Components(part: Int) {
    BpmTheme {
        BpmWindowBox(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)) { windowClass, _ ->
            Row(Modifier.fillMaxSize()) {
                if (windowClass >= BpmWindowClass.Expanded) BpmPlaySideMenu(BpmBrand.name, listOf(
                    BpmPlayNavItem("Hoy", BpmSymbol.Home, true) {}, BpmPlayNavItem("Aprender", BpmSymbol.Route, false) {},
                    BpmPlayNavItem("Tareas", BpmSymbol.Control, false) {}, BpmPlayNavItem("Perfil", BpmSymbol.Account, false) {}))
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (part == 1) J1PartOne() else J1PartTwo()
                }
            }
        }
    }
}

@Composable
private fun J1Label(text: String) = Text(text.uppercase(), style = BpmPlayType.label, color = BpmPlayColors.muted)

@Composable
private fun J1PartOne() {
    Text("Tipografía de juego", style = BpmPlayType.h1, color = BpmPlayColors.ink)
    Text("h2 Nunito 900 · 17 sp", style = BpmPlayType.h2, color = BpmPlayColors.ink)
    Text("Texto de lectura Nunito 700 · 15 sp con acentos: ñ á é í ó ú ¿?", style = BpmPlayType.text, color = BpmPlayColors.ink)
    Text("Apoyo gris Nunito 600 · 14 sp", style = BpmPlayType.sub, color = BpmPlayColors.muted)
    J1Label("Botones gruesos")
    BpmChunkyButton("Continuar", {}, Modifier.fillMaxWidth())
    BpmChunkyButton("Correcto", {}, Modifier.fillMaxWidth(), tone = BpmChunkyTone.Correct)
    BpmChunkyButton("Entendido", {}, Modifier.fillMaxWidth(), tone = BpmChunkyTone.Error)
    BpmChunkyButton("Reportar", {}, Modifier.fillMaxWidth(), tone = BpmChunkyTone.Report, icon = BpmSymbol.Camera)
    BpmChunkyButton("Ahora no", {}, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
    BpmChunkyButton("Comprobar", {}, Modifier.fillMaxWidth(), enabled = false)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { BpmChunkyButton("Siguiente", {}, icon = BpmSymbol.Play); BpmChunkyButton("Listo", {}, tone = BpmChunkyTone.Correct) }
    J1Label("Opciones de respuesta")
    BpmOptionTile("Guardarlo a 5 °C o menos", "1", BpmOptionState.Idle, {}, Modifier.fillMaxWidth())
    BpmOptionTile("Dejarlo a temperatura ambiente una hora", "2", BpmOptionState.Selected, {}, Modifier.fillMaxWidth())
    BpmOptionTile("Cubrir la herida con apósito impermeable y guante, y avisar al jefe de cocina", "3", BpmOptionState.Correct, {}, Modifier.fillMaxWidth())
    BpmOptionTile("Seguir trabajando sin cubrirla", "4", BpmOptionState.Wrong, {}, Modifier.fillMaxWidth())
    J1Label("Fichas con icono")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BpmChoiceTile("Bien", BpmSymbol.Check, BpmPlayTone.Correct, true, {}, Modifier.weight(1f))
        BpmChoiceTile("Con observación", BpmSymbol.Eye, BpmPlayTone.Gold, false, {}, Modifier.weight(1f))
    }
    BpmChoiceTile("No se pudo hacer", BpmSymbol.Close, BpmPlayTone.Streak, false, {}, Modifier.fillMaxWidth(), horizontal = true)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BpmChoiceTile("Temperatura", BpmSymbol.Thermometer, BpmPlayTone.Streak, false, {}, Modifier.weight(1f))
        BpmChoiceTile("Equipo malogrado", BpmSymbol.Wrench, BpmPlayTone.Action, false, {}, Modifier.weight(1f))
    }
    J1Label("Misiones")
    BpmMissionRow("Desinfectar mesas de preparación", "Venció a las 10:00", BpmSymbol.Spray, BpmPlayTone.Streak, done = false, late = true, onCheck = {}, onOpen = {})
    BpmMissionRow("Temperatura de la cámara", "Hasta las 12:00", BpmSymbol.Thermometer, BpmPlayTone.Action, done = false, late = false, onCheck = {}, onOpen = {})
    BpmMissionRow("Lavado de manos al ingresar", "Hecho 8:05", BpmSymbol.Drop, BpmPlayTone.Correct, done = true, late = false, onCheck = {}, onOpen = {})
    BpmMissionRow("Revisar fechas de vencimiento", "Hasta las 15:00", BpmSymbol.Box, BpmPlayTone.Gold, done = false, late = false, onCheck = {}, onOpen = {})
    J1Label("Contadores y avance")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BpmStatPill(BpmSymbol.Flame, "6", BpmPlayTone.Streak); BpmStatPill(BpmSymbol.Bolt, "560", BpmPlayTone.Gold); BpmStatPill(BpmSymbol.Target, "2/3", BpmPlayTone.Action)
    }
    BpmChunkyProgress(0.66f, Modifier.fillMaxWidth())
    BpmChunkyProgress(0.375f, Modifier.fillMaxWidth(), tone = BpmPlayTone.Gold)
    BpmChunkyProgress(0.05f, Modifier.fillMaxWidth())
    BpmChunkyProgress(0f, Modifier.fillMaxWidth())
    BpmStepDots(3, 2)
}

@Composable
private fun J1PartTwo() {
    J1Label("Franja de respuesta")
    BpmFeedbackSheet(true, "¡Muy bien!", "A 5 °C o menos las bacterias casi no se multiplican.", "Continuar", {}, Modifier.fillMaxWidth())
    BpmFeedbackSheet(false, "Casi", "Una herida abierta contamina los alimentos. Se cubre con apósito impermeable y guante.", "Entendido", {}, Modifier.fillMaxWidth())
    J1Label("Celebración")
    BpmCelebration(Res.drawable.mascot_welcome, "¡Lección completa!", "Sigue así, vas muy bien", listOf(
        BpmStatTileSpec("XP", "15", BpmPlayTone.Gold, BpmSymbol.Bolt), BpmStatTileSpec("Racha", "7", BpmPlayTone.Streak, BpmSymbol.Flame),
        BpmStatTileSpec("Aciertos", "90%", BpmPlayTone.Correct, BpmSymbol.Target)))
    J1Label("Celebración con cuatro fichas (fin de lección)")
    BpmCelebration(Res.drawable.mascot_welcome, "¡Lección completa!", "Higiene personal", listOf(
        BpmStatTileSpec("Lecciones", "3/8", BpmPlayTone.Gold, BpmSymbol.Star), BpmStatTileSpec("Racha", "12", BpmPlayTone.Streak, BpmSymbol.Flame),
        BpmStatTileSpec("Aciertos", "100%", BpmPlayTone.Correct, BpmSymbol.Target), BpmStatTileSpec("XP", "+15", BpmPlayTone.Gold, BpmSymbol.Bolt)))
    J1Label("Tomate que habla")
    BpmMascotRow(Res.drawable.mascot_learning, buildAnnotatedString {
        append("¡Hola, Lucía! Te faltan "); withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append("2 tareas") }
        append(" y "); withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append("1 lección") }; append(" para cerrar el día.")
    })
    J1Label("Camino")
    BpmUnitBanner("Módulo 1 · Sección 2", "Higiene y manipulación", BpmPlayColors.unitColors[0], {})
    BpmLearningPath(listOf(
        BpmPathNodeSpec(BpmPathNodeState.Done, {}), BpmPathNodeSpec(BpmPathNodeState.Done, {}), BpmPathNodeSpec(BpmPathNodeState.Current, {}),
        BpmPathNodeSpec(BpmPathNodeState.Locked, {}), BpmPathNodeSpec(BpmPathNodeState.Trophy, {}, enabled = false)))
    BpmUnitBanner("Módulo 2 · Sección 1", "Temperaturas seguras", BpmPlayColors.unitColors[1], {})
    BpmUnitBanner("Módulo 3 · Sección 1", "Limpieza y desinfección", BpmPlayColors.unitColors[2], {})
    J1Label("Navegación (barra inferior)")
    BpmPlayBottomBar(listOf(BpmPlayNavItem("Hoy", BpmSymbol.Home, true) {}, BpmPlayNavItem("Aprender", BpmSymbol.Route, false) {},
        BpmPlayNavItem("Tareas", BpmSymbol.Control, false) {}, BpmPlayNavItem("Perfil", BpmSymbol.Account, false) {}))
}

@Composable
private fun SkeletonGallery() {
    androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
        BpmSkeletonKpis()
        BpmSkeletonList(rows = 3)
        BpmSkeletonCard(lines = 2, action = true)
        BpmSkeletonVideo()
        BpmSkeletonDetail()
    }
}

@Composable
private fun Tasks(operations: OperationsViewModel, workspace: WorkspaceViewModel) {
    Column(Modifier.fillMaxSize()) {
        DailyOperationsContent(F.USER, "WORKER", workspaceState, workspace, operations, Modifier.weight(1f), onIncidents = {})
    }
}

// ---- Prueba -------------------------------------------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
class WorkerScreenshots {
    @Test
    fun renderWorkerScreens() {
        // Sin la propiedad (corrida normal de jvmTest) no hace nada: solo se activa al pedirlo por nombre.
        val dir = System.getProperty("bpm.screenshots.dir")?.let(::File) ?: return
        dir.mkdirs()
        dir.listFiles { f -> f.extension == "png" || f.name == "_report.txt" }?.forEach { it.delete() }
        Dispatchers.setMain(Dispatchers.Unconfined)
        val report = StringBuilder()
        val failures = mutableListOf<String>()
        try {
            for (spec in workerScreens + platformScreens) {
                val sheetImages = mutableListOf<Pair<String, BufferedImage>>()
                for (size in ScreenshotSizes) {
                    try {
                        val base = renderOffscreen(size.widthDp, size.heightDp, spec.build())
                        check(looksRendered(base)) { "captura en blanco" }
                        writePng(base, File(dir, "${spec.name}-${size.label}.png"))
                        var shown = base
                        val tall = if (spec.full) collapseEmptyGap(renderOffscreen(size.widthDp, TALL_HEIGHT_DP, spec.build()), size.sideNavDp) else base
                        val overflows = spec.full && tall.height > base.height + 12
                        if (overflows) {
                            check(looksRendered(tall)) { "captura completa en blanco" }
                            writePng(tall, File(dir, "${spec.name}-${size.label}-full.png"))
                            shown = tall
                        }
                        sheetImages += size.label to shown
                        report.appendLine("OK   ${spec.name}-${size.label} ${base.width}x${base.height}" + if (overflows) " (+full ${tall.width}x${tall.height})" else "")
                    } catch (e: Throwable) {
                        failures += "${spec.name}-${size.label}: $e"
                        report.appendLine("FAIL ${spec.name}-${size.label}: $e")
                        e.printStackTrace()
                    }
                }
                if (sheetImages.isNotEmpty()) writePng(contactSheet(sheetImages), File(dir, "sheet-${spec.name}.png"))
            }
        } finally {
            Dispatchers.resetMain()
            File(dir, "_report.txt").writeText(report.toString())
        }
        assertTrue(failures.isEmpty(), "Capturas con error:\n" + failures.joinToString("\n"))
    }
}
