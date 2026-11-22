package pe.bpm.features.normative.domain

/** Normativa disponible para buscar (RF-46/47); [available] es falso con un servidor anterior a V17. */
data class NormativeStatus(val documents: Int, val available: Boolean = true)

/** Párrafo de la normativa activa que coincide con la búsqueda. */
data class NormativeMatch(val documentId: String, val documentTitle: String, val documentSource: String, val text: String)

/** Documento de normativa (solo administrador general). [content] solo viene al abrirlo. */
data class NormativeDocument(
    val id: String, val title: String, val source: String, val status: String, val characters: Int,
    val updatedAt: String, val content: String = "",
)

data class NormativeDocumentInput(val title: String, val source: String, val content: String)

enum class NormativeFailure { SESSION, DENIED, NOT_FOUND, INVALID, UNAVAILABLE, CONNECTION, INVALID_RESPONSE }

sealed interface NormativeResult<out T> {
    data class Success<T>(val value: T) : NormativeResult<T>
    data class Failure(val reason: NormativeFailure, val fields: Map<String, String> = emptyMap()) : NormativeResult<Nothing>
}

interface NormativeRepository {
    suspend fun status(): NormativeResult<NormativeStatus>
    suspend fun search(query: String): NormativeResult<List<NormativeMatch>>
    suspend fun documents(archived: Boolean): NormativeResult<List<NormativeDocument>>
    suspend fun document(id: String): NormativeResult<NormativeDocument>
    suspend fun create(input: NormativeDocumentInput): NormativeResult<NormativeDocument>
    suspend fun update(id: String, input: NormativeDocumentInput): NormativeResult<NormativeDocument>
    suspend fun setArchived(id: String, archived: Boolean): NormativeResult<NormativeDocument>
}

/** Reglas de entrada iguales a las del servidor, para avisar antes de enviar. */
object NormativeRules {
    const val QUERY_MIN = 2
    const val QUERY_MAX = 200
    const val TITLE_MAX = 200
    const val SOURCE_MAX = 300
    const val CONTENT_MAX = 400_000

    fun queryIssue(text: String): String? = text.trim().length.let {
        if (it < QUERY_MIN) "Escribe al menos $QUERY_MIN letras." else if (it > QUERY_MAX) "La búsqueda admite hasta $QUERY_MAX caracteres." else null
    }

    /** Palabras de la búsqueda (sin tildes ni mayúsculas) para resaltarlas en los resultados. */
    fun terms(query: String): List<String> = query.split(Regex("\\s+")).map(::fold).filter { it.length >= 3 }.distinct()

    /**
     * Rangos de [text] a resaltar: palabras cuyo inicio coincide con la raíz de un término buscado (hasta 5
     * letras), sin tildes ni mayúsculas. Aproxima la búsqueda en español del servidor (plurales y variantes).
     */
    fun highlights(text: String, terms: List<String>): List<IntRange> {
        if (terms.isEmpty()) return emptyList()
        val roots = terms.map { it.take(maxOf(3, minOf(it.length, 5))) }
        return Regex("\\p{L}+").findAll(text).filter { word ->
            val folded = fold(word.value)
            folded.length >= 3 && roots.any { folded.startsWith(it) }
        }.map { it.range }.toList()
    }

    /** Minúsculas sin tildes, igual que el índice del servidor. */
    fun fold(text: String): String = text.lowercase().map { c -> when (c) { 'á' -> 'a'; 'é' -> 'e'; 'í' -> 'i'; 'ó' -> 'o'; 'ú', 'ü' -> 'u'; 'ñ' -> 'n'; else -> c } }.joinToString("")

    fun documentIssues(input: NormativeDocumentInput): Map<String, String> = buildMap {
        val title = input.title.trim()
        if (title.isEmpty() || title.length > TITLE_MAX || '\n' in title) put("title", "El título es obligatorio, de una línea y de hasta $TITLE_MAX caracteres.")
        if (input.source.trim().length > SOURCE_MAX || '\n' in input.source.trim()) put("source", "La referencia admite una línea de hasta $SOURCE_MAX caracteres.")
        val content = input.content.trim()
        if (content.isEmpty() || content.length > CONTENT_MAX) put("content", "Pega el texto de la norma (hasta 400 000 caracteres).")
    }

    fun failureMessage(reason: NormativeFailure): String = when (reason) {
        NormativeFailure.SESSION -> "Tu sesión venció. Inicia sesión nuevamente."
        NormativeFailure.DENIED -> "Tu cuenta no tiene permiso para esta operación."
        NormativeFailure.NOT_FOUND -> "El documento ya no está disponible."
        NormativeFailure.INVALID -> "Revisa los datos indicados."
        NormativeFailure.UNAVAILABLE -> "El servicio no está disponible. Intenta de nuevo en unos minutos."
        NormativeFailure.CONNECTION -> "No pudimos conectar. Revisa tu conexión e inténtalo otra vez."
        NormativeFailure.INVALID_RESPONSE -> "No pudimos verificar la respuesta del servidor. Inténtalo otra vez."
    }
}
