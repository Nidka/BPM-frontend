package pe.bpm.features.incidents

import kotlin.test.*
import pe.bpm.core.designsystem.BpmPlayTone
import pe.bpm.core.designsystem.BpmSymbol
import pe.bpm.features.incidents.presentation.*

/** Reglas puras del reporte rápido del personal: icono por palabras clave de la categoría y descripción. */
class WorkerReportRulesTest {
    @Test fun categoryIconFollowsKeywordsOfTheName() {
        assertEquals(BpmSymbol.Thermometer, reportIcon("Temperatura fuera de rango").symbol)
        assertEquals(BpmSymbol.Bug, reportIcon("Plagas").symbol)
        assertEquals(BpmSymbol.Bug, reportIcon("Presencia de roedores").symbol)
        assertEquals(BpmSymbol.Wrench, reportIcon("Equipo malogrado").symbol)
        assertEquals(BpmSymbol.Box, reportIcon("Insumo en mal estado").symbol)
        assertEquals(BpmSymbol.Box, reportIcon("Producto vencido").symbol)
        assertEquals(BpmSymbol.Drop, reportIcon("Agua o desagüe").symbol)
        assertEquals(BpmSymbol.Spray, reportIcon("Falta de limpieza").symbol)
    }

    @Test fun anyOtherCategoryIsOtro() {
        val other = reportIcon("Ruido en el salón")
        assertEquals(BpmSymbol.Dots, other.symbol)
        assertEquals(BpmSymbol.Dots, reportIcon("").symbol)
        assertEquals(BpmPlayTone.Gold, other.tone)
    }

    @Test fun keywordsIgnoreAccentsAndCase() {
        assertEquals(BpmSymbol.Thermometer, reportIcon("TEMPERATURA").symbol)
        assertEquals(BpmSymbol.Drop, reportIcon("DESAGÜE tapado").symbol)
        assertEquals(BpmSymbol.Bug, reportIcon("Cucarachas en almacén").symbol)
    }

    @Test fun descriptionJoinsPlaceAndNote() {
        assertEquals("Dónde: Cocina. La cámara no enfría", reportDescription(" Cocina ", " La cámara no enfría "))
        assertEquals("Dónde: Almacén", reportDescription("Almacén", ""))
        assertEquals("Hay una gotera", reportDescription("", "Hay una gotera"))
        assertEquals("", reportDescription("  ", " "))
        assertEquals(4000, reportDescription("", "x".repeat(5000)).length)
    }

    private fun catalog(id: String, code: String, name: String, evidence: Boolean = false) = pe.bpm.features.incidents.domain.IncidentCatalog(
        id, code, name, "Cocina", "Criterio", pe.bpm.features.incidents.domain.IncidentRisk.MEDIUM, evidence, 1)

    @Test fun anOrganizationWithoutCatalogStillOffersEveryBuiltInCategoryEndingInOther() {
        val options = reportOptions(emptyList())
        assertEquals(listOf("Temperatura", "Limpieza", "Plagas", "Equipo dañado", "Producto en mal estado", "Higiene del personal", "Otro problema"), options.map { it.name })
        assertTrue(options.all { it.catalogId == null && it.code != null })
        assertEquals("OTRO", otherOption(options).code)
    }

    @Test fun organizationCategoriesJoinTheDefaultsWithoutRepeatingThem() {
        val own = listOf(catalog("c1", "TEMPERATURA", "Cámaras", evidence = true), catalog("c2", "X", "limpieza"), catalog("c3", "Z", "Ruido"))
        val options = reportOptions(own)
        // «TEMPERATURA» (por código) y «limpieza» (por nombre) reemplazan a las integradas; «Ruido» se suma antes de «Otro problema».
        assertEquals(listOf("Cámaras", "limpieza", "Plagas", "Equipo dañado", "Producto en mal estado", "Higiene del personal", "Ruido", "Otro problema"), options.map { it.name })
        assertEquals("c1", options.first().catalogId)
        assertNull(options.first().code)
        assertTrue(options.first().evidenceRequired)
        assertEquals(1, options.count { it.catalogId == "c2" })
    }

    @Test fun personalHygieneGetsItsOwnIcon() {
        assertEquals(BpmSymbol.Hand, reportIcon("Higiene del personal").symbol)
        assertEquals(BpmSymbol.Wrench, reportIcon("Equipo dañado").symbol)
    }
}
