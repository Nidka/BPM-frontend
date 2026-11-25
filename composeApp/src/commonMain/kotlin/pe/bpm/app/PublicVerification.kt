package pe.bpm.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pe.bpm.core.designsystem.BpmTheme
import pe.bpm.core.di.createCertificateVerificationViewModel
import pe.bpm.features.reports.presentation.CertificateVerificationScreen

/** Página pública `/verificar-cumplimiento/{codigo}`: sin login, sin grafo de sesión ni navegación. */
@Composable
fun PublicCertificateVerification(code: String) {
    val model = viewModel { createCertificateVerificationViewModel() }
    val state by model.state.collectAsStateWithLifecycle()
    BpmTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CertificateVerificationScreen(code, model, state, Modifier.verticalScroll(rememberScrollState()))
        }
    }
}

/**
 * Página pública `/auth/accion` (enlaces de correo de Firebase): verificar correo, crear contraseña nueva o recuperar correo.
 * [onNavigate] recibe el destino al pulsar «Iniciar sesión»: el acceso con el correo precargado o el `continueUrl` del enlace si es de BPM.
 */
@Composable
fun PublicEmailAction(request: pe.bpm.features.identity.domain.EmailActionRequest?, onNavigate: (destination: String) -> Unit) {
    val model = viewModel { pe.bpm.core.di.createEmailActionViewModel(request) }
    val state by model.state.collectAsStateWithLifecycle()
    // El enlace se aplica una sola vez; el ViewModel ignora arranques repetidos.
    androidx.compose.runtime.LaunchedEffect(model) { model.start() }
    BpmTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            pe.bpm.features.identity.presentation.EmailActionScreen(state, model::submitPassword, model::retry,
                onGoToLogin = { email ->
                    val resume = model.continueUrl.takeIf { request?.mode != pe.bpm.features.identity.domain.EmailActionMode.ResetPassword }
                    onNavigate(resume ?: pe.bpm.features.identity.domain.EmailActionLink.loginDestination(email))
                })
        }
    }
}
