package pe.bpm.features.research.presentation

// patrón: modo enfoque de juego (BpmPlayStage), como la lección y el reporte; el shell no pone cabecera.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.core.offline.LocalOnline
import pe.bpm.core.offline.LocalPendingMarks
import pe.bpm.core.offline.PendingOperationsNotice
import pe.bpm.features.research.domain.*
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_done
import pe.bpm.resources.mascot_learning

/**
 * Encuesta del estudio (cuestionario CAP: conocimientos, actitudes y prácticas) para cualquier miembro, con el estilo de
 * juego y en modo enfoque: resumen → consentimiento informado → preguntas una por pantalla (con progreso, «Escuchar» y
 * borrador que se retoma aunque se cierre la app) → gracias. Es voluntaria y anónima: se participa con un código, nunca
 * se muestran puntajes ni respuestas correctas y el consentimiento se puede retirar cuando se quiera.
 * [onClose] sale de la encuesta (las respuestas a medias quedan guardadas en el dispositivo).
 */
@Composable
fun SurveyScreen(viewModel: SurveyViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val participation = state.participation
    if (participation == null) {
        BpmPlayStage(modifier, onClose = onClose, closeDescription = "Salir de la encuesta", centered = true) {
            if (state.loading) BpmSkeletonCard(lines = 3, action = true, label = "Cargando la encuesta")
            state.failure?.let { failure ->
                BpmMascotRow(Res.drawable.mascot_learning, "No pudimos abrir la encuesta", imageWidth = 72.dp)
                Text(researchFailureMessage(failure), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                BpmChunkyButton("Volver a intentar", viewModel::refresh, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            }
        }
        return
    }
    when (state.screen) {
        SurveyPane.OVERVIEW -> Overview(state, participation, viewModel, onClose, modifier)
        SurveyPane.CONSENT -> ConsentPanel(state, participation, viewModel, modifier)
        SurveyPane.QUESTIONS -> QuestionPanel(state, viewModel, modifier)
        SurveyPane.DONE -> DonePanel(state, viewModel, modifier)
    }
}

@Composable
private fun Overview(state: SurveyState, participation: Participation, viewModel: SurveyViewModel, onClose: () -> Unit, modifier: Modifier) {
    val submissionPending = LocalPendingMarks.current.forResource("research").isNotEmpty()
    BpmPlayStage(modifier, onClose = onClose, closeDescription = "Salir de la encuesta", scrollKey = state.notice) {
        BpmMascotRow(Res.drawable.mascot_learning, "Encuesta del estudio. Es voluntaria y anónima: usamos un código, no tu nombre, y no mostramos puntajes.", imageWidth = 72.dp)
        BpmListenButton("Encuesta del estudio. Tu participación es voluntaria. Respondemos de forma anónima: usamos un código en lugar de tu nombre y no mostramos puntajes individuales.")
        state.failure?.let { Text(researchFailureMessage(it), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        state.notice?.let { notice ->
            BpmPlayNotice(notice)
            BpmChunkyButton("Entendido", viewModel::dismissNotice, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        }
        PendingOperationsNotice("research")
        val document = participation.consentDocument
        when {
            document == null -> BpmPlayEmpty(Res.drawable.mascot_done, "Todavía no hay una encuesta", "El equipo de investigación aún no publicó el consentimiento informado. Vuelve más tarde.")
            !participation.consentValid -> BpmPlayCard {
                BpmPlayChip("Primer paso", BpmPlayTone.Gold)
                Text(if (participation.consent?.accepted == false) "Retiraste tu participación" else "Consentimiento informado", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
                Text(document.title, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink)
                Text(if (participation.consent?.accepted == false) "Si cambias de opinión, puedes leer el consentimiento y aceptarlo de nuevo."
                else "Lee el documento y decide si quieres participar. Podrás retirarte cuando quieras.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
                BpmChunkyButton("Leer y decidir", viewModel::showConsent, Modifier.fillMaxWidth())
            }
            participation.survey == null -> BpmPlayEmpty(Res.drawable.mascot_done, "Encuesta no disponible", "Aceptaste participar, pero todavía no hay un cuestionario publicado. Te avisaremos cuando esté listo.")
            else -> {
                participation.participant?.let { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { BpmPlayChip("Tu código · ${it.code}", BpmPlayTone.Action, uppercase = false) } }
                val completed = participation.completedMoments()
                SurveyMoment.entries.forEach { moment ->
                    val record = participation.submissions.firstOrNull { it.moment == moment && it.instrumentVersionId.equals(participation.survey.instrumentVersionId, ignoreCase = true) }
                    BpmPlayCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BpmPlayChip(if (moment == SurveyMoment.PRETEST) "Al empezar" else "Al terminar", BpmPlayTone.Gold)
                            if (moment in completed) BpmPlayChip("Completada", BpmPlayTone.Correct)
                        }
                        Text(moment.label(), style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
                        if (moment in completed) {
                            Text("Completada" + (record?.let { " el ${it.submittedAt.take(10)}" } ?: "") + ". Gracias por responder; no necesitas hacer nada más.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
                        } else {
                            val resuming = state.moment == moment && state.answers.isNotEmpty()
                            Text("${participation.survey.questions.size} preguntas. Toma unos minutos; puedes pausar y seguir después, tus respuestas quedan guardadas aquí.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
                            if (moment == SurveyMoment.POSTTEST) Text("Se responde al terminar el piloto (unos ${PILOT_DAYS / 30} meses después de la inicial).", style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
                            BpmChunkyButton(if (resuming) "Continuar" else "Responder", { viewModel.start(moment) }, Modifier.fillMaxWidth(), enabled = !submissionPending)
                            if (submissionPending) Text("Tienes una encuesta pendiente de enviar. Se confirmará al recuperar la conexión.", style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
                            else if (!LocalOnline.current) Text("Puedes responder sin conexión: tus respuestas se guardan y se envían al conectarte.", style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
                        }
                    }
                }
                BpmChunkyButton("Ver o retirar mi consentimiento", viewModel::showConsent, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            }
        }
    }
}

@Composable
private fun ConsentPanel(state: SurveyState, participation: Participation, viewModel: SurveyViewModel, modifier: Modifier) {
    val document = participation.consentDocument
    val online = LocalOnline.current
    BpmPlayStage(
        modifier, onClose = { viewModel.back() }, closeDescription = "Volver a la encuesta", scrollKey = state.confirmingRevoke,
        footer = {
            if (document != null) when {
                !participation.consentValid -> {
                    BpmChunkyButton(if (state.consentBusy) "Guardando…" else "Acepto participar", viewModel::acceptConsent, Modifier.fillMaxWidth(), enabled = !state.consentBusy && online)
                    BpmChunkyButton("Ahora no", { viewModel.back() }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
                }
                state.confirmingRevoke -> {
                    BpmChunkyButton("Sí, retirar mi consentimiento", viewModel::revokeConsent, Modifier.fillMaxWidth(), tone = BpmChunkyTone.Report, enabled = !state.consentBusy && online)
                    BpmChunkyButton("Mantener mi participación", viewModel::cancelRevoke, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
                }
                else -> {
                    BpmChunkyButton("Retirar mi consentimiento", viewModel::askRevoke, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = !state.consentBusy && online)
                    BpmChunkyButton("Volver", { viewModel.back() }, Modifier.fillMaxWidth())
                }
            }
        },
    ) {
        if (document == null) { BpmPlayEmpty(Res.drawable.mascot_done, "Consentimiento no disponible", "No hay un documento publicado por ahora."); return@BpmPlayStage }
        Text(document.title, style = BpmPlayType.h1.copy(fontSize = 24.sp, lineHeight = 27.sp), color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BpmPlayChip("Versión ${document.version}", BpmPlayTone.Action, uppercase = false)
            BpmPlayChip("Voluntaria", BpmPlayTone.Gold)
        }
        Text("Investigador responsable: ${document.responsibleInvestigator}", style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
        BpmListenButton(document.title + ". " + document.text)
        BpmPlayCard { Text(document.text, style = BpmPlayType.text, color = BpmPlayColors.ink) }
        if (!online) Text(CONSENT_ONLINE_EXPLANATION, style = BpmPlayType.sub, color = BpmPlayColors.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        state.failure?.let { Text(researchFailureMessage(it), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        when {
            !participation.consentValid -> Text("Al aceptar confirmas que leíste el documento y que participas voluntariamente. Puedes retirarte cuando quieras.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
            state.confirmingRevoke -> BpmPlayCard {
                Text("¿Retirar tu consentimiento?", style = BpmPlayType.h2, color = BpmPlayColors.ink)
                Text("Dejarás de participar y tus respuestas no se usarán en los resultados ni en las exportaciones.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
            }
            else -> Text("Aceptaste este documento" + (participation.consent?.let { " el ${it.recordedAt.take(10)}" } ?: "") + ".", style = BpmPlayType.sub, color = BpmPlayColors.correct.shadow)
        }
    }
}

private const val CONSENT_ONLINE_EXPLANATION =
    "El consentimiento informado es un acto legal que debe quedar registrado en el servidor, por eso solo puede aceptarse o retirarse con conexión. " +
        "Cuando ya aceptaste, puedes responder la encuesta sin conexión: tus respuestas se guardan en este dispositivo y se envían al conectarte."

private fun dimensionTitle(dimension: Dimension) = when (dimension) {
    Dimension.KNOWLEDGE -> "Conocimientos · elige una respuesta"
    Dimension.ATTITUDE -> "Actitudes · qué tan de acuerdo estás"
    Dimension.PRACTICE -> "Prácticas · con qué frecuencia lo haces"
}

@Composable
private fun QuestionPanel(state: SurveyState, viewModel: SurveyViewModel, modifier: Modifier) {
    val survey = state.survey ?: return
    val question = state.current ?: return
    val total = survey.questions.size
    val answered = state.answeredCount
    val selected = SurveyRules.optionIndex(question, state.answers[question.id])
    val busy = state.submitting || state.pending != null
    val last = state.index == total - 1
    val send = state.pending != null && state.failure != null
    BpmPlayStage(
        modifier.bpmPlayKeys(question.options.size, { if (!busy) viewModel.answer(question.id, SurveyRules.valueForOption(question, it)) }, { if (selected != null) if (last) viewModel.submit() else viewModel.next() }, focusKey = state.index),
        progress = answered.toFloat() / total, counter = "${state.index + 1}/$total", closeDescription = "Salir de la encuesta y guardar mis respuestas",
        onClose = { viewModel.back() }, progressDescription = "Pregunta ${state.index + 1} de $total, $answered respondidas", scrollKey = state.index,
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.index > 0) BpmChunkyButton("Anterior", viewModel::previous, Modifier.weight(1f), tone = BpmChunkyTone.White, enabled = !state.submitting)
                if (!last) BpmChunkyButton("Siguiente", viewModel::next, Modifier.weight(1f), enabled = selected != null && !state.submitting)
                else BpmChunkyButton(if (state.submitting) "Enviando…" else if (send) "Reintentar envío" else "Enviar respuestas", viewModel::submit, Modifier.weight(1f), enabled = !state.submitting)
            }
        },
    ) {
        state.notice?.let { Text(it, style = BpmPlayType.sub, color = BpmPlayColors.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        Text(dimensionTitle(question.dimension), style = BpmPlayType.label, color = BpmPlayColors.action.face)
        Text(question.text, style = BpmPlayType.h1.copy(fontSize = 24.sp, lineHeight = 27.sp), color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
        BpmListenButton(question.text + ". " + question.options.mapIndexed { i, o -> "Opción ${i + 1}: $o." }.joinToString(" "), label = "Escuchar la pregunta")
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            question.options.forEachIndexed { index, option ->
                BpmOptionTile(option, (index + 1).toString(), if (selected == index) BpmOptionState.Selected else BpmOptionState.Idle,
                    { viewModel.answer(question.id, SurveyRules.valueForOption(question, index)) }, Modifier.fillMaxWidth(), enabled = !busy)
            }
        }
        if (state.showMissing && !state.complete) {
            Text("Faltan ${total - answered} preguntas por responder antes de enviar.", style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            BpmChunkyButton("Ir a la primera sin responder", viewModel::goToFirstMissing, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        }
        state.failure?.let { Text(researchFailureMessage(it), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (state.pending != null && state.failure != null) Text("Tus respuestas están guardadas. Al reintentar no se duplicará el envío.", style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
        if (state.index < total - 1 && state.complete) BpmChunkyButton("Ya respondí todo: enviar", viewModel::submit, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = !state.submitting)
    }
}

@Composable
private fun DonePanel(state: SurveyState, viewModel: SurveyViewModel, modifier: Modifier) {
    val label = state.completedMoment?.label()?.lowercase() ?: "encuesta"
    BpmPlayStage(modifier.bpmPlayKeys(0, {}, { viewModel.dismissDone() }, focusKey = "gracias"), centered = true,
        footer = { BpmChunkyButton("Listo", viewModel::dismissDone, Modifier.fillMaxWidth()) }) {
        BpmCelebration(Res.drawable.mascot_done, "¡Gracias por participar!", null, emptyList(), titleColor = BpmPlayColors.correct.shadow, middle = {
            BpmPlayCard {
                Text(if (state.completedQueued) "Guardamos tu $label en este dispositivo. Se enviará y confirmará al recuperar la conexión; no necesitas responder otra vez."
                else "Recibimos tu $label. Tus respuestas se guardan de forma anónima y no se muestran puntajes individuales.",
                    style = BpmPlayType.sub, color = BpmPlayColors.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        })
    }
}
