package pe.bpm.features.alerts.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.displayDateTime
import pe.bpm.features.alerts.domain.*

/** La navegación al recurso pertenece a la raíz y vuelve a autorizar su destino. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertsScreen(
  viewModel: AlertsViewModel,
  modifier: Modifier = Modifier,
  onOpenSource: (Alert) -> Unit = {},
  pushControls: @Composable () -> Unit = {},
) {
  val s by viewModel.state.collectAsState()
  Column(
    modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Mis avisos", style = MaterialTheme.typography.headlineSmall)
    Text(
      "Aquí solo ves los avisos dirigidos a tu cuenta.",
      style = MaterialTheme.typography.bodyMedium,
    )
    pushControls()
    s.unreadCount?.let { count ->
      BpmStatusChip(if (count == 0) "Todo al día" else if (count == 1) "1 sin leer" else "$count sin leer", if (count == 0) BpmTone.Success else BpmTone.Warning, symbol = if (count == 0) BpmSymbol.Check else BpmSymbol.Bell)
    }
    if (s.countFailure != null) {
      Text("No pudimos consultar la cantidad de avisos sin leer.")
      BpmTextButton(viewModel::refreshCount) { Text("Reintentar contador") }
    }
    s.failure?.let { AlertError(it, viewModel::refresh) }
    if (s.loading && s.inbox.rows.isEmpty() || s.detailLoading) BpmSkeletonRecordCards(3, label = "Cargando avisos")
    val a = s.detail
    if (a != null) {
      val unread = a.viewedAt == null && a.attendedAt == null && a.status != AlertStatus.CANCELLED
      // Abrir un aviso es verlo: se marca como visto sin pedir otro toque (la persona no distingue «visto» de «leído»).
      LaunchedEffect(a.id, unread) { if (unread && !s.actionBusy && s.actionFailure == null) viewModel.markViewed() }
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BpmGuideCard(
          alertSymbol(a.type), alertTone(a.type), a.title, eyebrow = alertTypeLabel(a.type), message = a.message,
        ) {
          pe.bpm.core.offline.PendingBadge(a.id)
          if (s.actionBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
          s.actionFailure?.let { AlertError(it, viewModel::retryAction) }
          if (a.status != AlertStatus.CANCELLED) {
            if (a.attendedAt == null)
              BpmChunkyButton("Ya lo atendí", viewModel::markAttended, Modifier.fillMaxWidth(), enabled = !s.actionBusy)
            if (a.sourceAvailable)
              BpmChunkyButton("Ir a verlo", { onOpenSource(a) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = !s.actionBusy)
          }
        }
        BpmSectionCard("Detalle", BpmSymbol.Bell) {
          BpmChipRow { BpmStatusChip(alertStatusLabel(a), when { unread -> BpmTone.Warning; a.attendedAt != null -> BpmTone.Success; else -> BpmTone.Neutral },
            symbol = when { unread -> BpmSymbol.Bell; a.attendedAt != null -> BpmSymbol.Check; else -> BpmSymbol.Eye }) }
          if (a.locationName.isNotBlank()) BpmInfoRow(BpmSymbol.Company, "Sede", a.locationName)
          BpmInfoRow(BpmSymbol.Clock, "Llegó", displayDateTime(a.createdAt))
          a.viewedAt?.let { BpmInfoRow(BpmSymbol.Eye, "Visto", displayDateTime(it), tone = BpmPlayTone.Correct) }
          a.attendedAt?.let { BpmInfoRow(BpmSymbol.Check, "Atendido", displayDateTime(it), tone = BpmPlayTone.Correct) }
          if (!a.sourceAvailable && a.status != AlertStatus.CANCELLED) Text("Ya no puedes abrir lo relacionado con este aviso.", style = MaterialTheme.typography.bodySmall)
        }
      }
    } else if (!s.detailLoading) {
      BpmSearchField(s.inbox.query, viewModel::query, "Buscar avisos", loading = s.loading)
      BpmChipRow {
        AlertFilter.entries.forEach { f ->
          BpmFilterChip(
            s.filter == f,
            { viewModel.filter(f) },
            label = {
              Text(
                when (f) {
                  AlertFilter.ALL -> "Todos"
                  AlertFilter.UNREAD -> "Sin leer"
                  AlertFilter.VIEWED -> "Vistos"
                  AlertFilter.ATTENDED -> "Atendidos"
                }
              )
            },
          )
        }
      }
      if (s.inboxLoaded && s.inbox.rows.isEmpty() && !s.loading && s.failure == null)
        EmptyState("Sin avisos", "No hay avisos para esta consulta. Cambia la búsqueda o el filtro para ver otros.")
      BpmGridLayout(s.inbox.rows) { row, cardModifier ->
        AlertCard(row, { viewModel.detail(row.id) }, cardModifier, enabled = !s.loading)
      }
      if (s.inboxLoaded)
        PageNavigation(
          s.inbox.number,
          s.inbox.previous.isNotEmpty(),
          s.inbox.next.isNotEmpty(),
          s.loading,
          viewModel::previous,
          viewModel::next,
        )
    }
  }
}

/** Aviso de la bandeja: icono por tipo, estado con icono (sin leer destacado), mensaje corto y sede con fecha. */
@Composable
private fun AlertCard(a: Alert, onOpen: () -> Unit, modifier: Modifier, enabled: Boolean) {
  val unread = a.viewedAt == null && a.attendedAt == null && a.status != AlertStatus.CANCELLED
  BpmCard(modifier, onClick = onOpen, onClickLabel = "Consultar aviso", enabled = enabled) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
      BpmIconTile(alertSymbol(a.type), alertTone(a.type))
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
        Text(a.message, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
      }
      BpmIcon(BpmSymbol.ChevronRight, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    pe.bpm.core.offline.PendingBadge(a.id)
    BpmChipRow {
      BpmStatusChip(
        alertStatusLabel(a),
        when { unread -> BpmTone.Warning; a.attendedAt != null -> BpmTone.Success; else -> BpmTone.Neutral },
        symbol = when { unread -> BpmSymbol.Bell; a.attendedAt != null -> BpmSymbol.Check; else -> BpmSymbol.Eye },
      )
    }
    Text(
      listOf(a.locationName.takeIf { it.isNotBlank() }, displayDateTime(a.createdAt)).filterNotNull().joinToString(" · "),
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

internal fun alertSymbol(type: AlertType): BpmSymbol = when (type) {
  AlertType.TEMPERATURE_DANGER -> BpmSymbol.Thermometer
  AlertType.CHECKLIST_MISSING -> BpmSymbol.Control
  AlertType.ORGANIZATION_INACTIVE -> BpmSymbol.Company
  AlertType.TRAINING_ASSIGNED -> BpmSymbol.Learn
  AlertType.INCIDENT_ASSIGNED -> BpmSymbol.Wrench
}

internal fun alertTone(type: AlertType): BpmPlayTone = when (type) {
  AlertType.TEMPERATURE_DANGER -> BpmPlayTone.Error
  AlertType.CHECKLIST_MISSING -> BpmPlayTone.Gold
  AlertType.ORGANIZATION_INACTIVE -> BpmPlayTone.Streak
  AlertType.TRAINING_ASSIGNED -> BpmPlayTone.Action
  AlertType.INCIDENT_ASSIGNED -> BpmPlayTone.Streak
}

@Composable
internal fun AlertError(reason: AlertsFailure, retry: () -> Unit) {
  Text(
    alertFailureText(reason),
    color = MaterialTheme.colorScheme.error,
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
  )
  if (reason !in setOf(AlertsFailure.SESSION, AlertsFailure.DENIED))
    BpmTextButton(retry) { Text("Reintentar") }
}

internal fun alertStatusLabel(a: Alert) =
  when {
    a.status == AlertStatus.CANCELLED -> "Cancelado"
    a.attendedAt != null -> "Atendido"
    a.viewedAt != null -> "Visto"
    else -> "Sin leer"
  }

internal fun alertTypeLabel(type: AlertType) =
  when (type) {
    AlertType.CHECKLIST_MISSING -> "Control diario sin completar"
    AlertType.TEMPERATURE_DANGER -> "Temperatura en intervalo de peligro"
    AlertType.ORGANIZATION_INACTIVE -> "Empresa sin actividad"
    AlertType.TRAINING_ASSIGNED -> "Capacitación asignada"
    AlertType.INCIDENT_ASSIGNED -> "Incidencia asignada"
  }

internal fun alertFailureText(reason: AlertsFailure) =
  when (reason) {
    AlertsFailure.SESSION -> "La sesión terminó. Vuelve a ingresar."
    AlertsFailure.DENIED -> "Ya no tienes acceso a estos avisos o a esta configuración."
    AlertsFailure.NOT_FOUND -> "El aviso o la regla ya no está disponible para tu cuenta."
    AlertsFailure.INVALID -> "Revisa los datos y los requisitos de la regla."
    AlertsFailure.CONFLICT ->
      "La regla cambió en el servidor. Conservamos tu borrador; descártalo explícitamente y abre la versión actual para editarla."
    AlertsFailure.INVALID_PAGE ->
      "Esta página ya no está disponible. Vuelve a la primera página o cambia la búsqueda."
    AlertsFailure.CONNECTION ->
      "No pudimos conectar. Conservamos la información y puedes reintentar la misma operación."
    AlertsFailure.UNAVAILABLE -> "El servicio no está disponible. Reintenta en un momento."
    AlertsFailure.INVALID_RESPONSE ->
      "No pudimos verificar la respuesta del servidor. El cambio todavía no está confirmado."
    AlertsFailure.RATE_LIMITED -> "Hay muchas solicitudes. Espera un momento y reintenta."
    AlertsFailure.LOCAL -> "No pudimos guardar la copia local. Reintenta antes de salir o enviar."
    AlertsFailure.QUEUED -> "Guardado en este dispositivo. Se enviará al recuperar la conexión."
  }
