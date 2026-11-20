package pe.bpm.features.training.domain

/** Límites del contrato de Academia; la interfaz y los casos de uso los comparten. */
object TrainingLimits {
    const val COURSE_CODE_MAX = 50
    const val COURSE_TITLE_MIN = 2
    const val COURSE_TITLE_MAX = 200
    const val COURSE_DESCRIPTION_MAX = 2000
    const val DURATION_MAX_MINUTES = 1440
    const val LESSON_TITLE_MAX = 200
    const val LESSON_CONTENT_MAX = 20000
    const val MAX_LESSONS = 50
    /** «Lo esencial» de una lección: hasta 5 ideas de 1..200 caracteres. */
    const val KEY_POINTS_MAX = 5
    const val KEY_POINT_MAX = 200
    const val MAX_QUESTIONS = 100
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 6
    const val OPTION_MAX = 300
    const val QUESTION_MAX = 300
    const val EXPLANATION_MAX = 1000
    const val MAX_VIDEO_NAME = 200

    /** Tope aproximado del cuerpo del borrador (el servidor admite 1 MiB). */
    const val DRAFT_BODY_MAX_BYTES = 900_000

    /** El servidor admite hasta 1 GiB; el cliente guarda el archivo en memoria, por eso su tope es menor. */
    const val CLIENT_VIDEO_MAX_BYTES = 100 * 1024 * 1024
    val videoMimeTypes = setOf("video/mp4", "video/webm")
}

enum class DraftIssueKind {
    COURSE_CODE, COURSE_TITLE, COURSE_DESCRIPTION, COURSE_DURATION, PASSING_SCORE, LESSON_COUNT, LESSON_TITLE, LESSON_CONTENT, KEY_POINTS,
    QUESTION_COUNT, EXTERNAL_VIDEO, QUESTION_TEXT, OPTION_COUNT, OPTION_TEXT, CORRECT_OPTION, EXPLANATION, QUESTION_LESSON, DUPLICATE_ID, DUPLICATE_OPTION, BODY_SIZE,
}

/** Problema localizable: [id] identifica la lección o pregunta afectada (o vacío si es global). */
data class DraftIssue(val kind: DraftIssueKind, val id: String = "", val message: String)

object TrainingDraftRules {
    /** Igual que el backend: inicia con mayúscula o dígito; luego mayúsculas, dígitos, guion o guion bajo. */
    private val codePattern = Regex("[A-Z0-9][A-Z0-9_-]{0,${TrainingLimits.COURSE_CODE_MAX - 1}}")

    private fun String.textLength() = count { !it.isLowSurrogate() }

    fun validateNewCourse(course: NewCourse): List<DraftIssue> = buildList {
        if (!codePattern.matches(course.code.trim()))
            add(DraftIssue(DraftIssueKind.COURSE_CODE, message = "Usa un código de hasta ${TrainingLimits.COURSE_CODE_MAX} caracteres: mayúsculas, números, guion o guion bajo (ej. M-01)."))
        val title = course.title.trim()
        if (title.textLength() !in TrainingLimits.COURSE_TITLE_MIN..TrainingLimits.COURSE_TITLE_MAX || course.title.any { it.isISOControl() })
            add(DraftIssue(DraftIssueKind.COURSE_TITLE, message = "El título debe tener entre ${TrainingLimits.COURSE_TITLE_MIN} y ${TrainingLimits.COURSE_TITLE_MAX} caracteres."))
        if (course.description.textLength() > TrainingLimits.COURSE_DESCRIPTION_MAX)
            add(DraftIssue(DraftIssueKind.COURSE_DESCRIPTION, message = "La descripción admite hasta ${TrainingLimits.COURSE_DESCRIPTION_MAX} caracteres."))
        if (course.estimatedDurationMinutes !in 1..TrainingLimits.DURATION_MAX_MINUTES)
            add(DraftIssue(DraftIssueKind.COURSE_DURATION, message = "Indica una duración entre 1 y ${TrainingLimits.DURATION_MAX_MINUTES} minutos."))
    }

