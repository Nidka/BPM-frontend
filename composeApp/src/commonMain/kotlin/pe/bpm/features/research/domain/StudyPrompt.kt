package pe.bpm.features.research.domain

import kotlin.time.Instant

/**
 * Cuándo y cómo se invita a la encuesta del estudio (cuestionario CAP, instrumento aprobado por el CIEI). El diseño es
 * pretest–postest: **antes** de empezar a usar la app (encuesta inicial) y **al terminar el piloto** (encuesta final, unos
 * [PILOT_DAYS] días después). Toda participación es voluntaria y anónima: la invitación nunca bloquea nada, se puede dejar
 * para después y no se muestra a quien retiró su consentimiento.
 */
const val PILOT_DAYS = 90

enum class StudyStep { CONSENT, PRETEST, POSTTEST }

/** Invitación vigente: qué paso toca ([step]), el título, lo que se pide y el botón. */
data class StudyPrompt(val step: StudyStep, val title: String, val body: String, val action: String)

/**
 * La invitación que toca ahora, o nulo si no hay nada que pedir:
 *  1. sin consentimiento vigente (y sin haberlo retirado): «Antes de empezar» → leer y decidir;
 *  2. con consentimiento y sin encuesta inicial: «Encuesta inicial»;
 *  3. con la inicial hecha y pasados [PILOT_DAYS] días desde ella: «Encuesta final».
 * Sin documento de consentimiento o sin cuestionario publicado no hay nada que responder, así que no se invita.
 */
fun studyPrompt(participation: Participation?, now: String): StudyPrompt? {
    val p = participation ?: return null
    val survey = p.survey ?: return null
    if (p.consentDocument == null) return null
    if (!p.consentValid) {
        if (p.consent?.accepted == false) return null
        return StudyPrompt(StudyStep.CONSENT, "Antes de empezar: encuesta del estudio",
            "Participar es voluntario y anónimo. Toma unos minutos y puedes dejarlo cuando quieras.", "Conocer el estudio")
    }
    val done = p.completedMoments()
    if (SurveyMoment.PRETEST !in done) return StudyPrompt(StudyStep.PRETEST, "Encuesta inicial",
        "${survey.questions.size} preguntas sobre cómo trabajas hoy. Puedes pausar y seguir después.", "Responder")
    if (SurveyMoment.POSTTEST !in done) {
        val first = p.submissions.firstOrNull { it.moment == SurveyMoment.PRETEST && it.instrumentVersionId.equals(survey.instrumentVersionId, ignoreCase = true) }
        val days = first?.let { daysBetween(it.submittedAt, now) }
        if (days != null && days >= PILOT_DAYS) return StudyPrompt(StudyStep.POSTTEST, "Encuesta final",
            "Ya pasó el piloto. Cuéntanos cómo trabajas ahora: son las mismas ${survey.questions.size} preguntas.", "Responder")
    }
    return null
}

private fun daysBetween(from: String, to: String): Long? = runCatching {
    (Instant.parse(to) - Instant.parse(from)).inWholeDays
}.getOrNull()
