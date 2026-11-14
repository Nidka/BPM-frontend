package pe.bpm.core.offline

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CachedResourceTest {
    @Serializable private data class Catalog(val names: List<String>)

    @Test fun emitsLocalDataImmediatelyThenRefreshesFromNetwork() = runTest {
        val h = OfflineHarness(); h.login()
        h.runtime.store.putTyped("catalog", "products", Catalog(listOf("viejo")), Catalog.serializer())
        val cached = CachedResource(h.runtime.store, h.connectivity)
        val flow = cached.observe("catalog", "products", Catalog.serializer()) { delay(100); Catalog(listOf("nuevo")) }
        // La primera emisión ya trae lo local, antes de terminar la red.
        assertEquals(listOf("viejo"), flow.first().value?.names)
        assertEquals(listOf("nuevo"), flow.first { it.value?.names == listOf("nuevo") }.value?.names)
    }

    @Test fun withoutNetworkOnlyLocalDataIsShownWithItsSyncTime() = runTest {
        val h = OfflineHarness(connectivity = ManualConnectivityMonitor(false)); h.login()
        h.runtime.store.putTyped("catalog", "products", Catalog(listOf("guardado")), Catalog.serializer())
        var fetched = false
        val first = CachedResource(h.runtime.store, h.connectivity).observe("catalog", "products", Catalog.serializer()) { fetched = true; null }.first()
        assertEquals(listOf("guardado"), first.value?.names)
        assertEquals(h.now, first.syncedAt)
        assertTrue(!fetched && !first.refreshing)
    }

    @Test fun failedRefreshKeepsLocalDataAndFlagsIt() = runTest {
        val h = OfflineHarness(); h.login()
        h.runtime.store.putTyped("catalog", "k", Catalog(listOf("local")), Catalog.serializer())
        val failed = CachedResource(h.runtime.store, h.connectivity).observe("catalog", "k", Catalog.serializer()) { error("5xx") }.first { it.refreshFailed }
        assertEquals(listOf("local"), failed.value?.names)
    }

    @Test fun nothingIsShownWithoutAnActiveAccountAndRelativeAgeReadsNaturally() = runTest {
        val h = OfflineHarness()
        assertNull(h.runtime.store.get("catalog", "k"))
        assertEquals("hace unos segundos", relativeAge(100_000, 90_000))
        assertEquals("hace 5 min", relativeAge(1_000_000, 700_000))
        assertEquals("hace 2 h", relativeAge(8_000_000, 600_000))
        assertEquals("hace 3 días", relativeAge(300_000_000, 10_000))
    }
}
