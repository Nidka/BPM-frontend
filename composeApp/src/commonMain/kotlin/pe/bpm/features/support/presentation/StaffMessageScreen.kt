package pe.bpm.features.support.presentation

// patrón: hoja de ajustes (BpmSettingsScaffold) como Ayuda y Ajustes: secciones cortas con botones grandes.

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.support.domain.*

/**
 * «Avisar a mi jefe»: tres botones grandes (Falta insumo, Equipo malogrado, Necesito ayuda), un texto opcional y Enviar.
 * Llega como aviso al jefe de cocina y al administrador de la sede. Si el jefe dejó su WhatsApp, se puede escribirle directo.
 */
@Composable
fun StaffMessageScreen(
    viewModel: StaffMessageViewModel,
    myName: String,
    siteName: String,
    sites: List<Pair<String, String>>,
    onPickSite: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by viewModel.state.collectAsState()
    val uri = LocalUriHandler.current
    var notice by remember { mutableStateOf<String?>(null) }
    BpmSettingsScaffold("Avisar a mi jefe", modifier, subtitle = "Un aviso rápido. Le llega a tu jefe de cocina y al administrador.") {
        if (sites.size > 1) BpmSettingsSection("¿En qué local estás?") {
            sites.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { (id, name) -> BpmChoiceTile(name, BpmSymbol.Company, BpmPlayTone.Action, id == s.locationId, { onPickSite(id) }, Modifier.weight(1f).fillMaxHeight()) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        BpmSettingsSection("¿Qué pasa?") {
            listOf(StaffMessageKind.SUPPLY to BpmSymbol.Sprout, StaffMessageKind.EQUIPMENT to BpmSymbol.Wrench, StaffMessageKind.HELP to BpmSymbol.Team, StaffMessageKind.OTHER to BpmSymbol.Alert)
                .chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { (kind, icon) -> BpmChoiceTile(kind.label, icon, BpmPlayTone.Action, s.kind == kind, { viewModel.pick(kind) }, Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            BpmTextField(s.text, viewModel::edit, "Cuéntale más (opcional)", Modifier.fillMaxWidth(), singleLine = false, minLines = 2, enabled = !s.sending,
                placeholder = "Ejemplo: se acabó el aceite")
            s.error?.let { BpmInlineNotice(it, warning = true) }
            if (s.sent) BpmInlineNotice("Listo. Tu jefe recibió el aviso.")
            BpmChunkyButton(if (s.sending) "Enviando…" else "Enviar aviso", viewModel::send, Modifier.fillMaxWidth(), tone = BpmChunkyTone.Report, enabled = !s.sending && s.locationId.isNotEmpty())
        }
        s.contact?.let { c ->
            BpmSettingsSection("Escribir por WhatsApp", description = "Para hablar directo con ${c.name.ifBlank { if (c.role == "CHEF") "tu jefe de cocina" else "tu administrador" }}.") {
                BpmChunkyButton("Escribir por WhatsApp", {
                    val text = StaffMessageRules.whatsappText(c.name, myName, siteName, s.kind, s.text)
                    runCatching { uri.openUri(SupportRules.whatsappUrl(c.whatsapp, text)) }.onFailure { notice = "No pudimos abrir WhatsApp." }
                }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
                notice?.let { BpmInlineNotice(it, onDismiss = { notice = null }, warning = true) }
            }
        }
        BpmChunkyButton("Volver", onClose, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
    }
}
