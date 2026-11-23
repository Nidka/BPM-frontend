package pe.bpm.features.incidents.presentation

import pe.bpm.features.incidents.domain.IncidentCatalog

/*
 * Categorías de un reporte. Reportar nunca depende de que la administración haya creado un catálogo: la app trae un
 * juego integrado (las mismas categorías que el servidor entiende como `catalogCode`) y, si la organización creó otras
 * o ya tiene alguna con el mismo código o nombre, se muestran junto a ellas sin repetirse.
 */

/** Una ficha del primer paso: una categoría de la organización ([catalogId]) o una integrada ([code]); exactamente una. */
internal data class ReportOption(val name: String, val catalogId: String?, val code: String?, val evidenceRequired: Boolean = false) {
    /** Clave estable para marcar la ficha elegida. */
    val key: String get() = catalogId ?: code.orEmpty()
}

/** Categorías integradas, en el orden en que se ofrecen; «Otro problema» va siempre al final. */
internal val builtInReportOptions: List<ReportOption> = listOf(
    ReportOption("Temperatura", null, "TEMPERATURA"),
    ReportOption("Limpieza", null, "LIMPIEZA"),
    ReportOption("Plagas", null, "PLAGAS"),
    ReportOption("Equipo dañado", null, "EQUIPO_DANADO"),
    ReportOption("Producto en mal estado", null, "PRODUCTO_MAL_ESTADO"),
    ReportOption("Higiene del personal", null, "HIGIENE_PERSONAL"),
    ReportOption("Otro problema", null, "OTRO"),
)

private fun plain(text: String): String =
    text.lowercase().replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u').replace('ñ', 'n').trim()

/**
 * Fichas del primer paso: las integradas (si la organización ya tiene una con el mismo código o nombre, se usa la suya,
 * con su criterio y si pide fotos), después las demás de la organización por orden alfabético, y «Otro problema» al final.
 */
internal fun reportOptions(organization: List<IncidentCatalog>): List<ReportOption> {
    val used = mutableSetOf<String>()
    fun own(code: String?, name: String): IncidentCatalog? = organization.firstOrNull {
        (code != null && it.code.equals(code, ignoreCase = true)) || plain(it.name) == plain(name)
    }?.also { used += it.id }
    val builtIn = builtInReportOptions.map { option ->
        own(option.code, option.name)?.let { ReportOption(it.name, it.id, null, it.evidenceRequired) } ?: option
    }
    val extra = organization.filter { it.id !in used }.sortedBy { plain(it.name) }.map { ReportOption(it.name, it.id, null, it.evidenceRequired) }
    return builtIn.dropLast(1) + extra + builtIn.last()
}

/** La ficha de «Otro problema» (la que se usa al decir «No sé qué tipo es»). */
internal fun otherOption(options: List<ReportOption>): ReportOption = options.last()
