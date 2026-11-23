package pe.bpm.features.webhooks.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.ShareResult
import pe.bpm.core.presentation.displayDateTime
import pe.bpm.core.presentation.rememberTextSharer
import pe.bpm.features.webhooks.domain.*

/**
 * Automatizaciones (RF-48): webhooks que reciben los avisos de la empresa en n8n u otra herramienta.
 * patrón: Rejilla de webhooks y Formulario de 720 dp, alojada por el shell.
 */
@Composable
fun WebhooksScreen(viewModel: WebhooksViewModel, organizationId: String, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(organizationId) { viewModel.open(organizationId) }
    val list = state.list
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xl)) {
        state.notice?.let { BpmInlineNotice(it) }
        state.failure?.let {
            Text(WebhookRules.failureMessage(it, list?.maxEndpoints ?: 5), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        state.revealed?.let { SecretPanel(it, viewModel::closeSecret) }
        val editor = state.editor
        if (editor != null && list != null) { WebhookForm(editor, list.eventTypes, state.busy, viewModel); return@Column }
        state.deleting?.let { webhook ->
            BpmConfirmPanel("¿Eliminar este webhook?", "Sí, eliminar", viewModel::confirmDelete, viewModel::cancelDelete, busy = state.busy) {
                Text("${webhook.url} dejará de recibir avisos. Tus flujos de n8n que dependan de él se detendrán.")
            }
        }
        BpmPageHeader("Automatizaciones", description = "Envía los avisos de tu empresa a n8n u otra herramienta mediante webhooks firmados.",
            actions = if (list != null && list.items.size < list.maxEndpoints) ({ BpmChunkyButton("Agregar webhook", viewModel::create, Modifier.bpmPrimaryButtonWidth(), enabled = !state.busy) }) else null)
        BpmPagedList(loading = state.loading && list == null, failure = null, isEmpty = list?.items.isNullOrEmpty(), pageNumber = 1, canPrevious = false, canNext = false,
            onRetry = viewModel::refresh, onPrevious = {}, onNext = {},
            empty = { EmptyState("Sin webhooks", "Agrega la dirección de un nodo Webhook de n8n para recibir los avisos de control, temperatura, inactividad y capacitación.") }) {
            BpmGridLayout(list?.items.orEmpty()) { webhook, m ->
                BpmRecordCard(webhook.url.removePrefix("https://"), m,
                    status = when {
                        !webhook.active -> BpmRecordStatus("Pausado", BpmTone.Neutral)
                        webhook.lastDeliveryAt == null -> BpmRecordStatus("Sin envíos", BpmTone.Info)
                        webhook.lastError.isNotEmpty() -> BpmRecordStatus("Falló el último envío", BpmTone.Danger)
                        else -> BpmRecordStatus("Funcionando", BpmTone.Success)
                    },
                    subtitle = webhook.eventTypes.joinToString(", ") { WebhookRules.eventLabel(it) },
                    details = listOfNotNull(webhook.lastDeliveryAt?.let { "Último envío: ${displayDateTime(it)}${webhook.lastStatus?.let { s -> " · respuesta $s" }.orEmpty()}" }),
                    onOpen = { viewModel.edit(webhook) }, actionLabel = "Enviar prueba", onAction = { viewModel.test(webhook) },
                    textActions = listOf("Rotar secreto" to { viewModel.rotate(webhook) }, "Eliminar" to { viewModel.askDelete(webhook) }),
                    enabled = !state.busy)
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        BpmSection("Cómo verificar la firma en n8n") {
            Text("Cada aviso llega por POST con X-BPM-Signature = sha256=HMAC-SHA256(secreto, X-BPM-Timestamp + \".\" + cuerpo). " +
                "Compara la firma en un nodo Code, rechaza marcas de tiempo de más de 5 minutos y usa X-BPM-Delivery para no procesar dos veces el mismo aviso.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WebhookForm(editor: WebhookEditor, eventTypes: List<String>, busy: Boolean, viewModel: WebhooksViewModel) {
    BpmFormLayout(actions = { BpmFormActions(if (editor.id == null) "Agregar webhook" else "Guardar cambios", viewModel::save, busy = busy, onCancel = viewModel::cancelEditor) }) {
        BpmSection(if (editor.id == null) "Nuevo webhook" else "Editar webhook", description = "Usa la dirección de producción del nodo Webhook de n8n (empieza con https://).") {
            BpmTextField(editor.input.url, viewModel::editUrl, "Dirección del webhook *", Modifier.fillMaxWidth(), enabled = !busy,
                error = editor.fields["url"], inputKind = BpmInputKind.Url, placeholder = "https://n8n.tuempresa.pe/webhook/bpm")
        }
        BpmSection("¿Qué avisos envía?") {
            eventTypes.forEach { type -> BpmCheckboxRow(WebhookRules.eventLabel(type), type in editor.input.eventTypes, !busy) { viewModel.toggleEvent(type) } }
            editor.fields["eventTypes"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (editor.id != null) BpmSection("Estado") {
            BpmChoiceRow("Activo", editor.input.active, !busy, "Recibe los avisos elegidos.") { viewModel.setActive(true) }
            BpmChoiceRow("Pausado", !editor.input.active, !busy, "No recibe avisos hasta reactivarlo.") { viewModel.setActive(false) }
        }
    }
}

/** El secreto se muestra una sola vez: después solo puede rotarse. */
@Composable
private fun SecretPanel(webhook: Webhook, onClose: () -> Unit) {
    val sharer = rememberTextSharer()
    var feedback by remember(webhook.secret) { mutableStateOf<String?>(null) }
    BpmSection("Guarda este secreto", description = "Lo necesitas en n8n para verificar la firma. No volverá a mostrarse; si lo pierdes, rótalo.") {
        Text(webhook.secret.orEmpty(), style = BpmType.bodyStrong)
        BpmInlineActions(secondary = { BpmTextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Listo, ya lo guardé") } }) {
            BpmChunkyButton("Copiar secreto", { sharer.copy(webhook.secret.orEmpty()) { feedback = if (it == ShareResult.COPIED) "Secreto copiado." else "No pudimos copiarlo; selecciónalo y cópialo a mano." } }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}
