package pe.bpm.features.identity

import androidx.lifecycle.ViewModelStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.identity.data.FirebaseEmailActionDataSource
import pe.bpm.features.identity.data.emailActionFailure
import pe.bpm.features.identity.domain.*
import pe.bpm.features.identity.presentation.EmailActionState
import pe.bpm.features.identity.presentation.EmailActionTexts
import pe.bpm.features.identity.presentation.EmailActionViewModel
import kotlin.test.*

class EmailActionLinkTest {
    @Test fun readsTheParametersFirebasePutsInTheEmail() {
        val request = assertNotNull(EmailActionLink.parse(
            "?mode=verifyEmail&oobCode=AbC_123-xyz&apiKey=AIzaSyFake&continueUrl=https%3A%2F%2Fappbpm.com%2F&lang=es"))
        assertEquals(EmailActionMode.VerifyEmail, request.mode)
        assertEquals("AbC_123-xyz", request.oobCode)
        assertEquals("https://appbpm.com/", request.continueUrl)
        assertEquals("es", request.lang)
    }

    @Test fun supportsEveryModeAndTheHashForm() {
        assertEquals(EmailActionMode.ResetPassword, EmailActionLink.parse("?mode=resetPassword&oobCode=x")?.mode)
        assertEquals(EmailActionMode.RecoverEmail, EmailActionLink.parse("?mode=recoverEmail&oobCode=x")?.mode)
        val hashed = assertNotNull(EmailActionLink.parse("", "#/auth/accion?mode=verifyEmail&oobCode=abc"))
        assertEquals(EmailActionMode.VerifyEmail, hashed.mode)
    }

    @Test fun rejectsIncompleteOrUnsupportedLinks() {
        assertNull(EmailActionLink.parse(""))
        assertNull(EmailActionLink.parse("?mode=verifyEmail"))
        assertNull(EmailActionLink.parse("?oobCode=abc"))
        assertNull(EmailActionLink.parse("?mode=signIn&oobCode=abc"))
        assertNull(EmailActionLink.parse("?mode=verifyEmail&oobCode="))
        assertNull(EmailActionLink.parse("?mode=verifyEmail&oobCode=<script>"))
        assertNull(EmailActionLink.parse("?mode=verifyEmail&oobCode=" + "a".repeat(513)))
    }

    @Test fun firstValueWinsAndBadEscapesAreIgnored() {
        assertEquals("one", EmailActionLink.parse("?mode=verifyEmail&oobCode=one&oobCode=two")?.oobCode)
        assertNull(EmailActionLink.parse("?mode=verifyEmail&oobCode=%zz"))
    }

    @Test fun invalidCodeFromTheLiveCheckIsAccepted() {
        // El servidor de Firebase es quien rechaza el código; la página muestra su error de enlace vencido.
        assertNotNull(EmailActionLink.parse("?mode=verifyEmail&oobCode=invalid"))
    }

    @Test fun continueUrlOnlyPointsToBpm() {
        assertEquals("https://appbpm.com/x?y=1", EmailActionLink.safeContinueUrl("https://appbpm.com/x?y=1"))
        assertEquals("https://www.appbpm.com", EmailActionLink.safeContinueUrl("https://www.appbpm.com"))
        assertEquals("http://localhost:3000/", EmailActionLink.safeContinueUrl("http://localhost:3000/"))
        for (bad in listOf("https://evil.example/", "https://appbpm.com.evil.example/", "http://appbpm.com/", "https://appbpm.com@evil.example/",
            "//evil.example", "javascript:alert(1)", "https://evil.example\\@appbpm.com", "", "   ", "https://appbpm.com/\u0000a"))
            assertNull(EmailActionLink.safeContinueUrl(bad), bad)
        assertNull(EmailActionLink.parse("?mode=verifyEmail&oobCode=abc&continueUrl=https%3A%2F%2Fevil.example")?.continueUrl)
    }

    @Test fun routeMatchesPathAndHash() {
        assertTrue(EmailActionLink.isRoute("/auth/accion"))
        assertTrue(EmailActionLink.isRoute("/auth/accion/"))
        assertTrue(EmailActionLink.isRoute("/", "#/auth/accion?mode=x"))
        assertTrue(EmailActionLink.isRoute("/", "#auth/accion"))
        assertFalse(EmailActionLink.isRoute("/"))
        assertFalse(EmailActionLink.isRoute("/auth/google.mjs"))
        assertFalse(EmailActionLink.isRoute("/auth/accion-falsa"))
    }

