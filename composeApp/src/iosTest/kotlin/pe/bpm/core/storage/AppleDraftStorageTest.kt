package pe.bpm.core.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AppleDraftStorageTest {
    @OptIn(ExperimentalUuidApi::class)
    @Test fun protectedDocumentSurvivesAdapterRecreation() = runTest {
        val key = draftKey("https://draft-test.invalid", Uuid.random().toString(), "test-org", "test-document")
        val first = AppleDraftStorage()
        try {
            assertNull(first.read(key))
            first.write(key, "{\"observación\":\"Revisión pendiente\"}")
            assertEquals("{\"observación\":\"Revisión pendiente\"}", AppleDraftStorage().read(key))
            first.remove(key)
            assertNull(AppleDraftStorage().read(key))
        } finally { first.remove(key) }
    }
}
