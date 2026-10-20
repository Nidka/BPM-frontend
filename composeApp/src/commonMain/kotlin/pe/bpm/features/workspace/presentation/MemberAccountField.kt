package pe.bpm.features.workspace.presentation

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
import pe.bpm.core.designsystem.*
import pe.bpm.features.workspace.domain.MemberEligibility

/** Traduce los resultados del caso de uso sin trasladar reglas de negocio al buscador compartido. */
@Composable
internal fun MemberAccountField(email: String, onEdit: (String) -> Unit, state: MemberAccountSearchState,
    enabled: Boolean, fieldError: String?, navigation: BpmFieldNavigation,
    onSelect: (String) -> Unit, onRetry: () -> Unit) {
    val unavailable = state.status == MemberEligibility.UNAVAILABLE && state.suggestions.isEmpty()
    val message = when {
        state.loading -> "Buscando cuentas…"
        state.errors.isNotEmpty() -> null
        state.status == MemberEligibility.READY -> "Cuenta encontrada. Elige su rol y sus sedes."
        state.suggestions.isNotEmpty() -> "Selecciona el correo de la persona."
        state.status == MemberEligibility.ALREADY_MEMBER -> "Esta persona ya está en el equipo."
        unavailable -> "No hay una cuenta BPM disponible con este correo. La persona debe completar su registro, confirmar su correo y entrar a BPM."
        state.searched -> "Sin coincidencias. Escribe el correo completo."
        else -> "Escribe al menos 3 caracteres del correo."
    }
    BpmSearchField(email, onEdit, "Correo de la cuenta BPM", enabled = enabled, loading = state.loading,
        error = fieldError ?: state.inputError ?: if (unavailable) message else null,
        supportingText = message, placeholder = "Ej.: nombre@correo.com",
        suggestions = state.suggestions, onSelect = onSelect,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false), navigation = navigation)
    if (state.errors.isNotEmpty()) {
        Errors(state.errors)
        BpmTextButton(onClick = onRetry, enabled = enabled && !state.loading) { Text("Reintentar búsqueda") }
    }
}
