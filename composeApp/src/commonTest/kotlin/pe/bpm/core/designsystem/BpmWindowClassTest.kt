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
        assertEquals(BpmNavigationMode.Rail, BpmNavigationMode.of(BpmWindowClass.Medium, 800.dp))
        assertEquals(BpmNavigationMode.SideMenu, BpmNavigationMode.of(BpmWindowClass.Expanded, 800.dp))
        assertEquals(BpmNavigationMode.SideMenu, BpmNavigationMode.of(BpmWindowClass.Large, 800.dp))
        assertEquals(BpmNavigationMode.BottomBar, BpmNavigationMode.of(BpmWindowClass.Large, 400.dp))
        assertEquals(80.dp, BpmNavigationMode.Rail.width)
        assertEquals(240.dp, BpmNavigationMode.SideMenu.width)
    }

    @Test fun patternRulesMatchTheDesignTable() {
        val c = BpmWindowClass.Compact; val m = BpmWindowClass.Medium; val e = BpmWindowClass.Expanded; val l = BpmWindowClass.Large
        assertEquals(2, BpmPatternRules.indicatorColumns(c)); assertEquals(4, BpmPatternRules.indicatorColumns(m))
        assertEquals(listOf(1, 2, null, 3), listOf(c, m, e, l).map { BpmPatternRules.gridColumns(it) })
        assertEquals(listOf(false, false, true, true), listOf(c, m, e, l).map { BpmPatternRules.detailBesideList(it) })
        assertEquals(listOf(false, false, false, true), listOf(c, m, e, l).map { BpmPatternRules.panelSplit(it) })
        assertEquals(listOf(false, true, true, true), listOf(c, m, e, l).map { BpmPatternRules.formTwoColumns(it) })
        assertEquals(listOf(false, false, false, true), listOf(c, m, e, l).map { BpmPatternRules.formPreview(it) })
        assertEquals(720.dp, BpmPatternRules.contentMax(BpmPattern.StepFlow, m))
        assertEquals(440.dp, BpmPatternRules.contentMax(BpmPattern.Access, e))
        assertEquals(1200.dp, BpmPatternRules.contentMax(BpmPattern.Panel, l))
    }
}
