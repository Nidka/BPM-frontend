package pe.bpm.features.reports.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.reports.domain.*

data class VerificationState(
    val code: String = "",
    val loading: Boolean = false,
    val certificate: PublicCertificate? = null,
    val failure: ReportFailure? = null,
)

/** Verificación pública de una constancia de cumplimiento; no requiere sesión. */
class CertificateVerificationViewModel(
    private val verify: VerifyComplianceCertificate,
    private val onClose: () -> Unit = {},
) : ViewModel() {
    private val mutable = MutableStateFlow(VerificationState())
    val state: StateFlow<VerificationState> = mutable.asStateFlow()
    private var generation = 0L

    fun check(code: String) {
        val expected = ++generation
        mutable.value = VerificationState(code = code, loading = true)
        viewModelScope.launch {
            val result = verify(code)
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is ReportResult.Success -> VerificationState(code = code, certificate = result.value)
                is ReportResult.Failure -> VerificationState(code = code, failure = result.reason)
            }
        }
    }

    fun retry() = check(mutable.value.code)

    override fun onCleared() { onClose() }
}
