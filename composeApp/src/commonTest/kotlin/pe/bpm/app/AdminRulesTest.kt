package pe.bpm.app

import pe.bpm.core.designsystem.BpmPlayTone
import pe.bpm.core.designsystem.BpmTone
import pe.bpm.features.reports.domain.ComplianceWindow
import pe.bpm.features.reports.domain.Kpis
import pe.bpm.features.reports.domain.LocationCompliance
import pe.bpm.features.reports.domain.PendingChecks
import pe.bpm.features.reports.domain.TrainingKpis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminRulesTest {
    private fun kpis(week: Double? = 86.0, runs: Int = 12, assigned: Int = 14, overdue: Int = 2, incidents: Int = 3, training: TrainingKpis? = TrainingKpis(5, 13, 8, 3, 62.0, null)) =
        Kpis("2026-11-22T10:00:00Z", ComplianceWindow(null, 0), ComplianceWindow(week, runs), ComplianceWindow(80.0, 40),
            PendingChecks(assigned, assigned - 10, overdue), openAlerts = 1, openIncidents = incidents, training = training)

    @Test fun attentionListsOnlyRealReasonsInOrderOfSeverity() {
        assertTrue(adminAttention(0, 0, 0).isEmpty())
        assertTrue(adminAttention(null, null, null).isEmpty())
        assertEquals(listOf("1 control vencido", "2 incidencias abiertas", "3 avisos sin atender"), adminAttention(1, 2, 3).map { it.text })
        assertEquals(listOf(AdminAttentionKind.OpenAlerts), adminAttention(0, null, 1).map { it.kind })
        assertEquals(listOf("2 sedes con bajo cumplimiento"), adminAttention(0, 0, 0, 2).map { it.text })
        assertTrue(adminAttention(0, 0, 0, 0).isEmpty())
    }

    @Test fun kpisUseTheDashboardDataAndShowNoDataWhenMissing() {
        val (compliance, onTime, incidents, training) = adminKpis(kpis())
        assertEquals("86 %", compliance.value); assertEquals(BpmPlayTone.Correct, compliance.tone)
        assertEquals("12/14", onTime.value); assertEquals("2 vencidos", onTime.support); assertEquals(BpmPlayTone.Error, onTime.tone)
        assertEquals("3", incidents.value); assertEquals(BpmPlayTone.Error, incidents.tone)
        assertEquals("62 %", training.value); assertEquals("8 completados · 3 en curso", training.support)

        val empty = adminKpis(null)
        assertTrue(empty.all { it.value == null && it.progress == null && it.support == null })
        // Un jefe de sede no recibe capacitación y una semana sin controles no inventa un porcentaje.
        val partial = adminKpis(kpis(week = null, runs = 0, assigned = 0, overdue = 0, incidents = 0, training = null))
        assertNull(partial[0].value); assertNull(partial[1].value); assertEquals("0", partial[2].value); assertEquals(BpmPlayTone.Correct, partial[2].tone); assertNull(partial[3].value)
    }

    @Test fun siteRowsShowABarOnlyWhenTheServerHasFinishedChecks() {
        val good = adminSiteRow("s1", "Sede principal", "Av. Lima 1", LocationCompliance("s1", "Sede principal", 5, 94.0, emptyList()))
        assertEquals("94 %", good.value); assertEquals("Al día", good.statusText); assertEquals(BpmTone.Success, good.statusTone); assertEquals(0.94f, good.progress!!, 0.001f)
        assertEquals("Atención", adminSiteRow("s2", "B", "", LocationCompliance("s2", "B", 2, 71.0, emptyList())).statusText)
        assertEquals("Revisar", adminSiteRow("s3", "C", "", LocationCompliance("s3", "C", 2, 40.0, emptyList())).statusText)
        for (none in listOf(null, LocationCompliance("s4", "D", 0, null, emptyList()), LocationCompliance("s4", "D", 0, 90.0, emptyList()))) {
            val row = adminSiteRow("s4", "D", "Calle 2", none)
            assertNull(row.progress); assertNull(row.value); assertEquals("Sin controles", row.statusText); assertEquals("Calle 2", row.subtitle)
        }
    }
}
