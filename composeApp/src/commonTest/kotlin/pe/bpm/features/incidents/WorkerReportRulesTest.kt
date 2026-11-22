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
}
