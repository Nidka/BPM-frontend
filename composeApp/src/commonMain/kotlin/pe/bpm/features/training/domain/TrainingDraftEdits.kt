package pe.bpm.features.training.domain

/** Ediciones puras del borrador: la interfaz las compone y las pruebas las verifican sin Compose. */
object TrainingDraftEdits {
    fun addLesson(draft: TrainingDraft, id: String): TrainingDraft {
        if (draft.lessons.size >= TrainingLimits.MAX_LESSONS) return draft
        return draft.copy(lessons = draft.lessons + DraftLesson(id, "", "", draft.lessons.size + 1, null)).renumbered()
    }

    fun updateLesson(draft: TrainingDraft, id: String, title: String? = null, content: String? = null): TrainingDraft =
        draft.copy(lessons = draft.lessons.map { if (it.id == id) it.copy(title = title ?: it.title, content = content ?: it.content) else it })

    /** Reemplaza los subtítulos WebVTT de la lección (RF-45); vacío los quita. */
    fun setCaptions(draft: TrainingDraft, id: String, text: String): TrainingDraft =
        draft.copy(lessons = draft.lessons.map { if (it.id == id) it.copy(captionsVtt = text) else it })

    /** Agrega una idea vacía a «Lo esencial» de la lección (tope [TrainingLimits.KEY_POINTS_MAX]). */
    fun addKeyPoint(draft: TrainingDraft, id: String): TrainingDraft =
        draft.copy(lessons = draft.lessons.map { if (it.id == id && it.keyPoints.size < TrainingLimits.KEY_POINTS_MAX) it.copy(keyPoints = it.keyPoints + "") else it })

    /** Reemplaza el texto de la idea [index]; se recorta a una sola línea (saltos de línea se vuelven espacios). */
    fun setKeyPoint(draft: TrainingDraft, id: String, index: Int, text: String): TrainingDraft =
        draft.copy(lessons = draft.lessons.map { lesson ->
            if (lesson.id == id && index in lesson.keyPoints.indices)
                lesson.copy(keyPoints = lesson.keyPoints.mapIndexed { i, old -> if (i == index) text.replace(Regex("[\\r\\n]+"), " ") else old })
            else lesson
        })

    fun removeKeyPoint(draft: TrainingDraft, id: String, index: Int): TrainingDraft =
        draft.copy(lessons = draft.lessons.map { lesson ->
            if (lesson.id == id && index in lesson.keyPoints.indices) lesson.copy(keyPoints = lesson.keyPoints.filterIndexed { i, _ -> i != index }) else lesson
        })

    /** Adjuntar un video privado retira el de YouTube: una lección tiene como máximo uno. */
    fun setLessonVideo(draft: TrainingDraft, id: String, mediaId: String?): TrainingDraft =
        draft.copy(lessons = draft.lessons.map { if (it.id == id) it.copy(videoMediaId = mediaId, externalVideoId = if (mediaId != null) null else it.externalVideoId) else it })

    /** Fija el video de YouTube (o lo quita con `null`) y retira el video privado. */
    fun setExternalVideo(draft: TrainingDraft, id: String, videoId: String?): TrainingDraft =
        draft.copy(lessons = draft.lessons.map { if (it.id == id) it.copy(externalVideoId = videoId, videoMediaId = if (videoId != null) null else it.videoMediaId) else it })

    /** Al quitar una lección sus preguntas siguen existiendo pero quedan sin lección. */
    fun removeLesson(draft: TrainingDraft, id: String): TrainingDraft = draft.copy(
        lessons = draft.lessons.filterNot { it.id == id },
        questions = draft.questions.map { if (it.lessonId == id) it.copy(lessonId = null) else it },
    ).renumbered()

    fun moveLesson(draft: TrainingDraft, id: String, step: Int): TrainingDraft {
        val from = draft.lessons.indexOfFirst { it.id == id }
        val to = from + step
        if (from < 0 || to !in draft.lessons.indices) return draft
        val list = draft.lessons.toMutableList().apply { add(to, removeAt(from)) }
        return draft.copy(lessons = list).renumbered()
    }

    fun addQuestion(draft: TrainingDraft, id: String, lessonId: String?): TrainingDraft {
        if (draft.questions.size >= TrainingLimits.MAX_QUESTIONS) return draft
        return draft.copy(questions = draft.questions + DraftQuestion(id, lessonId, "", listOf("", ""), 0, "", draft.questions.size + 1)).renumbered()
    }

    /** Los ítems del banco oficial son de solo lectura: no se quitan desde el borrador. */
    fun removeQuestion(draft: TrainingDraft, id: String): TrainingDraft = draft.copy(questions = draft.questions.filterNot { it.id == id && !it.official }).renumbered()

    fun updateQuestion(draft: TrainingDraft, id: String, text: String? = null, explanation: String? = null, lessonId: String? = null, clearLesson: Boolean = false): TrainingDraft =
        draft.mapQuestion(id) {
            it.copy(questionText = text ?: it.questionText, explanation = explanation ?: it.explanation,
                lessonId = if (clearLesson) null else lessonId ?: it.lessonId)
        }

    fun setOption(draft: TrainingDraft, id: String, index: Int, text: String): TrainingDraft =
        draft.mapQuestion(id) { q -> if (index in q.options.indices) q.copy(options = q.options.toMutableList().also { it[index] = text }) else q }

    fun addOption(draft: TrainingDraft, id: String): TrainingDraft =
        draft.mapQuestion(id) { q -> if (q.options.size < TrainingLimits.MAX_OPTIONS) q.copy(options = q.options + "") else q }

    /** Quita una opción manteniendo marcada la misma respuesta correcta cuando sigue existiendo. */
    fun removeOption(draft: TrainingDraft, id: String, index: Int): TrainingDraft = draft.mapQuestion(id) { q ->
        if (q.options.size <= TrainingLimits.MIN_OPTIONS || index !in q.options.indices) q
        else q.copy(options = q.options.filterIndexed { i, _ -> i != index },
            correctOption = when {
                index < q.correctOption -> q.correctOption - 1
                index == q.correctOption -> 0
                else -> q.correctOption
            })
    }

    fun setCorrect(draft: TrainingDraft, id: String, index: Int): TrainingDraft =
        draft.mapQuestion(id) { q -> if (index in q.options.indices) q.copy(correctOption = index) else q }

    private fun TrainingDraft.mapQuestion(id: String, transform: (DraftQuestion) -> DraftQuestion) =
        copy(questions = questions.map { if (it.id == id && !it.official) transform(it) else it })

    private fun TrainingDraft.renumbered() = copy(
        lessons = lessons.mapIndexed { i, lesson -> lesson.copy(displayOrder = i + 1) },
        questions = questions.mapIndexed { i, question -> question.copy(displayOrder = i + 1) },
    )
}
