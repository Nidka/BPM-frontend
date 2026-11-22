package pe.bpm.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Dos campos relacionados (por ejemplo «Desde» y «Hasta»). Alias histórico de [BpmShortFields]: lado a lado
 * desde 420 dp de contenedor y apilados por debajo. Cada lambda recibe el modificador de su campo raíz.
 */
@Composable
fun BpmFieldPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit, modifier: Modifier = Modifier) =
    BpmShortFields(first, second, modifier)
