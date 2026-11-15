package pe.bpm.features.accounts

import androidx.lifecycle.viewModelScope
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.accounts.data.RemoteAccountsRepository
import pe.bpm.features.accounts.domain.*
import pe.bpm.features.accounts.presentation.*

/** Panel de invitación: enlace o código, mensaje de WhatsApp codificado y nada guardado tras cerrarlo. */
@OptIn(ExperimentalCoroutinesApi::class)
class InvitationShareTest {
    private fun viewModel(repo: FakeAccountsRepository): InvitationsViewModel {
        var n = 0
        return InvitationsViewModel(ListInvitations(repo), LoadActiveLocations(repo), InviteMember(repo), ResendInvitation(repo), RevokeInvitation(repo),
            { "id-${++n}" }, { Instant.parse("2026-11-10T12:05:00Z") })
    }

    private fun scenario(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }

    @Test fun inviteShowsThePanelWithTheAcceptUrl() = scenario {
        val repo = FakeAccountsRepository()
        val vm = viewModel(repo)
        vm.open("o1", true, "Pollería Lima"); advanceUntilIdle()
        vm.openForm(); vm.editEmail("ana@example.com"); vm.toggleLocation("l1"); vm.submit(); advanceUntilIdle()
        val share = assertNotNull(vm.state.value.share)
        assertEquals(InvitationShare.Link(ACCEPT_URL), share.share)
        assertEquals("i1@example.com", share.email)
        assertFalse(share.resent)
        assertEquals("Pollería Lima", vm.state.value.organizationName)
        // Cerrar el panel descarta el enlace de la memoria.
        vm.closeShare()
        assertNull(vm.state.value.share)
        assertFalse(vm.state.value.toString().contains(TOKEN))
        vm.viewModelScope.cancel()
    }

    @Test fun inviteShowsTheCodeWhenTheServerHasNoAcceptUrl() = scenario {
        val repo = FakeAccountsRepository().apply { invite = AccountsResult.Success(shared(InvitationShare.Code(TOKEN))) }
        val vm = viewModel(repo)
        vm.open("o1", true, "BPM"); advanceUntilIdle()
        vm.openForm(); vm.editEmail("ana@example.com"); vm.toggleLocation("l1"); vm.submit(); advanceUntilIdle()
        assertEquals(InvitationShare.Code(TOKEN), vm.state.value.share?.share)
        assertEquals(TOKEN, vm.state.value.share?.share?.value)
        vm.viewModelScope.cancel()
    }

    @Test fun resendShowsANewPanelAndThePanelNeverAppearsInTheList() = scenario {
        val repo = FakeAccountsRepository().apply { resend = AccountsResult.Success(shared(InvitationShare.Link(ACCEPT_URL.replace("AbCd", "ZzZz")))) }
        val vm = viewModel(repo)
        vm.open("o1", true, "BPM"); advanceUntilIdle()
        vm.resend(vm.state.value.page.items.single()); advanceUntilIdle()
        val share = assertNotNull(vm.state.value.share)
        assertTrue(share.resent)
        assertTrue(share.share.value.contains("ZzZz"))
        // La lista se vuelve a leer y sus filas no tienen enlace ni código.
        assertTrue(vm.state.value.page.items.none { it.toString().contains("token=") })
        vm.viewModelScope.cancel()
    }

    @Test fun anotherActionClearsThePanel() = scenario {
        val repo = FakeAccountsRepository()
        val vm = viewModel(repo)
        vm.open("o1", true, "BPM"); advanceUntilIdle()
        vm.openForm(); vm.editEmail("ana@example.com"); vm.toggleLocation("l1"); vm.submit(); advanceUntilIdle()
        assertNotNull(vm.state.value.share)
        vm.askRevoke(vm.state.value.page.items.single())
        assertNull(vm.state.value.share)
        vm.viewModelScope.cancel()
    }