    @Test fun loginDestinationCarriesOnlyAValidEmail() {
        assertEquals("/", EmailActionLink.loginDestination(null))
        assertEquals("/", EmailActionLink.loginDestination("no es un correo"))
        assertEquals("/?correo=ana%2Btest%40ejemplo.com", EmailActionLink.loginDestination("ana+test@ejemplo.com"))
        assertEquals("ana+test@ejemplo.com", EmailActionLink.prefilledEmail("?correo=ana%2Btest%40ejemplo.com"))
        assertNull(EmailActionLink.prefilledEmail("?correo=<x>"))
        assertNull(EmailActionLink.prefilledEmail(""))
    }

    @Test fun newPasswordRulesAreVisibleAndStrict() {
        assertEquals(listOf(false, false), NewPasswordRules.checks("", "").map { it.met })
        assertEquals(listOf(true, false), NewPasswordRules.checks("password8", "otra").map { it.met })
        assertEquals(listOf(true, true), NewPasswordRules.checks("password8", "password8").map { it.met })
        assertNotNull(NewPasswordRules.error("corta", "corta"))
        assertNotNull(NewPasswordRules.error("password8", "password9"))
        assertNull(NewPasswordRules.error("password8", "password8"))
    }

    @Test fun everyFailureHasPlainSpanishCopyWithoutProviderCodes() {
        for (mode in EmailActionMode.entries) for (failure in EmailActionFailure.entries) {
            val copy = EmailActionTexts.failure(mode, failure)
            assertTrue(copy.title.isNotBlank() && copy.message.isNotBlank())
            assertFalse(copy.message.contains("OOB") || copy.message.contains("Firebase"))
        }
        assertTrue(EmailActionTexts.failure(EmailActionMode.VerifyEmail, EmailActionFailure.InvalidOrExpired).message.contains("inicia sesión"))
    }
}

