package pe.bpm.features.research.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.core.offline.LocalOnline
import pe.bpm.core.offline.LocalPendingMarks
import pe.bpm.core.offline.OfflineRequiredNotice
import pe.bpm.core.offline.PendingOperationsNotice
import pe.bpm.features.research.domain.*

/** Encuesta del estudio para cualquier miembro: consentimiento y cuestionario CAP, sin puntajes ni respuestas correctas. */
@Composable
fun SurveyScreen(viewModel: SurveyViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    LaunchedEffect(state.screen, state.index) { scroll.scrollTo(0) }
    Column(modifier.fillMaxSize().widthIn(max = BpmPatternRules.readingWidth).verticalScroll(scroll).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        if (state.loading && state.participation == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.failure?.let { failure ->
            BpmCard(Modifier.fillMaxWidth()) {
                Text(researchFailureMessage(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.participation == null) BpmSecondaryButton(onClick = viewModel::refresh) { Text("Volver a intentar") }
            }
        }
        state.notice?.let { notice ->
            BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                Text(notice, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                BpmTextButton(onClick = viewModel::dismissNotice) { Text("Entendido") }
            }
        }
        val participation = state.participation ?: return@Column
        when (state.screen) {
            SurveyPane.OVERVIEW -> Overview(state, participation, viewModel)
            SurveyPane.CONSENT -> ConsentPanel(state, participation, viewModel)
            SurveyPane.QUESTIONS -> QuestionPanel(state, viewModel)
            SurveyPane.DONE -> DonePanel(state, viewModel)
        }
    }
}

@Composable
private fun Overview(state: SurveyState, participation: Participation, viewModel: SurveyViewModel) {
    Text("Encuesta del estudio", style = MaterialTheme.typography.headlineMedium)
    Text("Tu participación es voluntaria. Respondemos de forma anónima: usamos un código en lugar de tu nombre y no mostramos puntajes individuales.")
    val document = participation.consentDocument
    val submissionPending = LocalPendingMarks.current.forResource("research").isNotEmpty()
    PendingOperationsNotice("research")
    when {
        document == null -> EmptyState("Todavía no hay una encuesta", "El equipo de investigación aún no publicó el consentimiento informado. Vuelve más tarde.")
        !participation.consentValid -> {
            BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                Eyebrow("PRIMER PASO")
                Text(if (participation.consent?.accepted == false) "Retiraste tu participación" else "Consentimiento informado", style = MaterialTheme.typography.titleLarge)
                Text(document.title)
                Text(if (participation.consent?.accepted == false) "Si cambias de opinión, puedes leer el consentimiento y aceptarlo de nuevo."
                else "Lee el documento completo y decide si quieres participar. Podrás retirarte cuando quieras.")
                RaisedButton("Leer el consentimiento", viewModel::showConsent, Modifier.bpmPrimaryButtonWidth())
                if (!LocalOnline.current) Text(CONSENT_ONLINE_EXPLANATION, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        participation.survey == null -> EmptyState("Encuesta no disponible", "Aceptaste participar, pero todavía no hay un cuestionario publicado. Te avisaremos cuando esté listo.")
        else -> {
            participation.participant?.let { Text("Tu código de participante: ${it.code}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val completed = participation.completedMoments()
            BpmCardGrid(SurveyMoment.entries, minCardWidth = 320.dp, maxColumns = 2) { moment, cardModifier -> // patrón: Antes y Después son pasos ordenados, no un catálogo
                BpmCard(cardModifier) {
                    Eyebrow(if (moment == SurveyMoment.PRETEST) "AL EMPEZAR" else "AL TERMINAR")
                    Text(moment.label(), style = MaterialTheme.typography.titleMedium)
                    val record = participation.submissions.firstOrNull { it.moment == moment && it.instrumentVersionId.equals(participation.survey.instrumentVersionId, ignoreCase = true) }
                    if (moment in completed) {
                        Text("Completada" + (record?.let { " el ${it.submittedAt.take(10)}" } ?: ""), color = MaterialTheme.colorScheme.primary, style = BpmType.bodyStrong)
                        Text("Gracias por responder. No necesitas hacer nada más en este momento.")
                    } else {
                        val resuming = state.moment == moment && state.answers.isNotEmpty()
                        Text("${participation.survey.questions.size} preguntas. Toma unos minutos y puedes pausar y volver mientras no cierres la pantalla.")
                        RaisedButton(if (resuming) "Continuar" else "Responder", { viewModel.start(moment) }, Modifier.fillMaxWidth(), enabled = !submissionPending)
                        if (submissionPending) Text("Tienes una encuesta pendiente de enviar. Se confirmará al recuperar la conexión.", style = MaterialTheme.typography.bodySmall)
                        else if (!LocalOnline.current) Text("Puedes responder sin conexión: tus respuestas se guardan en este dispositivo y se envían al conectarte.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            BpmTextButton(onClick = viewModel::showConsent) { Text("Ver o retirar mi consentimiento") }
        }
    }
}

@Composable
private fun ConsentPanel(state: SurveyState, participation: Participation, viewModel: SurveyViewModel) {
    val document = participation.consentDocument
    if (document == null) { EmptyState("Consentimiento no disponible", "No hay un documento publicado por ahora."); return }
    Text(document.title, style = MaterialTheme.typography.headlineMedium)
    Text("Versión ${document.version} · Investigador responsable: ${document.responsibleInvestigator}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    BpmCard(Modifier.fillMaxWidth()) { Text(document.text) }
    if (state.consentBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    val online = LocalOnline.current
    if (!online) Text(CONSENT_ONLINE_EXPLANATION, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (!participation.consentValid) {
        Text("Al aceptar confirmas que leíste el documento y que participas voluntariamente.")
        RaisedButton("Acepto participar", viewModel::acceptConsent, Modifier.bpmPrimaryButtonWidth(), enabled = !state.consentBusy && online)
        OfflineRequiredNotice()
    } else if (state.confirmingRevoke) {
        BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.errorContainer) {
            Text("¿Retirar tu consentimiento?", style = MaterialTheme.typography.titleMedium)
            Text("Dejarás de participar y tus respuestas no se usarán en los resultados ni en las exportaciones.")
            BpmSecondaryButton(onClick = viewModel::revokeConsent, enabled = !state.consentBusy && online) { Text("Sí, retirar mi consentimiento") }
            BpmTextButton(onClick = viewModel::cancelRevoke) { Text("Mantener mi participación") }
        }
    } else {
        Text("Aceptaste este documento" + (participation.consent?.let { " el ${it.recordedAt.take(10)}" } ?: "") + ".", color = MaterialTheme.colorScheme.primary)
        BpmSecondaryButton(onClick = viewModel::askRevoke, enabled = !state.consentBusy && online) { Text("Retirar mi consentimiento") }
    }
}

private const val CONSENT_ONLINE_EXPLANATION =
    "El consentimiento informado es un acto legal que debe quedar registrado en el servidor, por eso solo puede aceptarse o retirarse con conexión. " +
        "Cuando ya aceptaste, puedes responder la encuesta sin conexión: tus respuestas se guardan en este dispositivo y se envían al conectarte."

private fun dimensionTitle(dimension: Dimension) = when (dimension) {
    Dimension.KNOWLEDGE -> "Conocimientos"
    Dimension.ATTITUDE -> "Actitudes: indica qué tan de acuerdo estás"
    Dimension.PRACTICE -> "Prácticas: indica con qué frecuencia lo haces"
}

@Composable
private fun QuestionPanel(state: SurveyState, viewModel: SurveyViewModel) {
    val survey = state.survey ?: return
    val question = state.current ?: return
    val total = survey.questions.size
    Text(state.moment?.label() ?: "Encuesta", style = MaterialTheme.typography.headlineSmall)
    val answered = state.answeredCount
    Text("Pregunta ${state.index + 1} de $total · $answered respondidas", style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { contentDescription = "Pregunta ${state.index + 1} de $total, $answered respondidas" })
    LinearProgressIndicator(progress = { answered.toFloat() / total }, modifier = Modifier.fillMaxWidth())
    BpmCard(Modifier.fillMaxWidth()) {
        Eyebrow(dimensionTitle(question.dimension).uppercase())
        Text(question.text, style = MaterialTheme.typography.titleMedium)
        val selected = SurveyRules.optionIndex(question, state.answers[question.id])
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            question.options.forEachIndexed { index, option ->
                BpmChoiceRow(option, selected == index, enabled = !state.submitting && state.pending == null) {
                    viewModel.answer(question.id, SurveyRules.valueForOption(question, index))
                }
            }
        }
    }
    if (state.showMissing && !state.complete) {
        Text("Faltan ${total - answered} preguntas por responder antes de enviar.", color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        BpmTextButton(onClick = viewModel::goToFirstMissing) { Text("Ir a la primera pregunta sin responder") }
    }
    if (state.submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BpmSecondaryButton(onClick = viewModel::previous, enabled = state.index > 0 && !state.submitting) { Text("Anterior") }
        if (state.index < total - 1) RaisedButton("Siguiente", viewModel::next, Modifier.bpmPrimaryButtonWidth(), enabled = !state.submitting)
        else RaisedButton(if (state.pending != null && state.failure != null) "Reintentar envío" else "Enviar respuestas", viewModel::submit, Modifier.bpmPrimaryButtonWidth(), enabled = !state.submitting)
    }
    if (state.index < total - 1 && state.complete) BpmTextButton(onClick = viewModel::submit, enabled = !state.submitting) { Text("Ya respondí todo: enviar") }
    if (state.pending != null && state.failure != null) Text("Tus respuestas están guardadas en esta pantalla. Al reintentar no se duplicará el envío.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DonePanel(state: SurveyState, viewModel: SurveyViewModel) {
    BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
        Eyebrow("LISTO")
        Text("¡Gracias por participar!", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.completedQueued) Text("Guardamos tu ${state.completedMoment?.label()?.lowercase() ?: "encuesta"} en este dispositivo. Se enviará y confirmará al recuperar la conexión; no necesitas responder otra vez.")
        else Text("Recibimos tu ${state.completedMoment?.label()?.lowercase() ?: "encuesta"}. Tus respuestas se guardan de forma anónima y no se muestran puntajes individuales.")
        RaisedButton("Volver a la encuesta", viewModel::dismissDone, Modifier.bpmPrimaryButtonWidth())
    }
}
