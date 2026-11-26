package pe.bpm.features.accounts

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.accounts.data.RemoteInvitationPreview
import pe.bpm.features.accounts.domain.MemberRole

class InvitationPreviewTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val token = "A".repeat(43)
    private fun loader(engine: MockEngine) = RemoteInvitationPreview(createHttpClient(engine), AppConfig("https://api.example.com"))

    @Test fun readsCompanyRoleAndEmailWithoutSendingCredentials() = runTest {
        val preview = loader(MockEngine { request ->
            assertEquals("/api/v1/invitations/preview", request.url.encodedPath)
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("""{"data":{"organizationName":"Restaurante Demo","role":"CHEF","email":"ana@yopmail.com","expiresAt":"2026-12-03T10:00:00Z","status":"PENDING"}}""", HttpStatusCode.OK, json)
        }).preview(token)
        assertEquals("Restaurante Demo", preview?.organizationName)
        assertEquals(MemberRole.CHEF, preview?.role)
        assertEquals("ana@yopmail.com", preview?.email)
    }

    @Test fun anythingOddFallsBackToTheGenericNotice() = runTest {
        assertNull(loader(MockEngine { respond("{}", HttpStatusCode.OK, json) }).preview("corto"))
        assertNull(loader(MockEngine { respond("""{"errors":[{"code":"INVITATION_EXPIRED"}]}""", HttpStatusCode.Conflict, json) }).preview(token))
        assertNull(loader(MockEngine { respond("""{"data":{"organizationName":"X","role":"OTRO","email":"a@b.co"}}""", HttpStatusCode.OK, json) }).preview(token))
    }
}
