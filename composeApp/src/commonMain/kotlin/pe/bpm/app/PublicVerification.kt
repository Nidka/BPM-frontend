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
