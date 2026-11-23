package pe.bpm.app

import pe.bpm.core.designsystem.BpmPlayTone
import pe.bpm.features.reports.domain.ComplianceWindow
import pe.bpm.features.reports.domain.Kpis
import pe.bpm.features.reports.domain.PendingChecks
import pe.bpm.features.reports.domain.TrainingKpis
import pe.bpm.features.workspace.domain.Organization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformRulesTest {
    private val andina = Organization("o1", "Cocina Andina S.A.C.", "Cocina Andina", "RESTAURANT")
    private val tika = Organization("o2", "Tika", "", "CAFETERIA")
    private fun kpis(month: Double? = 81.0, runs: Int = 40, incidents: Int = 2) =
        Kpis("2026-11-22T10:00:00Z", ComplianceWindow(null, 0), ComplianceWindow(80.0, 10), ComplianceWindow(month, runs),
            PendingChecks(10, 5, 1), openAlerts = 0, openIncidents = incidents, training = TrainingKpis(3, 9, 5, 4, 57.0, null))

    @Test fun subtitleShowsTheLegalNameOnlyWhenItDiffersAndTheBusinessType() {
        assertEquals("Cocina Andina S.A.C. · Restaurante", organizationSubtitle(andina))
        assertEquals("Cafetín", organizationSubtitle(tika))
        assertEquals("OTRO", businessTypeLabel("OTRO"))
    }

    @Test fun kpisCountOrganizationsAndLabelTheOpenOneByName() {
        val (count, compliance, training, incidents) = platformKpis(2, more = false, searching = false, current = "Cocina Andina", kpis = kpis())
        assertEquals("Organizaciones", count.label); assertEquals("2", count.value); assertEquals("En total", count.support)
        assertEquals("81 %", compliance.value); assertEquals(BpmPlayTone.Gold, compliance.tone); assertEquals("30 días · Cocina Andina", compliance.support)
        assertEquals("57 %", training.value)
        assertEquals("2", incidents.value); assertEquals(BpmPlayTone.Error, incidents.tone)
    }

    @Test fun moreThanOnePageAndSearchesAreDescribedHonestly() {
        val paged = platformKpis(25, more = true, searching = false, current = null, kpis = null)
        assertEquals("25+", paged[0].value); assertEquals("Hay más en otras páginas", paged[0].support)
        assertEquals("Coincidencias", platformKpis(1, more = false, searching = true, current = null, kpis = null)[0].label)
        // Sin organizaciones ni datos no se inventa nada: todo «Sin datos».
        assertTrue(platformKpis(0, false, false, null, null).all { it.value == null })
        // Sin organización abierta no hay apoyo con nombre.
        assertEquals("30 días", platformKpis(1, false, false, null, kpis())[1].support)
    }

    @Test fun onlyTheOpenOrganizationGetsComplianceAndStatus() {
        val rows = platformOrganizationRows(listOf(andina, tika), "o1", kpis())
        assertTrue(rows[0].current); assertEquals("Abierta", rows[0].statusText); assertEquals("81 %", rows[0].value)
        assertEquals(0.81f, rows[0].progress)
        assertFalse(rows[1].current); assertNull(rows[1].statusText); assertNull(rows[1].value); assertNull(rows[1].progress)
        // Sin controles en 30 días no hay barra.
        assertNull(platformOrganizationRows(listOf(andina), "o1", kpis(month = 90.0, runs = 0))[0].progress)
        assertNull(platformOrganizationRows(listOf(andina), "o1", null)[0].value)
    }
}
