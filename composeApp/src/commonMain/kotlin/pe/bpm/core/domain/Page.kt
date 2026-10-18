package pe.bpm.core.domain

/** El cursor es opaco para el cliente y solo se reutiliza con la misma consulta. */
data class PageRequest(val token: String = "", val query: String = "", val size: Int = 25)
data class Page<T>(val items: List<T>, val nextToken: String = "")