    fun validate(draft: TrainingDraft): List<DraftIssue> = buildList {
        if (draft.passingScore !in 0..100) add(DraftIssue(DraftIssueKind.PASSING_SCORE, message = "El puntaje de aprobación va de 0 a 100."))
        if (draft.lessons.size > TrainingLimits.MAX_LESSONS) add(DraftIssue(DraftIssueKind.LESSON_COUNT, message = "Hasta ${TrainingLimits.MAX_LESSONS} lecciones."))
        if (draft.questions.size > TrainingLimits.MAX_QUESTIONS) add(DraftIssue(DraftIssueKind.QUESTION_COUNT, message = "Hasta ${TrainingLimits.MAX_QUESTIONS} preguntas."))
        if ((draft.lessons.map { it.id } + draft.questions.map { it.id }).let { it.toSet().size != it.size })
            add(DraftIssue(DraftIssueKind.DUPLICATE_ID, message = "Hay elementos duplicados en el borrador."))
        draft.lessons.forEach { lesson ->
            if (lesson.title.trim().isEmpty() || lesson.title.textLength() > TrainingLimits.LESSON_TITLE_MAX)
                add(DraftIssue(DraftIssueKind.LESSON_TITLE, lesson.id, "El título de la lección es obligatorio y admite hasta ${TrainingLimits.LESSON_TITLE_MAX} caracteres."))
            if (lesson.content.textLength() > TrainingLimits.LESSON_CONTENT_MAX)
                add(DraftIssue(DraftIssueKind.LESSON_CONTENT, lesson.id, "El contenido admite hasta ${TrainingLimits.LESSON_CONTENT_MAX} caracteres."))
            keyPointsIssue(lesson.keyPoints)?.let { add(DraftIssue(DraftIssueKind.KEY_POINTS, lesson.id, it)) }
            if (lesson.externalVideoId != null && (lesson.videoMediaId != null || !YouTubeLinks.isVideoId(lesson.externalVideoId)))
                add(DraftIssue(DraftIssueKind.EXTERNAL_VIDEO, lesson.id, "Usa un video subido o un enlace de YouTube, no ambos."))
        }
        // El servidor limita el cuerpo completo a 1 MiB; se valida un tamaño aproximado con margen.
        val approxBytes = draft.lessons.sumOf { it.title.encodeToByteArray().size + it.content.encodeToByteArray().size + it.keyPoints.sumOf { p -> p.encodeToByteArray().size + 4 } + 200 } +
            draft.questions.sumOf { q -> q.questionText.encodeToByteArray().size + q.explanation.encodeToByteArray().size + q.options.sumOf { it.encodeToByteArray().size + 4 } + 250 }
        if (approxBytes > TrainingLimits.DRAFT_BODY_MAX_BYTES) add(DraftIssue(DraftIssueKind.BODY_SIZE, message = "El curso es demasiado extenso. Reduce el texto de lecciones o preguntas."))
        val lessonIds = draft.lessons.map { it.id }.toSet()
        draft.questions.forEach { question ->
            if (question.questionText.trim().isEmpty() || question.questionText.textLength() > TrainingLimits.QUESTION_MAX)
                add(DraftIssue(DraftIssueKind.QUESTION_TEXT, question.id, "El enunciado es obligatorio y admite hasta ${TrainingLimits.QUESTION_MAX} caracteres."))
            if (question.options.size !in TrainingLimits.MIN_OPTIONS..TrainingLimits.MAX_OPTIONS)
                add(DraftIssue(DraftIssueKind.OPTION_COUNT, question.id, "Cada pregunta necesita entre ${TrainingLimits.MIN_OPTIONS} y ${TrainingLimits.MAX_OPTIONS} opciones."))
            if (question.options.any { it.trim().isEmpty() || it.textLength() > TrainingLimits.OPTION_MAX })
                add(DraftIssue(DraftIssueKind.OPTION_TEXT, question.id, "Cada opción es obligatoria y admite hasta ${TrainingLimits.OPTION_MAX} caracteres."))
            if (question.options.map { it.trim() }.let { it.toSet().size != it.size })
                add(DraftIssue(DraftIssueKind.DUPLICATE_OPTION, question.id, "Las opciones de una pregunta no pueden repetirse."))
            if (question.correctOption !in question.options.indices)
                add(DraftIssue(DraftIssueKind.CORRECT_OPTION, question.id, "Marca cuál es la opción correcta."))
            if (question.explanation.textLength() > TrainingLimits.EXPLANATION_MAX)
                add(DraftIssue(DraftIssueKind.EXPLANATION, question.id, "La explicación admite hasta ${TrainingLimits.EXPLANATION_MAX} caracteres."))
            if (question.lessonId != null && question.lessonId !in lessonIds)
                add(DraftIssue(DraftIssueKind.QUESTION_LESSON, question.id, "La lección asociada ya no existe."))
        }
    }

