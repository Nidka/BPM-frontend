package pe.bpm.features.identity

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import pe.bpm.features.identity.domain.*
import pe.bpm.features.identity.presentation.IdentityViewModel
import pe.bpm.features.identity.presentation.AuthMode
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityViewModelTest {
    @Test
    fun registrationAndRecoveryNeverPretendToBeAnAuthenticatedSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        var registrations = 0
        val repository = object : IdentityRepository {
            override suspend fun register(name: String, email: String, password: String): IdentityResult<Unit> {
                registrations++; return IdentityResult.Success(Unit)
            }
            override suspend fun resetPassword(email: String) = IdentityResult.Success(Unit)
            override suspend fun resendVerification() = IdentityResult.Success(Unit)
            override suspend fun signIn(email: String, password: String): IdentityResult<Profile> = error("Not called")
            override suspend fun signInWithGoogle(): IdentityResult<Profile> = error("Not called")
            override suspend fun currentProfile(): IdentityResult<Profile> = error("Not called")
            override fun signOut() {}
        }
        try {
            val model = IdentityViewModel(SignIn(repository), GetCurrentProfile(repository), SignOut(repository),
                SignInWithGoogle(repository), true, Register(repository), ResetPassword(repository), ResendVerification(repository))
            store.put("identity", model)
            model.changeMode(AuthMode.Register)
            model.register("", "bad", "short", "different")
            advanceUntilIdle()
            assertEquals(0, registrations)
            assertEquals(AuthMode.Register, model.state.value.mode)
            assertTrue(model.state.value.errors.single().fields.containsKey("confirmPassword"))
            model.register("Persona", "person@example.com", "password8", "password8")
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
        val repository = object : IdentityRepository {
            override suspend fun register(name: String, email: String, password: String) = IdentityResult.Success(Unit)
            override suspend fun resetPassword(email: String) = IdentityResult.Success(Unit)
            override suspend fun resendVerification() = IdentityResult.Success(Unit)
            override suspend fun signIn(email: String, password: String) = IdentityResult.Success(profile)
            override suspend fun signInWithGoogle() = IdentityResult.Success(profile)
            override suspend fun currentProfile(): IdentityResult<Profile> = IdentityResult.Failure(listOf(
                IdentityError("UNAUTHENTICATED", "Tu sesión terminó.")))
            override fun signOut() { signedOut = true }
        }
        try {
            val model = IdentityViewModel(SignIn(repository), GetCurrentProfile(repository), SignOut(repository),
                SignInWithGoogle(repository), googleSupported = true, Register(repository), ResetPassword(repository), ResendVerification(repository))
            store.put("identity", model)
            model.login("test@example.com", "password")
            runCurrent()
            assertEquals(profile, model.state.value.profile)
            assertFalse(model.state.value.busy)
            model.verifySession()
            advanceUntilIdle()
            assertNull(model.state.value.profile)
            assertEquals("UNAUTHENTICATED", model.state.value.errors.single().code)
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
        val repository = object : IdentityRepository {
            override suspend fun register(name: String, email: String, password: String) = IdentityResult.Success(Unit)
            override suspend fun resetPassword(email: String) = IdentityResult.Success(Unit)
            override suspend fun resendVerification() = IdentityResult.Success(Unit)
            override suspend fun signInWithGoogle(): IdentityResult<Profile> = error("Not called")
            override suspend fun signIn(email: String, password: String): IdentityResult<Profile> {
                try { awaitCancellation() } finally { cancelled = true }
            }
            override suspend fun currentProfile(): IdentityResult<Profile> = error("Not called")
            override fun signOut() {}
        }
        try {
            val model = IdentityViewModel(SignIn(repository), GetCurrentProfile(repository), SignOut(repository),
                SignInWithGoogle(repository), googleSupported = true, Register(repository), ResetPassword(repository), ResendVerification(repository))
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
}
