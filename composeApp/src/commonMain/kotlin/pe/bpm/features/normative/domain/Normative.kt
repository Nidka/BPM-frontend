package pe.bpm.features.normative.domain

/** Estado del agente normativo (RF-46/47): apagado sin clave en el servidor o sin normativa cargada. */
data class NormativeStatus(val enabled: Boolean, val documents: Int, val questionsPerHour: Int)

/** Fragmento literal de la normativa que respalda una respuesta. */
data class NormativeCitation(val documentTitle: String, val citedText: String)

/** Respuesta del agente; [supported] es falso si la normativa cargada no la respalda (sin citas). */
data class NormativeAnswer(val question: String, val answer: String, val citations: List<NormativeCitation>, val supported: Boolean)

/** Documento de normativa (solo administrador general). [content] solo viene al abrirlo. */
data class NormativeDocument(
    val id: String, val title: String, val source: String, val status: String, val characters: Int,
    val updatedAt: String, val content: String = "",
)

data class NormativeDocumentInput(val title: String, val source: String, val content: String)

enum class NormativeFailure { SESSION, DENIED, NOT_FOUND, DISABLED, NO_DOCUMENTS, RATE_LIMITED, TOO_LARGE, INVALID, UNAVAILABLE, CONNECTION, INVALID_RESPONSE }

sealed interface NormativeResult<out T> {
    data class Success<T>(val value: T) : NormativeResult<T>
    data class Failure(val reason: NormativeFailure, val fields: Map<String, String> = emptyMap()) : NormativeResult<Nothing>
}

interface NormativeRepository {
    suspend fun status(): NormativeResult<NormativeStatus>
    suspend fun ask(question: String): NormativeResult<NormativeAnswer>
    suspend fun documents(archived: Boolean): NormativeResult<List<NormativeDocument>>
    suspend fun document(id: String): NormativeResult<NormativeDocument>
    suspend fun create(input: NormativeDocumentInput): NormativeResult<NormativeDocument>
    suspend fun update(id: String, input: NormativeDocumentInput): NormativeResult<NormativeDocument>
    suspend fun setArchived(id: String, archived: Boolean): NormativeResult<NormativeDocument>
}

/** Reglas de entrada iguales a las del servidor, para avisar antes de enviar. */
object NormativeRules {
    const val QUESTION_MIN = 3
    const val QUESTION_MAX = 1000
    const val TITLE_MAX = 200
    const val SOURCE_MAX = 300
    const val CONTENT_MAX = 400_000

    fun questionIssue(text: String): String? = text.trim().length.let {
        if (it < QUESTION_MIN) "Escribe tu pregunta." else if (it > QUESTION_MAX) "La pregunta admite hasta $QUESTION_MAX caracteres." else null
    }

    fun documentIssues(input: NormativeDocumentInput): Map<String, String> = buildMap {
        val title = input.title.trim()
        if (title.isEmpty() || title.length > TITLE_MAX || '\n' in title) put("title", "El título es obligatorio, de una línea y de hasta $TITLE_MAX caracteres.")
        if (input.source.trim().length > SOURCE_MAX || '\n' in input.source.trim()) put("source", "La referencia admite una línea de hasta $SOURCE_MAX caracteres.")
        val content = input.content.trim()
        if (content.isEmpty() || content.length > CONTENT_MAX) put("content", "Pega el texto de la norma (hasta 400 000 caracteres).")
    }

    fun failureMessage(reason: NormativeFailure, questionsPerHour: Int = 20): String = when (reason) {
        NormativeFailure.SESSION -> "Tu sesión venció. Inicia sesión nuevamente."
        NormativeFailure.DENIED -> "Tu cuenta no tiene permiso para esta operación."
        NormativeFailure.NOT_FOUND -> "El documento ya no está disponible."
        NormativeFailure.DISABLED -> "El asistente de normativa aún no está activado. Consulta con el administrador general."
        NormativeFailure.NO_DOCUMENTS -> "Todavía no hay normativa cargada para consultar."
        NormativeFailure.RATE_LIMITED -> "Llegaste al límite de $questionsPerHour consultas por hora. Intenta más tarde."
        NormativeFailure.TOO_LARGE -> "La normativa activa superaría el límite. Archiva un documento antes de agregar otro."
        NormativeFailure.INVALID -> "Revisa los datos indicados."
        NormativeFailure.UNAVAILABLE -> "El asistente no respondió. Intenta de nuevo en unos minutos."
        NormativeFailure.CONNECTION -> "No pudimos conectar. Revisa tu conexión e inténtalo otra vez."
        NormativeFailure.INVALID_RESPONSE -> "No pudimos verificar la respuesta del servidor. Inténtalo otra vez."
    }
}
