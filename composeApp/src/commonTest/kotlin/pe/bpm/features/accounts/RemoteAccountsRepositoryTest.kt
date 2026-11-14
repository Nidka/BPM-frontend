package pe.bpm.features.accounts

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.accounts.data.RemoteAccountsRepository
import pe.bpm.features.accounts.domain.*

class RemoteAccountsRepositoryTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val api = "/api/v1"
    private val memberJson = """{"id":"u1","name":"Ana","email":"a@example.com","role":"CHEF","locationIds":["l1"],"status":"ACTIVE","rowVersion":4,"secret":"x"}"""

    private fun repo(engine: MockEngine) = RemoteAccountsRepository(createHttpClient(engine), AppConfig("https://api.example.com"), token = { "token" })

    @Test fun membersSendStatusAndTranslateRowVersion() = runTest {
        val repository = repo(MockEngine { request ->
            assertEquals("$api/organizations/o1/members", request.url.encodedPath)
            assertEquals("INACTIVE", request.url.parameters["status"])
            assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
            respond("""{"data":[$memberJson],"pagination":{"nextPageToken":"abc"}}""", HttpStatusCode.OK, json)
        })
        val page = (repository.members("o1", RecordStatus.INACTIVE, PageRequest()) as AccountsResult.Success).value
        assertEquals(4, page.items.single().rowVersion)
        assertEquals(MemberRole.CHEF, page.items.single().role)
        assertEquals("abc", page.nextToken)
    }

    @Test fun memberPatchSendsTheReadVersionAndNothingElse() = runTest {
        var body = ""
        val repository = repo(MockEngine { request ->
            assertEquals(HttpMethod.Patch, request.method)
            assertEquals("$api/organizations/o1/members/u1", request.url.encodedPath)
            body = (request.body as TextContent).text
            respond("""{"data":$memberJson}""", HttpStatusCode.OK, json)
        })
        repository.updateMember("o1", "u1", MemberRole.CHEF, listOf("l1"), 3)
        assertEquals("""{"role":"CHEF","locationIds":["l1"],"rowVersion":3}""", body)
    }

    @Test fun transferOmitsActorFieldsForASuperAdminAndSendsThemForAnAdmin() = runTest {
        val bodies = mutableListOf<String>()
        val response = """{"data":{"newAdmin":${memberJson.replace("CHEF", "ADMIN")},"formerAdmin":null}}"""
        val repository = repo(MockEngine { request -> bodies += (request.body as TextContent).text; respond(response, HttpStatusCode.OK, json) })
        repository.transferAdmin("o1", "u1", 4, null, emptyList())
        repository.transferAdmin("o1", "u1", 4, MemberRole.WORKER, listOf("l1"))
        assertEquals("""{"rowVersion":4}""", bodies[0])
        assertEquals("""{"rowVersion":4,"actorRole":"WORKER","actorLocationIds":["l1"]}""", bodies[1])
    }

    @Test fun organizationUsesTheWorkspaceReadAndAcceptsTheLegacyBusinessType() = runTest {
        val repository = repo(MockEngine { request ->
            assertEquals("$api/organizations/o1/workspace", request.url.encodedPath)
            respond("""{"data":{"organization":{"id":"o1","legalName":"BPM","businessType":"RELATED_SERVICE","rowVersion":2},"locations":[]}}""", HttpStatusCode.OK, json)
        })
        val organization = (repository.organization("o1") as AccountsResult.Success).value
        assertEquals(BusinessType.OTHER_FOOD_SERVICE, organization.businessType)
        assertEquals(2, organization.rowVersion)
    }

    @Test fun domainErrorCodesMapToPublicReasons() = runTest {
        suspend fun reason(status: Int, code: String): AccountFailure {
            val repository = repo(MockEngine { respond("""{"errors":[{"code":"$code","message":"SQL private"}]}""", HttpStatusCode.fromValue(status), json) })
            return (repository.deactivateMember("o1", "u1", 1) as AccountsResult.Failure).reason
        }
        assertEquals(AccountFailure.VERSION_CONFLICT, reason(409, "VERSION_CONFLICT"))
        assertEquals(AccountFailure.LAST_ADMIN, reason(409, "LAST_ADMIN_REQUIRED"))
        assertEquals(AccountFailure.MEMBER_INACTIVE, reason(409, "MEMBER_INACTIVE"))
        assertEquals(AccountFailure.RESEND_TOO_SOON, reason(429, "INVITATION_RESEND_TOO_SOON"))
        assertEquals(AccountFailure.EMAIL_MISMATCH, reason(403, "INVITATION_EMAIL_MISMATCH"))
        assertEquals(AccountFailure.INVITATION_EXPIRED, reason(409, "INVITATION_EXPIRED"))
        assertEquals(AccountFailure.INVITATION_USED, reason(409, "INVITATION_ALREADY_USED"))
        assertEquals(AccountFailure.INVITATION_REVOKED, reason(409, "INVITATION_REVOKED"))
        assertEquals(AccountFailure.INVITATION_NOT_FOUND, reason(404, "INVITATION_NOT_FOUND"))
        assertEquals(AccountFailure.CONFLICT, reason(409, "OTRO"))
        assertEquals(AccountFailure.DENIED, reason(403, "ACCESS_DENIED"))
        assertEquals(AccountFailure.UNAVAILABLE, reason(503, "BACKEND_UNAVAILABLE"))
    }

    @Test fun acceptSendsTheTokenOnlyInTheBodyAndRejectsMalformedOnes() = runTest {
        var seen = 0
        val repository = repo(MockEngine { request ->
            seen++
            assertEquals("$api/invitations/accept", request.url.encodedPath)
            assertFalse(request.url.toString().contains(TOKEN))
            assertEquals("""{"token":"$TOKEN"}""", (request.body as TextContent).text)
            respond("""{"data":{"organizationId":"o1","role":"WORKER","locationIds":["l1"]}}""", HttpStatusCode.OK, json)
        })
        val accepted = (repository.acceptInvitation(TOKEN) as AccountsResult.Success).value
        assertEquals(MemberRole.WORKER, accepted.role)
        assertEquals(AccountFailure.INVALID_RESPONSE, (repository.acceptInvitation("corto") as AccountsResult.Failure).reason)
        assertEquals(1, seen)
    }

    @Test fun invitationsSendTheStatusFilterAndRejectInvalidDates() = runTest {
        val valid = """{"id":"i1","email":"a@example.com","role":"WORKER","locationIds":[],"status":"EXPIRED","expiresAt":"$STAMP","lastSentAt":"$STAMP","createdAt":"$STAMP","sendCount":2,"rowVersion":1,"token":"no"}"""
        val repository = repo(MockEngine { request ->
            assertEquals("EXPIRED", request.url.parameters["status"])
            respond("""{"data":[$valid]}""", HttpStatusCode.OK, json)
        })
        val page = (repository.invitations("o1", InvitationStatus.EXPIRED, PageRequest()) as AccountsResult.Success).value
        assertEquals(InvitationStatus.EXPIRED, page.items.single().status)
        val broken = repo(MockEngine { respond("""{"data":[${valid.replace("\"expiresAt\":\"$STAMP\"", "\"expiresAt\":\"mañana\"")}]}""", HttpStatusCode.OK, json) })
        assertEquals(AccountFailure.INVALID_RESPONSE, (broken.invitations("o1", null, PageRequest()) as AccountsResult.Failure).reason)
    }

    @Test fun devicesListWithoutFiltersAndRevokeIsADelete() = runTest {
        val device = """{"id":"d1","organizationId":"o1","organizationName":"BPM","platform":"ANDROID","status":"ACTIVE","lastSeenAt":null,"createdAt":"$STAMP"}"""
        val repository = repo(MockEngine { request ->
            when (request.method) {
                HttpMethod.Get -> { assertEquals("$api/me/devices", request.url.encodedPath); assertNull(request.url.parameters["query"]); respond("""{"data":[$device]}""", HttpStatusCode.OK, json) }
                else -> { assertEquals(HttpMethod.Delete, request.method); assertEquals("$api/me/devices/d1", request.url.encodedPath); respond("""{"data":${device.replace("ACTIVE", "INACTIVE")}}""", HttpStatusCode.OK, json) }
            }
        })
        assertEquals(DevicePlatform.ANDROID, (repository.devices(PageRequest()) as AccountsResult.Success).value.items.single().platform)
        assertEquals(RecordStatus.INACTIVE, (repository.revokeDevice("d1") as AccountsResult.Success).value.status)
    }

    @Test fun identifiersWithPathCharactersAreRejectedBeforeAnyRequest() = runTest {
        var seen = 0
        val repository = repo(MockEngine { seen++; respond("{}", HttpStatusCode.OK, json) })
        assertEquals(AccountFailure.INVALID_RESPONSE, (repository.revokeDevice("../x") as AccountsResult.Failure).reason)
        assertEquals(0, seen)
    }
}
