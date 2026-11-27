package pe.bpm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.APP_VERSION
import pe.bpm.core.presentation.platformLabel
import pe.bpm.features.support.domain.SupportContact
import pe.bpm.features.support.domain.SupportRules

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
    support: SupportContact = SupportContact(SupportRules.DEFAULT_EMAIL),
    organizationName: String? = null,
) {
    val uriHandler = LocalUriHandler.current
    var supportNotice by remember { mutableStateOf<String?>(null) }
    val supportBody = SupportRules.messageBody(role.label(), organizationName, APP_VERSION, platformLabel())
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
            Text(helpNextSteps(role), style = BpmPlayType.sub, color = BpmPlayColors.muted)
            BpmSettingLink("Ajustes de la app", "Texto más grande, más contraste y «Escuchar».", onSettings, symbol = BpmSymbol.Settings)
        }
        BpmSettingsSection("Contactar soporte", description = "Si la app falla o no puedes entrar a una pantalla. Te responde el equipo de BPM.") {
            Text("Escríbenos a ${support.email}", style = BpmPlayType.text, color = BpmPlayColors.ink)
            BpmChunkyButton("Escribir un correo", {
                runCatching { uriHandler.openUri(SupportRules.mailtoUrl(support.email, supportBody)) }
                    .onFailure { supportNotice = "No pudimos abrir tu correo. Escribe a ${support.email}." }
            }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            support.whatsapp?.let { number ->
                BpmChunkyButton("Escribir por WhatsApp", {
                    runCatching { uriHandler.openUri(SupportRules.whatsappUrl(number, supportBody)) }
                        .onFailure { supportNotice = "No pudimos abrir WhatsApp. Escribe al +$number." }
                }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            }
            supportNotice?.let { BpmInlineNotice(it, onDismiss = { supportNotice = null }, warning = true) }
        }
    }
}

/**
 * A quién acudir: los problemas de la cocina van por «Reportar un problema» (crea una incidencia para el jefe o el
 * administrador); las fallas de la app, por «Contactar soporte».
 */
internal fun helpNextSteps(role: GuideRole): String = when (role) {
    GuideRole.Worker, GuideRole.Chef ->
        "Si tienes dudas, pregúntale primero a tu ${if (role == GuideRole.Worker) "jefe de cocina o a tu administrador" else "administrador"}. " +
            "Si hay un problema en la cocina (algo roto, sucio o fuera de temperatura), usa «Reportar un problema». Si la app falla, usa «Contactar soporte»."
    GuideRole.Admin ->
        "Si hay un problema en la cocina, usa «Reportar un problema»: tu equipo lo ve y lo puede corregir. Si la app falla, usa «Contactar soporte»."
    GuideRole.Super -> "Si la app falla, usa «Contactar soporte». Puedes cambiar el correo de soporte en Ajustes."
}