    /**
     * «Lo esencial» de una lección: hasta [TrainingLimits.KEY_POINTS_MAX] ideas de una línea y de hasta
     * [TrainingLimits.KEY_POINT_MAX] caracteres, sin repetirse. Los campos vacíos se ignoran (no se envían).
     */
    fun keyPointsIssue(points: List<String>): String? {
        val filled = points.map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            filled.size > TrainingLimits.KEY_POINTS_MAX -> "«Lo esencial» admite hasta ${TrainingLimits.KEY_POINTS_MAX} ideas."
            filled.any { it.textLength() > TrainingLimits.KEY_POINT_MAX } -> "Cada idea de «Lo esencial» admite hasta ${TrainingLimits.KEY_POINT_MAX} caracteres."
            filled.any { line -> line.any { it.isISOControl() } } -> "Cada idea de «Lo esencial» va en una sola línea."
            filled.map { it.lowercase() }.let { it.toSet().size != it.size } -> "Las ideas de «Lo esencial» no pueden repetirse."
            else -> null
        }
    }

    /** Qué falta para poder publicar; el servidor exige al menos una lección y una pregunta. */
    fun publishBlockers(draft: TrainingDraft): List<String> = buildList {
        if (draft.lessons.isEmpty()) add("Agrega al menos una lección.")
        if (draft.questions.isEmpty()) add("Agrega al menos una pregunta.")
        if (validate(draft).isNotEmpty()) add("Corrige los campos señalados.")
    }
}

/** Reasigna el orden consecutivo y recorta textos; el cuerpo enviado nunca depende de la interfaz. */
fun TrainingDraft.normalized(): TrainingDraft = copy(
    lessons = lessons.mapIndexed { i, lesson -> lesson.copy(title = lesson.title.trim(), displayOrder = i + 1, keyPoints = lesson.keyPoints.map { it.trim() }.filter { it.isNotEmpty() }) },
    questions = questions.mapIndexed { i, q ->
        q.copy(questionText = q.questionText.trim(), options = q.options.map { it.trim() }, explanation = q.explanation.trim(), displayOrder = i + 1)
    },
)

/** Comprueba nombre, tipo y firma de formato del video elegido; el backend vuelve a verificar. */
object TrainingVideoRules {
    /** Devuelve `null` si es válido o un mensaje con el problema encontrado. */
    fun validate(name: String, mimeType: String, bytes: ByteArray): String? = when {
        name.isBlank() || name.length > TrainingLimits.MAX_VIDEO_NAME || name.any { it == '/' || it == '\\' || it.isISOControl() } || name != name.trim() -> "El nombre del archivo no es válido."
        mimeType !in TrainingLimits.videoMimeTypes -> "Elige un video MP4 o WebM."
        bytes.isEmpty() -> "No se pudo leer el archivo."
        bytes.size > TrainingLimits.CLIENT_VIDEO_MAX_BYTES -> "El video supera el tamaño permitido en este dispositivo."
        !signatureMatches(mimeType, bytes) -> "El contenido no corresponde a un video $mimeType."
        else -> null
    }

    /** MP4 declara `ftyp` desde el byte 4; WebM empieza con la cabecera EBML 1A 45 DF A3. */
    fun signatureMatches(mimeType: String, bytes: ByteArray): Boolean = when (mimeType) {
        "video/mp4" -> bytes.size >= 12 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
        "video/webm" -> bytes.size >= 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()
        else -> false
    }

    fun mimeForName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        else -> null
    }
}
