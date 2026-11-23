package pe.bpm.app

import pe.bpm.core.designsystem.BpmPlayTone
import pe.bpm.core.designsystem.BpmTone
import pe.bpm.features.reports.domain.Kpis
import pe.bpm.features.reports.domain.LocationCompliance
import kotlin.math.roundToInt

/*
 * Reglas puras de las pantallas del administrador (Inicio, Controles). Sin Compose ni red: el servidor calcula los
 * indicadores; el cliente solo los redacta y les da un tono. Un dato que el servidor no entrega es nulo («Sin datos»).
 */

/** Motivo de la franja «Requiere atención» del Inicio del administrador. */
internal enum class AdminAttentionKind { OverdueChecks, OpenIncidents, OpenAlerts, LowSites }

/** Un motivo con su cantidad (siempre > 0). */
internal data class AdminAttention(val kind: AdminAttentionKind, val count: Int) {
    /** «1 control vencido», «2 incidencias abiertas», «3 avisos sin atender». */
    val text: String
        get() = when (kind) {
            AdminAttentionKind.OverdueChecks -> if (count == 1) "1 control vencido" else "$count controles vencidos"
            AdminAttentionKind.OpenIncidents -> if (count == 1) "1 incidencia abierta" else "$count incidencias abiertas"
            AdminAttentionKind.OpenAlerts -> if (count == 1) "1 aviso sin atender" else "$count avisos sin atender"
            AdminAttentionKind.LowSites -> if (count == 1) "1 sede con bajo cumplimiento" else "$count sedes con bajo cumplimiento"
        }
}

/** Motivos de atención con los datos del panel de indicadores y de cumplimiento por sede ([lowSites]: sedes por debajo del 70 %): los nulos o en cero no generan motivo; orden de gravedad. */
internal fun adminAttention(overdueChecks: Int?, openIncidents: Int?, openAlerts: Int?, lowSites: Int? = null): List<AdminAttention> = listOfNotNull(
    overdueChecks?.takeIf { it > 0 }?.let { AdminAttention(AdminAttentionKind.OverdueChecks, it) },
    openIncidents?.takeIf { it > 0 }?.let { AdminAttention(AdminAttentionKind.OpenIncidents, it) },
    openAlerts?.takeIf { it > 0 }?.let { AdminAttention(AdminAttentionKind.OpenAlerts, it) },
    lowSites?.takeIf { it > 0 }?.let { AdminAttention(AdminAttentionKind.LowSites, it) },
)

/** Cifra de un indicador en porcentaje entero («86 %»); nula si el servidor no tiene ejecuciones. */
internal fun percentValue(average: Double?): String? = average?.let { "${it.roundToInt()} %" }

/** Tono del cumplimiento: desde 85 % bien, desde 70 % atención y por debajo mal. */
internal fun complianceTone(average: Double): BpmPlayTone = when {
    average >= 85.0 -> BpmPlayTone.Correct
    average >= 70.0 -> BpmPlayTone.Gold
    else -> BpmPlayTone.Error
}

/** Un indicador del Inicio: etiqueta, cifra (nula = «Sin datos»), tono, avance y línea de contexto. */
internal data class AdminKpi(val label: String, val value: String?, val tone: BpmPlayTone, val progress: Float?, val support: String?)

/** Los cuatro indicadores del Inicio con los datos del panel de indicadores (`Kpis`); nulo = aún sin cargar. */
internal fun adminKpis(kpis: Kpis?): List<AdminKpi> {
    val week = kpis?.last7Days
    val weekAverage = week?.average
    val checks = kpis?.pendingChecks
    val onTime = checks?.takeIf { it.assigned > 0 }?.let { it.assigned - it.overdue }
    val training = kpis?.training
    val completion = training?.completionPercent
    return listOf(
        AdminKpi("Cumplimiento", percentValue(weekAverage), weekAverage?.let(::complianceTone) ?: BpmPlayTone.Action,
            weekAverage?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) },
            week?.takeIf { weekAverage != null }?.let { "Últimos 7 días · ${if (it.runs == 1) "1 control" else "${it.runs} controles"}" }),
        AdminKpi("Controles a tiempo", if (onTime != null) "$onTime/${checks.assigned}" else null,
            if (checks == null || onTime == null) BpmPlayTone.Action else if (checks.overdue > 0) BpmPlayTone.Error else BpmPlayTone.Correct,
            onTime?.let { (it.toFloat() / checks.assigned).coerceIn(0f, 1f) },
            checks?.takeIf { onTime != null }?.let { if (it.overdue == 0) "Ninguno vencido" else if (it.overdue == 1) "1 vencido" else "${it.overdue} vencidos" }),
        AdminKpi("Incidencias", kpis?.openIncidents?.toString(),
            if ((kpis?.openIncidents ?: 0) > 0) BpmPlayTone.Error else if (kpis != null) BpmPlayTone.Correct else BpmPlayTone.Action, null,
            kpis?.openIncidents?.let { if (it == 0) "Ninguna abierta" else if (it == 1) "abierta" else "abiertas" }),
        AdminKpi("Capacitación", percentValue(completion), BpmPlayTone.Gold,
            completion?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) },
            training?.takeIf { completion != null }?.let { "${it.completedEnrollments} completados · ${it.inProgressEnrollments} en curso" }),
    )
}

/** Fila de una sede en el Inicio: cumplimiento del periodo si el servidor lo tiene; sin controles finalizados, sin barra. */
internal data class AdminSiteRow(val id: String, val name: String, val subtitle: String?, val progress: Float?, val value: String?,
    val tone: BpmPlayTone, val statusText: String, val statusTone: BpmTone)

internal fun adminSiteRow(id: String, name: String, address: String, compliance: LocationCompliance?): AdminSiteRow {
    val average = compliance?.average?.takeIf { compliance.runs > 0 }
    val subtitle = address.ifBlank { null }
    if (average == null) return AdminSiteRow(id, name, subtitle, null, null, BpmPlayTone.Action, "Sin controles", BpmTone.Neutral)
    val tone = complianceTone(average)
    return AdminSiteRow(id, name, subtitle, (average / 100.0).toFloat().coerceIn(0f, 1f), percentValue(average), tone,
        when (tone) { BpmPlayTone.Correct -> "Al día"; BpmPlayTone.Gold -> "Atención"; else -> "Revisar" },
        when (tone) { BpmPlayTone.Correct -> BpmTone.Success; BpmPlayTone.Gold -> BpmTone.Warning; else -> BpmTone.Danger })
}
