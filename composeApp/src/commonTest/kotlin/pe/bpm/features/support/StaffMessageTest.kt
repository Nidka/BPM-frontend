package pe.bpm.features.support

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.features.support.domain.*
import pe.bpm.features.support.presentation.StaffMessageViewModel
import kotlin.test.*

class StaffMessageTest {
    private class Repo(var fail: Boolean = true) : TeamRepository {
        val sent = mutableListOf<Pair<String, String>>()
        override suspend fun sendStaffMessage(org: String, id: String, locationId: String, kind: StaffMessageKind, text: String, key: String): SupportResult<Unit> {
            sent += id to key
            return if (fail) SupportResult.Failure(SupportFailure.CONNECTION) else SupportResult.Success(Unit)
        }
        override suspend fun supervisorContact(org: String, locationId: String) = SupportResult.Success<SupervisorContact?>(SupervisorContact("Ana Pérez", "CHEF", "51987654321"))
        override suspend fun saveMyWhatsapp(raw: String?) = SupportResult.Success<String?>(raw)
    }

    @Test fun retryKeepsTheSameIdAndKeySoTheBossGetsOneAlert() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repo()
        var n = 0
        val vm = StaffMessageViewModel(repo) { "id-${++n}" }
        try {
            vm.open("org", "site"); advanceUntilIdle()
            assertEquals("51987654321", vm.state.value.contact?.whatsapp)
            vm.send(); assertEquals("Elige qué pasa.", vm.state.value.error)
            vm.pick(StaffMessageKind.SUPPLY); vm.send(); advanceUntilIdle()
            assertTrue(vm.state.value.error!!.startsWith("Sin conexión"))
            repo.fail = false; vm.send(); advanceUntilIdle()
            assertTrue(vm.state.value.sent)
            assertEquals(listOf("id-1" to "staff-id-1", "id-1" to "staff-id-1"), repo.sent)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun whatsappTextAndMyNumberRules() {
        assertEquals("Hola Ana, soy Luis (Centro). Falta insumo. Se acabó el aceite",
            StaffMessageRules.whatsappText("Ana Pérez", "Luis", "Centro", StaffMessageKind.SUPPLY, "Se acabó el aceite"))
        assertNull(StaffMessageRules.myWhatsappError("987 654 321"))
        assertNull(StaffMessageRules.myWhatsappError(""))
        assertNotNull(StaffMessageRules.myWhatsappError("12"))
        assertNotNull(StaffMessageRules.myWhatsappError("98765abc"))
    }
}
