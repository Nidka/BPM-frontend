package pe.bpm.app

import pe.bpm.core.designsystem.BpmPlayTone
import pe.bpm.core.designsystem.BpmTone
import pe.bpm.features.reports.domain.Kpis
import pe.bpm.features.workspace.domain.Organization

/*
 * Reglas puras de las pantallas de la super administración (Organizaciones, Indicadores). Sin Compose ni red: lo que el
 * servidor no entrega es nulo («Sin datos»). La API no da cifras por organización; solo la organización abierta tiene
 * indicadores, así que se rotulan con su nombre.
 */

/** «Restaurante», «Cafetín»…; un tipo desconocido se muestra sin traducir. */
internal fun businessTypeLabel(type: String): String = when (type) {
    "RESTAURANT" -> "Restaurante"
    "CAFETERIA" -> "Cafetín"
    "CATERING" -> "Catering"
    "OTHER_FOOD_SERVICE" -> "Otro servicio de alimentos"
    else -> type
}

/** «Razón social · Restaurante»; la razón social solo si difiere del nombre comercial. */
internal fun organizationSubtitle(organization: Organization): String =
    listOfNotNull(organization.legalName.takeIf { it.isNotBlank() && it != organization.name }, businessTypeLabel(organization.businessType).takeIf { it.isNotBlank() })
        .joinToString(" · ")

/**
 * Cuatro indicadores de Organizaciones: cuántas organizaciones hay en la página cargada ([more]: hay otras páginas,
 * cifra con «+») y tres de la organización abierta ([current]) con su nombre como apoyo (cumplimiento a 30 días,
 * capacitación e incidencias abiertas). Sin datos de la organización abierta, cada cifra es nula.
 */
internal fun platformKpis(count: Int, more: Boolean, searching: Boolean, current: String?, kpis: Kpis?): List<AdminKpi> {
    val month = kpis?.last30Days
    val average = month?.average
    val completion = kpis?.training?.completionPercent
    val open = kpis?.openIncidents
    return listOf(
        AdminKpi(if (searching) "Coincidencias" else "Organizaciones", if (count == 0 && !more) null else if (more) "$count+" else "$count", BpmPlayTone.Action, null,
            if (more) "Hay más en otras páginas" else if (count > 0) "En total" else null),
        AdminKpi("Cumplimiento", percentValue(average), average?.let(::complianceTone) ?: BpmPlayTone.Action, average?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) },
            listOfNotNull("30 días", current).joinToString(" · ")),
        AdminKpi("Capacitación", percentValue(completion), BpmPlayTone.Gold, completion?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) },
            listOfNotNull(current).joinToString(" · ").ifBlank { null }),
        AdminKpi("Incidencias abiertas", open?.toString(), if (open == null) BpmPlayTone.Action else if (open > 0) BpmPlayTone.Error else BpmPlayTone.Correct, null,
            listOfNotNull(current).joinToString(" · ").ifBlank { null }),
    )
}

/** Fila de una organización: la abierta lleva el cumplimiento a 30 días si el servidor lo tiene y el estado «Abierta». */
internal data class PlatformOrganizationRow(val id: String, val name: String, val subtitle: String, val progress: Float?, val value: String?,
    val tone: BpmPlayTone, val statusText: String?, val statusTone: BpmTone, val current: Boolean)

internal fun platformOrganizationRows(organizations: List<Organization>, selectedId: String?, kpis: Kpis?): List<PlatformOrganizationRow> = organizations.map { org ->
    val current = org.id == selectedId
    val average = if (current) kpis?.last30Days?.takeIf { it.runs > 0 }?.average else null
    PlatformOrganizationRow(org.id, org.name, organizationSubtitle(org),
        average?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) }, percentValue(average), average?.let(::complianceTone) ?: BpmPlayTone.Action,
        if (current) "Abierta" else null, BpmTone.Info, current)
}