private class FakeEmailActions : EmailActionRepository {
    var verify: EmailActionResult<String?> = EmailActionResult.Success("ana@ejemplo.com")
    var check: EmailActionResult<String> = EmailActionResult.Success("ana@ejemplo.com")
    var confirm: EmailActionResult<Unit> = EmailActionResult.Success(Unit)
    var recover: EmailActionResult<String?> = EmailActionResult.Success("ana@ejemplo.com")
    val calls = mutableListOf<String>()
    override suspend fun verifyEmail(oobCode: String) = verify.also { calls += "verify:$oobCode" }
    override suspend fun checkPasswordResetCode(oobCode: String) = check.also { calls += "check:$oobCode" }
    override suspend fun confirmPasswordReset(oobCode: String, newPassword: String) = confirm.also { calls += "confirm:$oobCode:$newPassword" }
    override suspend fun recoverEmail(oobCode: String) = recover.also { calls += "recover:$oobCode" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EmailActionViewModelTest {
    private fun model(repository: FakeEmailActions, mode: EmailActionMode?, store: ViewModelStore) = EmailActionViewModel(
        mode?.let { EmailActionRequest(it, "code123") }, VerifyEmailLink(repository), CheckPasswordResetLink(repository),
        ConfirmPasswordReset(repository), RecoverEmailLink(repository)).also { store.put("m", it) }

    private fun runModel(block: suspend TestScope.(ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try { block(store) } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun missingRequestShowsTheInvalidLinkStateAndNeverCallsFirebase() = runModel { store ->
        val repository = FakeEmailActions()
        val m = model(repository, null, store)
        m.start(); runCurrent()
        assertEquals(EmailActionState.InvalidLink, m.state.value)
        assertTrue(repository.calls.isEmpty())
    }

    @Test fun verifyEmailGoesFromWorkingToVerifiedAndAppliesTheCodeOnce() = runModel { store ->
        val repository = FakeEmailActions()
        val m = model(repository, EmailActionMode.VerifyEmail, store)
        assertEquals(EmailActionState.Working(EmailActionMode.VerifyEmail), m.state.value)
        m.start(); m.start(); runCurrent()
        assertEquals(EmailActionState.EmailVerified("ana@ejemplo.com"), m.state.value)
        assertEquals(listOf("verify:code123"), repository.calls)
    }

    @Test fun expiredVerificationLinkIsAFinalFailureThatCannotBeRetried() = runModel { store ->
        val repository = FakeEmailActions().apply { verify = EmailActionResult.Failure(EmailActionFailure.InvalidOrExpired) }
        val m = model(repository, EmailActionMode.VerifyEmail, store)
        m.start(); runCurrent()
        assertEquals(EmailActionState.Failed(EmailActionMode.VerifyEmail, EmailActionFailure.InvalidOrExpired), m.state.value)
        m.retry(); runCurrent()
        assertEquals(1, repository.calls.size)
    }

    @Test fun connectionFailureCanBeRetried() = runModel { store ->
        val repository = FakeEmailActions().apply { verify = EmailActionResult.Failure(EmailActionFailure.Network) }
        val m = model(repository, EmailActionMode.VerifyEmail, store)
        m.start(); runCurrent()
        assertEquals(EmailActionState.Failed(EmailActionMode.VerifyEmail, EmailActionFailure.Network), m.state.value)
        repository.verify = EmailActionResult.Success("ana@ejemplo.com")
        m.retry(); runCurrent()
        assertIs<EmailActionState.EmailVerified>(m.state.value)
        assertEquals(2, repository.calls.size)
    }

    @Test fun passwordResetChecksTheLinkThenSetsThePassword() = runModel { store ->
        val repository = FakeEmailActions()
        val m = model(repository, EmailActionMode.ResetPassword, store)
        m.start(); runCurrent()
        assertEquals(EmailActionState.NewPasswordForm("ana@ejemplo.com"), m.state.value)
        // Contraseñas inválidas no llegan a Firebase.
        m.submitPassword("corta", "corta"); runCurrent()
        assertNotNull((m.state.value as EmailActionState.NewPasswordForm).error)
        m.submitPassword("password8", "otra-cosa"); runCurrent()
        assertEquals("Las contraseñas no coinciden.", (m.state.value as EmailActionState.NewPasswordForm).error)
        assertEquals(listOf("check:code123"), repository.calls)
        m.submitPassword("password8", "password8"); runCurrent()
        assertEquals(EmailActionState.PasswordChanged("ana@ejemplo.com"), m.state.value)
        assertEquals("confirm:code123:password8", repository.calls.last())
    }

    @Test fun weakPasswordFromFirebaseKeepsTheFormAndTheLinkValid() = runModel { store ->
        val repository = FakeEmailActions().apply { confirm = EmailActionResult.Failure(EmailActionFailure.WeakPassword) }
        val m = model(repository, EmailActionMode.ResetPassword, store)
        m.start(); runCurrent()
        m.submitPassword("password8", "password8"); runCurrent()
        val form = assertIs<EmailActionState.NewPasswordForm>(m.state.value)
        assertNotNull(form.error)
        assertFalse(form.submitting)
    }

    @Test fun expiredResetLinkAndExpiredSubmitShowTheFailure() = runModel { store ->
        val repository = FakeEmailActions().apply { check = EmailActionResult.Failure(EmailActionFailure.InvalidOrExpired) }
        val m = model(repository, EmailActionMode.ResetPassword, store)
        m.start(); runCurrent()
        assertEquals(EmailActionState.Failed(EmailActionMode.ResetPassword, EmailActionFailure.InvalidOrExpired), m.state.value)
        val other = FakeEmailActions().apply { confirm = EmailActionResult.Failure(EmailActionFailure.InvalidOrExpired) }
        val second = EmailActionViewModel(EmailActionRequest(EmailActionMode.ResetPassword, "c"), VerifyEmailLink(other),
            CheckPasswordResetLink(other), ConfirmPasswordReset(other), RecoverEmailLink(other)).also { store.put("s", it) }
        second.start(); runCurrent()
        second.submitPassword("password8", "password8"); runCurrent()
        assertIs<EmailActionState.Failed>(second.state.value)
    }

    @Test fun networkFailureWhileSavingKeepsTheFormWithAMessage() = runModel { store ->
        val repository = FakeEmailActions().apply { confirm = EmailActionResult.Failure(EmailActionFailure.Network) }
        val m = model(repository, EmailActionMode.ResetPassword, store)
        m.start(); runCurrent()
        m.submitPassword("password8", "password8"); runCurrent()
        assertTrue((m.state.value as EmailActionState.NewPasswordForm).error!!.contains("conectar"))
    }

    @Test fun recoverEmailAppliesTheCode() = runModel { store ->
        val repository = FakeEmailActions()
        val m = model(repository, EmailActionMode.RecoverEmail, store)
        m.start(); runCurrent()
        assertEquals(EmailActionState.EmailRecovered("ana@ejemplo.com"), m.state.value)
        assertEquals(listOf("recover:code123"), repository.calls)
    }
}

class FirebaseEmailActionDataSourceTest {
    private val config = AppConfig("https://api.example.com", "public-key-for-tests-only")
    private val json = headersOf("Content-Type", "application/json")

    @Test fun verifyEmailPostsOnlyTheCodeWithThePublicKeyAndSpanishLocale() = runTest {
        val client = createHttpClient(MockEngine { request ->
            assertEquals("/v1/accounts:update", request.url.encodedPath)
            assertEquals("public-key-for-tests-only", request.url.parameters["key"])
            assertEquals("es", request.headers["X-Firebase-Locale"])
            val body = Json.parseToJsonElement(assertIs<TextContent>(request.body).text).jsonObject
            assertEquals(setOf("oobCode"), body.keys)
            assertEquals("abc", body["oobCode"]?.jsonPrimitive?.content)
            respond("""{"kind":"identitytoolkit#SetAccountInfoResponse","email":"ana@ejemplo.com","emailVerified":true}""", HttpStatusCode.OK, json)
        })
        try { assertEquals(EmailActionResult.Success<String?>("ana@ejemplo.com"), FirebaseEmailActionDataSource(client, config).verifyEmail("abc")) }
        finally { client.close() }
    }

    @Test fun providerRejectionsBecomePlainFailures() = runTest {
        suspend fun failure(status: HttpStatusCode, code: String): EmailActionFailure {
            val client = createHttpClient(MockEngine { respond("""{"error":{"code":${status.value},"message":"$code"}}""", status, json) })
            try { return assertIs<EmailActionResult.Failure>(FirebaseEmailActionDataSource(client, config).verifyEmail("abc")).reason }
            finally { client.close() }
        }
        assertEquals(EmailActionFailure.InvalidOrExpired, failure(HttpStatusCode.BadRequest, "EXPIRED_OOB_CODE"))
        assertEquals(EmailActionFailure.InvalidOrExpired, failure(HttpStatusCode.BadRequest, "INVALID_OOB_CODE"))
        assertEquals(EmailActionFailure.UserDisabled, failure(HttpStatusCode.BadRequest, "USER_DISABLED"))
        assertEquals(EmailActionFailure.RateLimited, failure(HttpStatusCode.BadRequest, "TOO_MANY_ATTEMPTS_TRY_LATER"))
        assertEquals(EmailActionFailure.Unavailable, failure(HttpStatusCode.InternalServerError, "INTERNAL"))
        assertEquals(EmailActionFailure.Unavailable, failure(HttpStatusCode.BadRequest, "OPERATION_NOT_ALLOWED"))
        assertEquals(EmailActionFailure.WeakPassword, emailActionFailure(400, "WEAK_PASSWORD"))
        assertEquals(EmailActionFailure.RateLimited, emailActionFailure(429, ""))
    }

    @Test fun passwordResetChecksTheCodeThenSendsThePassword() = runTest {
        val bodies = mutableListOf<Set<String>>()
        val client = createHttpClient(MockEngine { request ->
            assertEquals("/v1/accounts:resetPassword", request.url.encodedPath)
            val body = Json.parseToJsonElement(assertIs<TextContent>(request.body).text).jsonObject
            bodies += body.keys
            respond("""{"email":"ana@ejemplo.com","requestType":"PASSWORD_RESET"}""", HttpStatusCode.OK, json)
        })
        try {
            val source = FirebaseEmailActionDataSource(client, config)
            assertEquals(EmailActionResult.Success("ana@ejemplo.com"), source.checkPasswordResetCode("abc"))
            assertEquals(EmailActionResult.Success(Unit), source.confirmPasswordReset("abc", "password8"))
            assertEquals(listOf(setOf("oobCode"), setOf("oobCode", "newPassword")), bodies)
        } finally { client.close() }
    }

    @Test fun missingEmailInResetCheckIsAServiceFailure() = runTest {
        val client = createHttpClient(MockEngine { respond("{}", HttpStatusCode.OK, json) })
        try { assertEquals(EmailActionResult.Failure(EmailActionFailure.Unavailable), FirebaseEmailActionDataSource(client, config).checkPasswordResetCode("abc")) }
        finally { client.close() }
    }

    @Test fun connectionErrorsAreRetryableNetworkFailures() = runTest {
        val client = createHttpClient(MockEngine { throw RuntimeException("sin red") })
        try { assertEquals(EmailActionResult.Failure(EmailActionFailure.Network), FirebaseEmailActionDataSource(client, config).verifyEmail("abc")) }
        finally { client.close() }
    }
}
