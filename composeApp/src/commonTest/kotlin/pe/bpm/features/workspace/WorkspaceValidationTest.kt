package pe.bpm.features.workspace

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.workspace.data.RemoteWorkspaceRepository
import pe.bpm.features.workspace.domain.*
import pe.bpm.features.workspace.domain.Result
import kotlin.test.*

class WorkspaceValidationTest {
    @Test fun invalidFieldsNeverSendRequestsAndValidBusinessNamesCanContainNumbers() = runTest {
        var calls = 0
        val client = createHttpClient(MockEngine {
            calls++
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val repo = RemoteWorkspaceRepository(client, AppConfig("https://api.example.com")) { "test.token" }
            val create = CreateOrganization(repo)
            val base = Organization("id", "Restaurante 24 S.A.C.", contactName = "María Pérez", contactPhone = "+51 987 654 321")
            val error = assertIs<Result.Failure>(create(base.copy(contactName = "María2", contactPhone = "hola", contactEmail = "bad")))
            assertEquals(setOf("contactName", "contactPhone", "contactEmail"), error.errors.single().fields.keys)
            assertIs<Result.Failure>(CreateLocation(repo)("org", Location("id", "", "x", contactPhone = "123")))
            assertIs<Result.Failure>(AddMember(repo)("org", "chef@example.com", "CHEF", emptyList()))
            assertEquals(0, calls)
            assertIs<Result.Success<Unit>>(create(base))
            assertIs<Result.Success<Unit>>(CreateLocation(repo)("org", Location("id", "Sede 2", "Av. Perú 123")))
            assertEquals(2, calls)
        } finally { client.close() }
    }
}
