package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Confirmación reutilizada al descartar ediciones; no gestiona el borrado ni la navegación. */
@Composable fun DiscardDraftConfirmation(onKeep: () -> Unit, onDiscard: () -> Unit, enabled: Boolean = true) {
    BpmCard(Modifier.fillMaxWidth()) {
        Text("¿Descartar este borrador?")
        Text("Se eliminarán los cambios de este dispositivo que todavía no enviaste.")
        RaisedButton("Seguir editando", onKeep, enabled = enabled)
        BpmTextButton(onClick = onDiscard, enabled = enabled) { Text("Descartar borrador") }
    }
}
