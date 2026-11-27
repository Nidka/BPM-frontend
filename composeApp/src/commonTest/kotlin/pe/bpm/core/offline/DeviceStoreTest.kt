package pe.bpm.core.offline

import kotlinx.coroutines.test.runTest
import pe.bpm.core.storage.TestDraftStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Las marcas del dispositivo sobreviven al cierre de sesión y al cambio de cuenta; los datos de la cuenta no. */
class DeviceStoreTest {
    @Test fun deviceMarksSurviveLogoutAndAccountSwitch() = runTest {
        val db = InMemoryOfflineDatabase()
        val runtime = OfflineRuntime(OfflinePlatform(database = db), { 1_000L }, { "op" })
        runtime.activate(OfflineScope("https://api.test", "ana"))
        runtime.store.put("preferences", "onboarding", "{}")
        runtime.device.put("onboarding-seen", "ana", "true")
        runtime.wipe()
        assertEquals("true", runtime.device.get("onboarding-seen", "ana"))
        runtime.activate(OfflineScope("https://api.test", "beto"))
        assertEquals("true", runtime.device.get("onboarding-seen", "ana"))
        assertTrue(runtime.otherAccountsPending().isEmpty())
        runtime.activate(OfflineScope("https://api.test", "ana"))
        assertNull(runtime.store.get("preferences", "onboarding"))
    }

    @Test fun webDocumentsUseThePrefixThatLogoutKeeps() = runTest {
        val storage = TestDraftStorage()
        DeviceStore(DocumentOfflineDatabase(storage)) { 1L }.put("onboarding-seen", "ana", "true")
        assertTrue(storage.values.keys.any { it.startsWith(DeviceStore.DOCUMENT_PREFIX) })
    }
}
