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
import pe.bpm.app.WorkerTodayContent
import pe.bpm.core.designsystem.BpmPattern
import pe.bpm.core.storage.TestDraftStorage
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
internal class ScreenSpec(val name: String, val build: () -> @Composable () -> Unit)

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
    return TrainingLearnerViewModel(LoadMyAssignments(repo), LoadMyAchievements(repo), LoadLearningPaths(repo))
        .also { it.open(F.USER, F.ORG, member = true); it.loadAchievements() }
}

private fun operationsVm(resource: OperationsResource? = null): OperationsViewModel =
    OperationsViewModel(
        ManageOperations(F.OperationsFixtureRepository(), F.NoopTransport),
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
private fun courseScreen(navigatorOpen: Boolean = false, drive: (TrainingViewModel, FakeTrainingRepository) -> Unit): @Composable () -> Unit {
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
      androidx.compose.runtime.CompositionLocalProvider(LocalInitialNavigatorOpen provides navigatorOpen) {
        Shell(
            WorkspaceDestination.Learn,
            secondaryContent = { TrainingCourseContent(F.USER, F.ORG, F.COURSE, true, vm, onOpenCourse = {}) },
            secondaryEditor = true,
            // Igual que App.kt: la vista general es un panel; lo demás, un flujo por pasos.
            secondaryPattern = if (state.screen == TrainingPane.OVERVIEW) BpmPattern.Panel else BpmPattern.StepFlow,
            secondaryWideOnExpanded = wantsWidePane(state),
            // Igual que App.kt: modo enfoque fuera de la vista general (sin navegación; cabecera con X y nombre del curso).
            focusMode = isFocusMode(state), focusTitle = state.course?.course?.title,
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
        focusMode = focusMode, focusTitle = focusTitle,
        onOrganizationSearch = {}, onOrganizationNext = {}, onOrganizationPrevious = {}, onOrganizationRetry = {},
        onPageSearch = { _, _ -> }, onPageNext = {}, onPagePrevious = {}, onPageRetry = {},
    )
}

// ---- Las pantallas del personal ---------------------------------------------------------------------

internal val workerScreens: List<ScreenSpec> = listOf(
    ScreenSpec("u1-componentes") { { U1Components() } },
    ScreenSpec("hoy") {
        val learner = learnerVm()
        val operations = operationsVm()
        val content: @Composable () -> Unit = {
            Shell(WorkspaceDestination.Home, homeContent = {
                WorkerTodayContent(learner, F.USER, F.ORG, operations, F.workspace.locations,
                    onOpenCourse = {}, onLearn = {}, onTasks = {}, onReport = {}, onAchievements = {})
            })
        }
        content
    },
    ScreenSpec("tareas-por-hacer") {
        val operations = operationsVm(OperationsResource.PLANS)
        val workspace = workspaceVm()
        val content: @Composable () -> Unit = { Shell(WorkspaceDestination.Tasks, taskContent = { Tasks(operations, workspace) }) }
        content
    },
    ScreenSpec("tareas-hechas") {
        val operations = operationsVm(OperationsResource.EXECUTIONS)
        val workspace = workspaceVm()
        val content: @Composable () -> Unit = { Shell(WorkspaceDestination.Tasks, taskContent = { Tasks(operations, workspace) }) }
        content
    },
    ScreenSpec("aprender") {
        val learner = learnerVm()
        val content: @Composable () -> Unit = {
            Shell(WorkspaceDestination.Learn, learnHeader = {
                TrainingLearnerHeader(F.USER, F.ORG, true, learner, onOpenCourse = {}, onAchievements = {}, onPaths = {})
            })
        }
        content
    },
    ScreenSpec("curso") { courseScreen { _, _ -> } },
    ScreenSpec("leccion") { courseScreen { vm, _ -> vm.selectLesson(F.lessonIds[2]) } },
    ScreenSpec("estudio") {
        courseScreen { vm, _ ->
            vm.startStudy(F.lessonIds[2])
            // Respuesta equivocada: muestra la retroalimentación con la explicación.
            vm.answerStudy(1)
        }
    },
    ScreenSpec("evaluacion") {
        courseScreen { vm, _ ->
            vm.startEvaluation(AttemptType.FINAL)
            vm.answerEvaluation(F.questionIds[0], 2)
            vm.answerEvaluation(F.questionIds[1], 1)
            vm.answerEvaluation(F.questionIds[4], 0)
            vm.goToQuestion(2) // pregunta 3 de 8
        }
    },
    // La hoja inferior con el navegador de preguntas abierta (clase compacta y media; en expandida vive en el panel).
    ScreenSpec("evaluacion-hoja") {
        courseScreen(navigatorOpen = true) { vm, _ ->
            vm.startEvaluation(AttemptType.FINAL)
            vm.answerEvaluation(F.questionIds[0], 2)
            vm.answerEvaluation(F.questionIds[1], 1)
            vm.answerEvaluation(F.questionIds[4], 0)
            vm.goToQuestion(2)
        }
    },
    ScreenSpec("revision") {
        courseScreen { vm, _ ->
            vm.startEvaluation(AttemptType.FINAL)
            F.questionIds.forEachIndexed { i, id -> if (i != 5) vm.answerEvaluation(id, F.correctOptions[i]) } // la 6 queda sin responder
            vm.openReview()
        }
    },
    ScreenSpec("resultado") {
        courseScreen { vm, _ ->
            vm.startEvaluation(AttemptType.FINAL)
            F.questionIds.forEachIndexed { i, id -> vm.answerEvaluation(id, if (i == 5) 0 else F.correctOptions[i]) } // 7 de 8
            vm.submitEvaluation()
        }
    },
)

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
            for (spec in workerScreens) {
                val sheetImages = mutableListOf<Pair<String, BufferedImage>>()
                for (size in ScreenshotSizes) {
                    try {
                        val base = renderOffscreen(size.widthDp, size.heightDp, spec.build())
                        check(looksRendered(base)) { "captura en blanco" }
                        writePng(base, File(dir, "${spec.name}-${size.label}.png"))
                        var shown = base
                        val tall = collapseEmptyGap(renderOffscreen(size.widthDp, TALL_HEIGHT_DP, spec.build()), size.sideNavDp)
                        val overflows = tall.height > base.height + 12
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
