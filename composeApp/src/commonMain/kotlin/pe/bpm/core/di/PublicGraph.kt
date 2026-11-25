package pe.bpm.core.di

import pe.bpm.core.config.AppConfig
import pe.bpm.core.config.platformAppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.identity.data.FirebaseEmailActionDataSource
import pe.bpm.features.identity.domain.*
import pe.bpm.features.identity.presentation.EmailActionViewModel
import pe.bpm.features.reports.data.RemoteReportsRepository
import pe.bpm.features.reports.domain.VerifyComplianceCertificate
import pe.bpm.features.reports.presentation.CertificateVerificationViewModel

/**
 * Composición de las páginas públicas (sin sesión). La verificación de constancias solo necesita un
 * cliente HTTP propio, que se cierra cuando el ViewModel se libera.
 */
fun createCertificateVerificationViewModel(config: AppConfig = platformAppConfig()): CertificateVerificationViewModel {
    val client = createHttpClient()
    val repository = RemoteReportsRepository(client, config, token = { null })
    return CertificateVerificationViewModel(VerifyComplianceCertificate(repository), onClose = client::close)
}

/**
 * Página pública `/auth/accion` (verificar correo, restablecer contraseña, recuperar correo): sin sesión, con un cliente HTTP propio
 * que se cierra cuando el ViewModel se libera. [request] es nulo si el enlace está incompleto.
 */
fun createEmailActionViewModel(request: EmailActionRequest?, config: AppConfig = platformAppConfig()): EmailActionViewModel {
    val client = createHttpClient()
    val repository = FirebaseEmailActionDataSource(client, config)
    return EmailActionViewModel(request, VerifyEmailLink(repository), CheckPasswordResetLink(repository),
        ConfirmPasswordReset(repository), RecoverEmailLink(repository), onClose = client::close)
}
