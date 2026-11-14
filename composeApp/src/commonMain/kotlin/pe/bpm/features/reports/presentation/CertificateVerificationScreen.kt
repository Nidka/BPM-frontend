package pe.bpm.features.reports.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.reports.domain.ReportFailure

/**
 * Página pública de verificación (RF-39). No usa sesión ni muestra datos internos: solo empresa,
 * período, cumplimiento, umbral y fecha de emisión.
 */
@Composable
fun CertificateVerificationScreen(code: String, viewModel: CertificateVerificationViewModel, state: VerificationState, modifier: Modifier = Modifier) {
    LaunchedEffect(code) { viewModel.check(code) }
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
            Brand()
            Text("Verificación de constancia de cumplimiento", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.certificate?.let { cert ->
                BpmCard(Modifier.fillMaxWidth()) {
                    Eyebrow("CONSTANCIA VÁLIDA")
                    Text(cert.organizationName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Período: ${longDate(cert.periodStart)} al ${longDate(cert.periodEnd)}")
                    Text("Cumplimiento promedio: ${formatPercent(cert.averageCompliance)}")
                    Text("Umbral exigido: ${formatPercent(cert.threshold)}")
                    Text("Emitida el ${longDate(cert.issuedAt)}")
                    Text("Código: ${cert.verificationCode}", fontWeight = FontWeight.Bold)
                }
            }
            state.failure?.let { failure ->
                BpmCard(Modifier.fillMaxWidth()) {
                    Text(
                        if (failure == ReportFailure.CERTIFICATE_NOT_FOUND) "No encontramos una constancia con ese código. Revisa que el enlace esté completo."
                        else reportFailureMessage(failure),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (failure.retryable) BpmSecondaryButton(onClick = viewModel::retry) { Text("Reintentar") }
                }
            }
        }
    }
}
