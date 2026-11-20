package pe.bpm.features.workspace

import pe.bpm.core.designsystem.BpmTone
import pe.bpm.features.workspace.domain.Entry
import pe.bpm.features.workspace.presentation.CatalogFilter
import pe.bpm.features.workspace.presentation.catalogStatusChip
import pe.bpm.features.workspace.presentation.catalogSummary
import pe.bpm.features.workspace.presentation.visibleCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CourseCatalogTest {
    private val entries = listOf(
        Entry("A1", "Uno", "", "NOT_STARTED"), Entry("b2", "Dos", "", "IN_PROGRESS"),
        Entry("c3", "Tres", "", "COMPLETED"), Entry("d4", "Cuatro", "", "PUBLISHED"),
    )

    @Test fun allShowsEverythingExceptAssignedCourses() {
        assertEquals(listOf("A1", "b2", "c3", "d4"), visibleCatalog(entries, CatalogFilter.All).map { it.id })
        assertEquals(listOf("b2", "c3", "d4"), visibleCatalog(entries, CatalogFilter.All, setOf("a1")).map { it.id })
    }

    @Test fun statusFiltersKeepOnlyMatchingCourses() {
        assertEquals(listOf("A1"), visibleCatalog(entries, CatalogFilter.NotStarted).map { it.id })
        assertEquals(listOf("b2"), visibleCatalog(entries, CatalogFilter.InProgress).map { it.id })
        assertEquals(listOf("c3"), visibleCatalog(entries, CatalogFilter.Completed).map { it.id })
        assertEquals(emptyList(), visibleCatalog(entries, CatalogFilter.Completed, setOf("C3")))
    }

    @Test fun statusChipOnlyForLearnerProgress() {
        assertEquals("Sin iniciar" to BpmTone.Neutral, catalogStatusChip("NOT_STARTED"))
        assertEquals("En curso" to BpmTone.Info, catalogStatusChip("IN_PROGRESS"))
        assertEquals("Completado" to BpmTone.Success, catalogStatusChip("COMPLETED"))
        assertNull(catalogStatusChip("PUBLISHED"))
    }

    @Test fun summaryFlattensAndTruncates() {
        assertEquals("Hola mundo", catalogSummary("  Hola\n  mundo "))
        assertEquals("", catalogSummary(""))
        val long = catalogSummary("x".repeat(300))
        assertEquals(110, long.length)
        assertEquals('…', long.last())
    }
}