    @Test fun whatsappMessageIsShortInSpanishAndFullyEncodedInTheUrl() {
        val message = InvitationSharing.message("Pollería Lima", "ana@example.com", InvitationShare.Link(ACCEPT_URL))
        assertEquals("Te invito a unirte a Pollería Lima en BPM. Crea tu cuenta con ana@example.com y abre: $ACCEPT_URL", message)
        val url = InvitationSharing.whatsappUrl(message)
        assertTrue(url.startsWith("https://wa.me/?text="))
        val encoded = url.removePrefix("https://wa.me/?text=")
        // Solo caracteres no reservados o escapes en porcentaje: espacios, acentos, @, :, / y ? quedan escapados.
        assertTrue(Regex("[A-Za-z0-9._~%-]*").matches(encoded), encoded)
        assertTrue(encoded.startsWith("Te%20invito%20a%20unirte%20a%20Poller%C3%ADa%20Lima%20en%20BPM."))
        assertTrue(encoded.contains("ana%40example.com"))
        assertTrue(encoded.contains("https%3A%2F%2Fapp.bpm.example%2Fcuenta%2Finvitacion%3Ftoken%3D$TOKEN"))
        assertFalse(encoded.contains(' ') || encoded.contains('&') || encoded.contains('#'))
    }

    @Test fun codeMessageExplainsWhereToPasteIt() {
        val message = InvitationSharing.message(" ", "ana@example.com", InvitationShare.Code(TOKEN))
        assertTrue(message.startsWith("Te invito a unirte a nuestra empresa en BPM."))
        assertTrue(message.contains("ana@example.com"))
        assertTrue(message.contains("Aceptar una invitación"))
        assertTrue(message.endsWith(TOKEN))
    }

    @Test fun noticeNamesTheEmailTheLifetimeAndSingleUse() {
        assertEquals("El enlace solo funciona para la cuenta con el correo ana@example.com, vale 7 días y se usa una vez", InvitationSharing.notice("ana@example.com"))
    }

    // ---- Traducción de la respuesta del servidor -------------------------------------------------------------

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val base = """"id":"i1","email":"a@example.com","role":"WORKER","locationIds":["l1"],"status":"PENDING","expiresAt":"$STAMP","lastSentAt":"$STAMP","createdAt":"$STAMP","sendCount":1,"rowVersion":1"""
    private fun repo(engine: MockEngine) = RemoteAccountsRepository(createHttpClient(engine), AppConfig("https://api.example.com"), token = { "token" })

    @Test fun inviteAndResendReadTheAcceptUrlOrTheTokenAndNothingElse() = runTest {
        var payload = """{"data":{$base,"acceptUrl":"$ACCEPT_URL"}}"""
        val repository = repo(MockEngine { respond(payload, HttpStatusCode.Created, json) })
        val created = (repository.invite("o1", "i1", "a@example.com", MemberRole.WORKER, listOf("l1")) as AccountsResult.Success).value
        assertEquals(InvitationShare.Link(ACCEPT_URL), created.share)
        assertEquals("i1", created.invitation.id)
        payload = """{"data":{$base,"acceptUrl":null,"token":"$TOKEN"}}"""
        val resent = (repository.resendInvitation("o1", "i1") as AccountsResult.Success).value
        assertEquals(InvitationShare.Code(TOKEN), resent.share)
    }

    @Test fun malformedLinksOrMissingLinkAreRejected() = runTest {
        for (body in listOf(
            """"acceptUrl":"javascript:alert(1)"""", """"acceptUrl":"http://evil.example/cuenta/invitacion?token=$TOKEN"""",
            """"acceptUrl":"https://u:p@app.example/cuenta/invitacion?token=$TOKEN"""", """"acceptUrl":"https://app.example/cuenta/invitacion?token=corto"""",
            """"acceptUrl":null,"token":"corto"""", """"acceptUrl":null""",
        )) {
            val repository = repo(MockEngine { respond("""{"data":{$base,$body}}""", HttpStatusCode.Created, json) })
            val result = repository.invite("o1", "i1", "a@example.com", MemberRole.WORKER, listOf("l1"))
            assertEquals(AccountFailure.INVALID_RESPONSE, (result as AccountsResult.Failure).reason, body)
        }
    }

    @Test fun listedInvitationsIgnoreAnyLinkOrToken() = runTest {
        val repository = repo(MockEngine { respond("""{"data":[{$base,"acceptUrl":"$ACCEPT_URL","token":"$TOKEN"}],"pagination":{"nextPageToken":""}}""", HttpStatusCode.OK, json) })
        val page = (repository.invitations("o1", null, pe.bpm.core.domain.PageRequest()) as AccountsResult.Success).value
        assertEquals("i1", page.items.single().id)
        assertFalse(page.items.single().toString().contains(TOKEN))
    }
}
