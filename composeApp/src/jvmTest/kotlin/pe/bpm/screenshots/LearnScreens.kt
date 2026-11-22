package pe.bpm.screenshots

import androidx.compose.runtime.Composable
import pe.bpm.features.training.presentation.AchievementsScreen
import pe.bpm.features.training.presentation.LearningPathsScreen
import pe.bpm.features.workspace.presentation.WorkspaceDestination
import pe.bpm.screenshots.WorkerFixtures as F

/**
 * Mis logros y Rutas de aprendizaje alojadas en el shell como en `App.kt` (`secondaryEditor`), para el loop de diseño.
 * Aprender, curso, lección, estudio y evaluación ya están en [workerScreens].
 */
internal val learnScreens: List<ScreenSpec> = listOf(
    ScreenSpec("logros") {
        val learner = learnerVm()
        val content: @Composable () -> Unit = {
            Shell(WorkspaceDestination.Learn, secondaryTitle = "Mis logros", secondaryEditor = true, secondaryContent = { AchievementsScreen(learner) })
        }
        content
    },
    ScreenSpec("rutas") {
        val learner = learnerVm().also { it.loadPaths() }
        val content: @Composable () -> Unit = {
            Shell(WorkspaceDestination.Learn, secondaryTitle = "Rutas de aprendizaje", secondaryEditor = true,
                secondaryContent = { LearningPathsScreen(learner, onOpenCourse = {}) })
        }
        content
    },
    ScreenSpec("ruta-detalle") {
        val learner = learnerVm().also { it.loadPaths(); it.openPath(F.paths.first().id) }
        val content: @Composable () -> Unit = {
            Shell(WorkspaceDestination.Learn, secondaryTitle = "Rutas de aprendizaje", secondaryEditor = true,
                secondaryContent = { LearningPathsScreen(learner, onOpenCourse = {}) })
        }
        content
    },
)
