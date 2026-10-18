package pe.bpm.features.identity

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.*
import pe.bpm.features.identity.domain.*
import pe.bpm.features.identity.presentation.IdentityViewModel
import pe.bpm.features.identity.presentation.AuthMode
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityViewModelTest {
    @Test fun failedLoginWaitsForAnotherExplicitSignInAndDoesNotStartAutomaticRetries() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val profile = Profile("uid", "person@example.com", "Persona Pruebas", emptyList())
        val repository = object : FakeIdentityRepository() {
            override suspend fun signIn(email: String, password: String): IdentityResult<Profile> {
                attempts++
                if (attempts == 1) {
                    release.await()
                    return IdentityResult.Failure(listOf(IdentityError("NETWORK_ERROR", "No pudimos conectar. Vuelve a intentarlo.")))
                }
                return IdentityResult.Success(profile)
            }
            override suspend fun currentProfile() = error("No hay sesión que consultar")
            override suspend fun signInWithGoogle() = error("No se solicitó Google")
            override suspend fun register(name: String, email: String, password: String) = error("No se solicitó registro")
            override suspend fun resetPassword(email: String) = error("No se solicitó recuperación")
            override suspend fun resendVerification() = error("No se solicitó reenvío")
            override suspend fun confirmEmailVerification() = error("No se solicitó verificación")
            override fun signOut() {}
        }
        try {
            val model = IdentityViewModel(SignIn(repository), GetCurrentProfile(repository), SignOut(repository),
                SignInWithGoogle(repository), false, Register(repository), ResetPassword(repository),
                ResendVerification(repository), ConfirmEmailVerification(repository))
            store.put("identity", model)
            model.login("person@example.com", "password8"); runCurrent()
            model.login("person@example.com", "password8"); runCurrent()
            assertEquals(1, attempts)
            release.complete(Unit); advanceUntilIdle()
            assertFalse(model.state.value.busy)
            assertNull(model.state.value.profile)
            assertEquals(AuthMode.Login, model.state.value.mode)
            assertEquals("NETWORK_ERROR", model.state.value.errors.single().code)
            advanceTimeBy(60_000); runCurrent()
            assertEquals(1, attempts)
            model.login("person@example.com", "password8"); runCurrent()
            assertEquals(2, attempts)
            assertEquals(profile, model.state.value.profile)
            assertTrue(model.state.value.errors.isEmpty())
        } finally { release.complete(Unit); store.clear(); Dispatchers.resetMain() }
    }
    @Test fun verificationKeepsEmailBlocksRepeatedSendAndOnlyContinuesWithAuthorizedProfile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        var sends = 0
        var verified = false
        val profile = Profile("uid", "person@example.com", "Persona Pruebas", emptyList())
        val repository = object : FakeIdentityRepository() {
            override suspend fun register(name: String, email: String, password: String): IdentityResult<Unit> =
                if (name == "Limitado Pruebas") IdentityResult.Failure(listOf(IdentityError("REGISTRATION_RATE_LIMITED", "Espera un minuto.")))
                else IdentityResult.Success(Unit)
            override suspend fun resetPassword(email: String) = IdentityResult.Success(Unit)
            override suspend fun resendVerification(): IdentityResult<Unit> { sends++; return IdentityResult.Success(Unit) }
            override suspend fun confirmEmailVerification(): IdentityResult<Profile> = if (verified) IdentityResult.Success(profile)
                else IdentityResult.Failure(listOf(IdentityError("EMAIL_NOT_VERIFIED", "Confirma tu correo.")))
            override suspend fun signIn(email: String, password: String): IdentityResult<Profile> = IdentityResult.Failure(listOf(IdentityError("EMAIL_NOT_VERIFIED", "Confirma tu correo.")))
            override suspend fun signInWithGoogle(): IdentityResult<Profile> = error("No se esperaba Google")
            override suspend fun currentProfile(): IdentityResult<Profile> = error("No existe una sesión confirmada")
            override fun signOut() {}
        }
        try {
            val model = IdentityViewModel(SignIn(repository), GetCurrentProfile(repository), SignOut(repository),
                SignInWithGoogle(repository), false, Register(repository), ResetPassword(repository),
                ResendVerification(repository), ConfirmEmailVerification(repository))
            store.put("identity", model)
            model.changeMode(AuthMode.Register)
            model.register("Persona", "Pruebas", " person@example.com ", "password8", "password8")
            runCurrent()
            assertEquals("person@example.com", model.state.value.verificationEmail)
            assertEquals(AuthMode.Verification, model.state.value.mode)
            assertTrue(model.state.value.verificationEmailSent)
            assertFalse(model.state.value.verificationDeliveryFailed)
            assertEquals(60, model.state.value.resendCooldownSeconds)
            model.resend(); runCurrent()
            assertEquals(0, sends)
            model.confirmEmail(); runCurrent()
            assertNull(model.state.value.profile)
            assertFalse(model.state.value.busy)
            assertNull(model.state.value.notice)
            assertEquals(AuthMode.Verification, model.state.value.mode)
            advanceTimeBy(60_000); runCurrent()
            model.resend(); runCurrent()
            assertEquals(1, sends)
            verified = true
            model.confirmEmail(); runCurrent()
            assertEquals(profile, model.state.value.profile)
            assertNull(model.state.value.verificationEmail)
            model.logout()
            model.login("person@example.com", "password8"); runCurrent()
            assertEquals(AuthMode.Verification, model.state.value.mode)
            assertEquals("person@example.com", model.state.value.verificationEmail)
            assertFalse(model.state.value.verificationEmailSent)
            model.changeMode(AuthMode.Register)
            model.register("Limitado", "Pruebas", "person@example.com", "password8", "password8")
            runCurrent()
            assertEquals(AuthMode.Verification, model.state.value.mode)
            assertEquals(60, model.state.value.resendCooldownSeconds)
            assertFalse(model.state.value.verificationEmailSent)
            assertTrue(model.state.value.verificationDeliveryFailed)
            assertNull(model.state.value.notice)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun registrationAndRecoveryNeverPretendToBeAnAuthenticatedSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        var registrations = 0
        val repository = object : FakeIdentityRepository() {
            override suspend fun register(name: String, email: String, password: String): IdentityResult<Unit> {
                registrations++
                if (name == "Pendiente Pruebas") return IdentityResult.Failure(listOf(IdentityError("REGISTRATION_INCOMPLETE", "Verificación pendiente.")))
                return IdentityResult.Success(Unit)
            }
            override suspend fun resetPassword(email: String) = IdentityResult.Success(Unit)
            override suspend fun resendVerification() = IdentityResult.Success(Unit)
            override suspend fun confirmEmailVerification(): IdentityResult<Profile> = error("No se esperaba confirmar el correo")
            override suspend fun signIn(email: String, password: String): IdentityResult<Profile> = error("Not called")
            override suspend fun signInWithGoogle(): IdentityResult<Profile> = error("Not called")
            override suspend fun currentProfile(): IdentityResult<Profile> = error("Not called")
            override fun signOut() {}
        }
        try {
            val model = IdentityViewModel(SignIn(repository), GetCurrentProfile(repository), SignOut(repository),
                SignInWithGoogle(repository), true, Register(repository), ResetPassword(repository), ResendVerification(repository), ConfirmEmailVerification(repository))
            store.put("identity", model)
            model.changeMode(AuthMode.Register)
            model.register("", "", "bad", "short", "different")
            advanceUntilIdle()
            assertEquals(0, registrations)
            assertEquals(AuthMode.Register, model.state.value.mode)
            assertTrue(model.state.value.errors.single().fields.containsKey("confirmPassword"))
            model.register("Pendiente", "Pruebas", "person@example.com", "password8", "password8")
            advanceUntilIdle()
            assertEquals(AuthMode.Verification, model.state.value.mode)
            assertTrue(model.state.value.canResendVerification)
            assertNull(model.state.value.profile)
            model.resend()
            advanceUntilIdle()
            assertTrue(model.state.value.errors.isEmpty())
            model.changeMode(AuthMode.Register)
            model.register("Persona", "Pruebas", "person@example.com", "password8", "password8")
            advanceUntilIdle()
            assertEquals(AuthMode.Verification, model.state.value.mode)
            assertTrue(model.state.value.canResendVerification)
            assertNull(model.state.value.profile)
            model.changeMode(AuthMode.Recovery)
            model.recover("person@example.com")
            advanceUntilIdle()
            assertTrue(model.state.value.notice!!.startsWith("Si existe"))
            assertNull(model.state.value.profile)
            model.logout()
            assertNull(model.state.value.notice)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test
    fun loginVerificationAndLogoutHaveExplicitStates() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val profile = Profile("uid", "test@example.com", "Persona", emptyList())
        var signedOut = false
        var profileResult: IdentityResult<Profile> = IdentityResult.Success(profile)
        val repository = object : FakeIdentityRepository() {
            override suspend fun register(name: String, email: String, password: String) = IdentityResult.Success(Unit)
            override suspend fun resetPassword(email: String) = IdentityResult.Success(Unit)
            override suspend fun resendVerification() = IdentityResult.Success(Unit)
            override suspend fun confirmEmailVerification(): IdentityResult<Profile> = error("No se esperaba confirmar el correo")
            override suspend fun signIn(email: String, password: String) = IdentityResult.Success(profile)
            override suspend fun signInWithGoogle() = IdentityResult.Success(profile)
            override suspend fun currentProfile() = profileResult
            override fun signOut() { signedOut = true }
        }
        try {
            val model = IdentityViewModel(SignIn(repository), GetCurrentProfile(repository), SignOut(repository),
                SignInWithGoogle(repository), googleSupported = true, Register(repository), ResetPassword(repository), ResendVerification(repository), ConfirmEmailVerification(repository))
            store.put("identity", model)
            model.login("test@example.com", "password")
            runCurrent()
            assertEquals(profile, model.state.value.profile)
            assertFalse(model.state.value.busy)
            assertNull(model.state.value.verificationMessage)
            model.verifySession()
            assertTrue(model.state.value.busy)
            assertNull(model.state.value.verificationMessage)
            advanceUntilIdle()
            assertEquals(profile, model.state.value.profile)
            assertEquals("Sesión verificada. Tu cuenta aún no tiene una organización asignada.", model.state.value.verificationMessage)
            assertFalse(model.state.value.busy)
            profileResult = IdentityResult.Failure(listOf(IdentityError("AUTH_UNAVAILABLE", "No se pudo comprobar la sesión.")))
            model.verifySession()
            assertNull(model.state.value.verificationMessage)
            advanceUntilIdle()
            assertEquals(profile, model.state.value.profile)
            assertFalse(model.state.value.busy)
            assertEquals("AUTH_UNAVAILABLE", model.state.value.errors.single().code)
            profileResult = IdentityResult.Failure(listOf(IdentityError("UNAUTHENTICATED", "Tu sesión terminó.")))
            model.verifySession()
            advanceUntilIdle()
            assertNull(model.state.value.profile)
            assertEquals("UNAUTHENTICATED", model.state.value.errors.single().code)
            assertNull(model.state.value.verificationMessage)
            model.logout()
            assertTrue(signedOut)
            assertTrue(model.state.value.errors.isEmpty())
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun logoutCancelsAnOngoingLogin() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        var cancelled = false
        val repository = object : FakeIdentityRepository() {
            override suspend fun register(name: String, email: String, password: String) = IdentityResult.Success(Unit)
            override suspend fun resetPassword(email: String) = IdentityResult.Success(Unit)
            override suspend fun resendVerification() = IdentityResult.Success(Unit)
            override suspend fun confirmEmailVerification(): IdentityResult<Profile> = error("No se esperaba confirmar el correo")
            override suspend fun signInWithGoogle(): IdentityResult<Profile> = error("Not called")
            override suspend fun signIn(email: String, password: String): IdentityResult<Profile> {
                try { awaitCancellation() } finally { cancelled = true }
            }
            override suspend fun currentProfile(): IdentityResult<Profile> = error("Not called")
            override fun signOut() {}
        }
        try {
            val model = IdentityViewModel(SignIn(repository), GetCurrentProfile(repository), SignOut(repository),
                SignInWithGoogle(repository), googleSupported = true, Register(repository), ResetPassword(repository), ResendVerification(repository), ConfirmEmailVerification(repository))
            store.put("identity", model)
            model.login("test@example.com", "password")
            runCurrent()
            assertTrue(model.state.value.busy)
            model.logout()
            advanceUntilIdle()
            assertTrue(cancelled)
            assertFalse(model.state.value.busy)
            assertNull(model.state.value.profile)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun pollingDoesNotCreateConfirmationAndClearsItWhenPermissionsChange() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        var profile = Profile("uid", "test@example.com", "Persona", emptyList())
        var calls = 0
        val repository = object : FakeIdentityRepository() {
            override suspend fun register(name: String, email: String, password: String) = IdentityResult.Success(Unit)
            override suspend fun resetPassword(email: String) = IdentityResult.Success(Unit)
            override suspend fun resendVerification() = IdentityResult.Success(Unit)
            override suspend fun confirmEmailVerification(): IdentityResult<Profile> = error("No se esperaba confirmar el correo")
            override suspend fun signIn(email: String, password: String) = IdentityResult.Success(profile)
            override suspend fun signInWithGoogle() = IdentityResult.Success(profile)
            override suspend fun currentProfile(): IdentityResult<Profile> { calls++; return IdentityResult.Success(profile) }
            override fun signOut() {}
        }
        try {
            val model = IdentityViewModel(SignIn(repository), GetCurrentProfile(repository), SignOut(repository),
                SignInWithGoogle(repository), true, Register(repository), ResetPassword(repository), ResendVerification(repository), ConfirmEmailVerification(repository),
                observeInvalidations = ObserveSessionInvalidations(repository))
            store.put("identity", model)
            model.login("test@example.com", "password"); runCurrent()
            advanceTimeBy(60_000); runCurrent()
            assertEquals(1, calls)
            assertNull(model.state.value.verificationMessage)
            model.verifySession(); runCurrent()
            val confirmation = model.state.value.verificationMessage
            assertNotNull(confirmation)
            advanceTimeBy(60_000); runCurrent()
            assertEquals(confirmation, model.state.value.verificationMessage)
            profile = profile.copy(organizations = listOf(Organization("org", "Empresa", "ADMIN")))
            advanceTimeBy(60_000); runCurrent()
            assertEquals(profile, model.state.value.profile)
            assertNull(model.state.value.verificationMessage)
            model.verifySession(); runCurrent()
            assertEquals("Sesión verificada. Tus permisos están actualizados.", model.state.value.verificationMessage)
            model.logout()
            assertNull(model.state.value.verificationMessage)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun lateVerificationCannotConfirmASessionAfterLogout() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val release = CompletableDeferred<Unit>()
        val profile = Profile("uid", "test@example.com", "Persona", emptyList())
        val repository = object : FakeIdentityRepository() {
            override suspend fun register(name: String, email: String, password: String) = IdentityResult.Success(Unit)
            override suspend fun resetPassword(email: String) = IdentityResult.Success(Unit)
            override suspend fun resendVerification() = IdentityResult.Success(Unit)
            override suspend fun confirmEmailVerification(): IdentityResult<Profile> = error("No se esperaba confirmar el correo")
            override suspend fun signIn(email: String, password: String) = IdentityResult.Success(profile)
            override suspend fun signInWithGoogle() = IdentityResult.Success(profile)
            override suspend fun currentProfile(): IdentityResult<Profile> {
                withContext(NonCancellable) { release.await() }
                return IdentityResult.Success(profile)
            }
            override fun signOut() {}
        }
        try {
            val model = IdentityViewModel(SignIn(repository), GetCurrentProfile(repository), SignOut(repository),
                SignInWithGoogle(repository), true, Register(repository), ResetPassword(repository), ResendVerification(repository), ConfirmEmailVerification(repository))
            store.put("identity", model)
            model.login("test@example.com", "password"); runCurrent()
            model.verifySession(); runCurrent()
            assertTrue(model.state.value.busy)
            model.logout()
            release.complete(Unit); advanceUntilIdle()
            assertNull(model.state.value.profile)
            assertNull(model.state.value.verificationMessage)
            assertFalse(model.state.value.busy)
        } finally { release.complete(Unit); store.clear(); Dispatchers.resetMain() }
    }
}
