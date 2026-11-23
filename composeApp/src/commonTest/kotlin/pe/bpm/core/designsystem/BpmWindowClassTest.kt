package pe.bpm.core.designsystem

import androidx.compose.ui.unit.dp
import kotlin.test.*

class BpmWindowClassTest {
    @Test fun breakpointsFollowMaterialWidthClasses() {
        val cases = listOf(360 to BpmWindowClass.Compact, 599 to BpmWindowClass.Compact, 600 to BpmWindowClass.Medium,
            839 to BpmWindowClass.Medium, 840 to BpmWindowClass.Expanded, 1199 to BpmWindowClass.Expanded,
            1200 to BpmWindowClass.Large, 1600 to BpmWindowClass.Large)
        for ((width, expected) in cases) assertEquals(expected, BpmWindowClass.fromWidth(width.dp), "$width dp")
    }

    @Test fun marginsAre16_24_32_32() {
        assertEquals(listOf(16.dp, 24.dp, 32.dp, 32.dp), BpmWindowClass.entries.map { BpmSpacing.gutter(it) })
    }

    @Test fun navigationChangesWithWidthAndFallsBackToBottomBarWhenShort() {
        assertEquals(BpmNavigationMode.BottomBar, BpmNavigationMode.of(BpmWindowClass.Compact, 800.dp))
        assertEquals(BpmNavigationMode.BottomBar, BpmNavigationMode.of(BpmWindowClass.Medium, 800.dp))
        assertEquals(BpmNavigationMode.SideMenu, BpmNavigationMode.of(BpmWindowClass.Expanded, 800.dp))
        assertEquals(BpmNavigationMode.SideMenu, BpmNavigationMode.of(BpmWindowClass.Large, 800.dp))
        assertEquals(BpmNavigationMode.BottomBar, BpmNavigationMode.of(BpmWindowClass.Large, 400.dp))
        assertEquals(230.dp, BpmNavigationMode.SideMenu.width)
    }

    @Test fun patternRulesMatchTheDesignTable() {
        val c = BpmWindowClass.Compact; val m = BpmWindowClass.Medium; val e = BpmWindowClass.Expanded; val l = BpmWindowClass.Large
        assertEquals(2, BpmPatternRules.indicatorColumns(c)); assertEquals(4, BpmPatternRules.indicatorColumns(m))
        // Tres indicadores caben en una fila a letra normal; con letra grande bajan a dos columnas.
        assertEquals(3, BpmPatternRules.indicatorColumns(c, 3)); assertEquals(2, BpmPatternRules.indicatorColumns(c, 3, fontScale = 1.5f))
        assertEquals(3, BpmPatternRules.indicatorColumns(m, 3, fontScale = 1.5f))
        assertEquals(listOf(false, false, false, true), listOf(c, m, e, l).map { BpmPatternRules.panelSplit(it) })
        assertEquals(listOf(false, false, false, true), listOf(c, m, e, l).map { BpmPatternRules.formPreview(it) })
        assertEquals(720.dp, BpmPatternRules.contentMax(BpmPattern.StepFlow, m))
        assertEquals(440.dp, BpmPatternRules.contentMax(BpmPattern.Access, e))
        assertEquals(1200.dp, BpmPatternRules.contentMax(BpmPattern.Panel, l))
    }

    /** Ancho útil = ventana − navegación − márgenes (specs/LAYOUT.md §1). */
    private fun usable(window: Int): androidx.compose.ui.unit.Dp {
        val windowClass = BpmWindowClass.fromWidth(window.dp)
        val nav = BpmNavigationMode.of(windowClass, 800.dp).width
        return window.dp - nav - BpmSpacing.gutter(windowClass) * 2
    }

    @Test fun gridColumnsFollowUsableWidthNotWindow() {
        assertEquals(1, BpmPatternRules.gridColumns(360.dp))
        assertEquals(1, BpmPatternRules.gridColumns(615.dp))
        assertEquals(2, BpmPatternRules.gridColumns(616.dp))
        assertEquals(2, BpmPatternRules.gridColumns(931.dp))
        assertEquals(3, BpmPatternRules.gridColumns(932.dp))
        assertEquals(3, BpmPatternRules.gridColumns(1200.dp))
        // 600 dp de ventana con barra inferior: 552 dp útiles → una columna (antes: dos tarjetas de 228 dp).
        assertEquals(1, BpmPatternRules.gridColumns(usable(600)))
        // 900 dp de ventana con menú lateral: 596 dp útiles → una columna.
        assertEquals(1, BpmPatternRules.gridColumns(usable(900)))
        assertEquals(2, BpmPatternRules.gridColumns(usable(1000)))
        assertEquals(3, BpmPatternRules.gridColumns(usable(1600)))
    }

    @Test fun detailGoesBesideListOnlyWhenItStaysReadable() {
        assertFalse(BpmPatternRules.detailBesideList(863.dp))
        assertTrue(BpmPatternRules.detailBesideList(864.dp))
        // 840–1199 dp de ventana con menú: el detalle quedaba en 152–511 dp junto a la lista; ahora ocupa la pantalla.
        assertFalse(BpmPatternRules.detailBesideList(usable(840)))
        assertFalse(BpmPatternRules.detailBesideList(usable(1100)))
        assertTrue(BpmPatternRules.detailBesideList(usable(1200)))
    }

    @Test fun fieldPairsAndDetailColumnsDependOnContainer() {
        assertFalse(BpmPatternRules.shortFieldsSideBySide(419.dp)); assertTrue(BpmPatternRules.shortFieldsSideBySide(420.dp))
        assertEquals(1, BpmPatternRules.detailFieldColumns(519.dp)); assertEquals(2, BpmPatternRules.detailFieldColumns(520.dp))
        assertEquals(720.dp, BpmPatternRules.readingWidth)
    }
}
