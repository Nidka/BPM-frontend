package pe.bpm.features.workspace.presentation

import pe.bpm.core.designsystem.BpmTone
import pe.bpm.features.workspace.domain.Entry

/** Filtro local del catálogo de cursos de Aprender: se aplica solo a la página cargada. */
enum class CatalogFilter(val label: String, internal val status: String?) {
    All("Todos", null),
    NotStarted("Sin iniciar", "NOT_STARTED"),
    InProgress("En curso", "IN_PROGRESS"),
    Completed("Completados", "COMPLETED"),
}

/**
 * Cursos que se muestran en el catálogo: sin los ya asignados al alumno ([hiddenCourseIds], sin distinguir
 * mayúsculas) y con el [filter] de estado aplicado.
 */
fun visibleCatalog(entries: List<Entry>, filter: CatalogFilter, hiddenCourseIds: Set<String> = emptySet()): List<Entry> {
    val hidden = hiddenCourseIds.map { it.lowercase() }.toSet()
    return entries.filter { it.id.lowercase() !in hidden && (filter.status == null || it.status == filter.status) }
}

/** Texto y tono del chip de estado de un curso del catálogo; nulo si el estado no es de avance del alumno. */
fun catalogStatusChip(status: String): Pair<String, BpmTone>? = when (status) {
    "NOT_STARTED" -> "Sin iniciar" to BpmTone.Neutral
    "IN_PROGRESS" -> "En curso" to BpmTone.Info
    "COMPLETED" -> "Completado" to BpmTone.Success
    else -> null
}

/** Resumen corto de la descripción para la tarjeta: sin saltos de línea y con puntos suspensivos si se recorta. */
fun catalogSummary(detail: String, max: Int = 110): String {
    val flat = detail.trim().replace(Regex("\\s+"), " ")
    return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
}
