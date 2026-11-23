package pe.bpm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*

/** Nombre del rol para el antetítulo de la ayuda. */
internal fun GuideRole.label(): String = when (this) {
    GuideRole.Worker -> "Personal"; GuideRole.Chef -> "Jefe de cocina"; GuideRole.Admin -> "Administrador"; GuideRole.Super -> "Administración general"
}

/**
 * «Ayuda» (botón «?» de la cabecera, en todas las pantallas): para qué sirve la pantalla desde la que se abrió, cómo usarla en
 * pocos pasos, las palabras que se verán y cómo repetir «Primeros pasos». Misma anatomía de detalle/ajustes; cada bloque se puede
 * escuchar con «Escuchar».
 */
@Composable
internal fun HelpContent(
    topic: HelpTopic,
    role: GuideRole,
    onReplay: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = helpEntry(topic, role)
    BpmDetailScaffold("Ayuda: ${entry.title}", modifier, eyebrow = role.label(), subtitle = entry.purpose) {
        BpmListenButton("Ayuda: ${entry.title}. ${entry.purpose} " + entry.steps.mapIndexed { i, s -> "Paso ${i + 1}. $s" }.joinToString(" "), label = "Escuchar esta ayuda")
        BpmSettingsSection("Cómo usar esta pantalla") {
            entry.steps.forEachIndexed { i, step ->
                Text("${i + 1}. $step", style = BpmPlayType.text, color = BpmPlayColors.ink)
            }
        }
        BpmSettingsSection("Palabras que verás", description = "Qué significan, en sencillo.") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                glossaryFor(role).forEach { term ->
                    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
                        Text(term.term, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
                        Text(term.meaning, style = BpmPlayType.sub, color = BpmPlayColors.muted)
                    }
                }
            }
        }
        BpmSettingsSection("Primeros pasos", description = "La introducción corta y la lista de lo primero que conviene hacer.") {
            BpmChunkyButton("Ver primeros pasos otra vez", onReplay, Modifier.fillMaxWidth())
        }
        BpmSettingsSection("¿Necesitas más ayuda?") {
            Text(
                if (role == GuideRole.Admin || role == GuideRole.Super) "Si algo no funciona, usa «Reportar un problema» y cuenta qué pasó, o revisa tu contacto de soporte de BPM."
                else "Pregúntale a tu jefe de cocina o a tu administrador. Si algo no funciona en la app, usa «Reportar un problema».",
                style = BpmPlayType.sub, color = BpmPlayColors.muted)
            BpmSettingLink("Ajustes de la app", "Texto más grande, más contraste y «Escuchar».", onSettings, symbol = BpmSymbol.Settings)
        }
    }
}
