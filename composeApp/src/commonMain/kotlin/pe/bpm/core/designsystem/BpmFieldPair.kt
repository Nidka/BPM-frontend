package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Ancho útil mínimo para dejar dos campos lado a lado; por debajo, cada uno recibe el ancho completo. */
private val sideBySideMinWidth = 420.dp

/**
 * Dos campos relacionados (por ejemplo «Desde» y «Hasta»). Lado a lado solo si caben con su etiqueta completa;
 * en ancho compacto se apilan para no cortar etiquetas ni mensajes de error. Cada lambda recibe el modificador
 * que debe aplicar a su campo raíz.
 */
@Composable
fun BpmFieldPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < sideBySideMinWidth) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        }
    }
}
