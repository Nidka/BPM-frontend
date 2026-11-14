package pe.bpm.core.di

import pe.bpm.core.config.AppConfig
import pe.bpm.core.config.platformAppConfig
import pe.bpm.core.network.createHttpClient
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
