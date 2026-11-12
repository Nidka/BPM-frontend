package pe.bpm.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import pe.bpm.features.research.presentation.ResearchScreen
import pe.bpm.features.research.presentation.ResearchViewModel
import pe.bpm.features.research.presentation.SurveyScreen
import pe.bpm.features.research.presentation.SurveyViewModel

/** La raíz entrega persona y empresa autorizadas; al salir se descartan respuestas en memoria. */
@Composable
internal fun SurveyContent(userId: String, organizationId: String, survey: SurveyViewModel, modifier: Modifier = Modifier) {
    LaunchedEffect(userId, organizationId) { survey.open(userId, organizationId) }
    DisposableEffect(survey, userId, organizationId) { onDispose { survey.reset() } }
    SurveyScreen(survey, modifier)
}

/** Sección de investigación; el servidor reautoriza cada operación con la concesión. */
@Composable
internal fun ResearchContent(userId: String, research: ResearchViewModel, modifier: Modifier = Modifier) {
    LaunchedEffect(userId) { research.loadAccess(userId) }
    ResearchScreen(research, modifier)
}
