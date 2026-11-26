package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Zonas horarias que ofrece el selector: nombre IANA que guarda la API y cómo la lee una persona. */
object BpmTimezones {
    /** Zona por defecto de las sedes nuevas. */
    const val DEFAULT = "America/Lima"

    val options: List<Pair<String, String>> = listOf(
        "America/Lima" to "Perú (Lima)",
        "America/Bogota" to "Colombia (Bogotá)",
        "America/Guayaquil" to "Ecuador (Guayaquil)",
        "America/La_Paz" to "Bolivia (La Paz)",
        "America/Santiago" to "Chile (Santiago)",
        "America/Argentina/Buenos_Aires" to "Argentina (Buenos Aires)",
        "America/Montevideo" to "Uruguay (Montevideo)",
        "America/Asuncion" to "Paraguay (Asunción)",
        "America/Caracas" to "Venezuela (Caracas)",
        "America/Panama" to "Panamá",
        "America/Costa_Rica" to "Costa Rica",
        "America/Mexico_City" to "México (Ciudad de México)",
        "America/New_York" to "Estados Unidos (Nueva York)",
        "Europe/Madrid" to "España (Madrid)",
    )

    /** Nombre legible de [zone]; si no está en la lista (valor antiguo) se muestra tal cual para no ocultar el dato. */
    fun label(zone: String): String = options.firstOrNull { it.first == zone }?.second ?: zone
}

/**
 * Selector de zona horaria sin escribir: una ficha por zona frecuente y el valor actual siempre visible. Un valor guardado
 * que no esté en la lista aparece como ficha elegida para que no se pierda al editar la sede.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BpmTimezonePicker(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: String? = null,
    label: String = "Zona horaria",
) {
    val current = value.ifBlank { BpmTimezones.DEFAULT }
    val extra = if (BpmTimezones.options.none { it.first == current }) listOf(current to current) else emptyList()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (extra + BpmTimezones.options).forEach { (zone, name) ->
                BpmFilterChip(current == zone, { onValueChange(zone) }, { Text(name) }, enabled = enabled)
            }
        }
        Text("La hora de los controles y plazos de esta sede se cuenta en esta zona.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
