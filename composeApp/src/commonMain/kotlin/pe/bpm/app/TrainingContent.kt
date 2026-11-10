package pe.bpm.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import pe.bpm.features.training.presentation.TrainingAdminScreen
import pe.bpm.features.training.presentation.TrainingAuthoringViewModel
import pe.bpm.features.training.presentation.TrainingScreen
import pe.bpm.features.training.presentation.TrainingViewModel

/**
 * La raíz entrega el contexto autorizado (persona, organización y curso) y conserva el estado
 * solo mientras la pantalla está abierta: al salir se descartan lecciones, URL de video y respuestas.
 */
@Composable
internal fun TrainingCourseContent(userId: String, organizationId: String, courseId: String, canPersist: Boolean, training: TrainingViewModel, modifier: Modifier = Modifier) {
    LaunchedEffect(userId, organizationId, courseId, canPersist) { training.open(userId, organizationId, courseId, canPersist) }
    DisposableEffect(training, userId, organizationId, courseId) { onDispose { training.reset() } }
    TrainingScreen(training, modifier)
}

/** Autoría de Academia; el backend vuelve a validar el rol de cada operación. */
@Composable
internal fun TrainingAdminContent(userId: String, organizationId: String, canManage: Boolean, authoring: TrainingAuthoringViewModel, modifier: Modifier = Modifier) {
    LaunchedEffect(userId, organizationId, canManage) { authoring.open(userId, organizationId, canManage) }
    DisposableEffect(authoring, userId, organizationId) { onDispose { authoring.reset() } }
    TrainingAdminScreen(authoring, modifier)
}
