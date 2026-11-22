package pe.bpm.screenshots

import androidx.compose.runtime.Composable
import java.lang.reflect.Proxy
import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.offline.OfflineStatus
import pe.bpm.core.offline.OfflineUiState
import pe.bpm.core.offline.PendingChangesScreen
import pe.bpm.core.offline.PendingItem
import pe.bpm.core.offline.PendingState
import pe.bpm.features.accounts.domain.AccountDevice
import pe.bpm.features.accounts.domain.AccountsRepository
import pe.bpm.features.accounts.domain.AccountsResult
import pe.bpm.features.accounts.domain.DevicePlatform
import pe.bpm.features.accounts.domain.ListMyDevices
import pe.bpm.features.accounts.domain.RecordStatus
import pe.bpm.features.accounts.domain.RevokeMyDevice
import pe.bpm.features.accounts.presentation.DevicesScreen
import pe.bpm.features.accounts.presentation.DevicesViewModel
import pe.bpm.features.alerts.domain.Alert
import pe.bpm.features.alerts.domain.AlertFilter
import pe.bpm.features.alerts.domain.AlertRule
import pe.bpm.features.alerts.domain.AlertRuleDraftRepository
import pe.bpm.features.alerts.domain.AlertRuleTransport
import pe.bpm.features.alerts.domain.AlertStatus
import pe.bpm.features.alerts.domain.AlertType
import pe.bpm.features.alerts.domain.AlertsRepository
import pe.bpm.features.alerts.domain.AlertsResult
import pe.bpm.features.alerts.domain.ManageAlerts
import pe.bpm.features.alerts.presentation.AlertsScreen
import pe.bpm.features.alerts.presentation.AlertsViewModel
import pe.bpm.features.workspace.presentation.WorkspaceDestination
import pe.bpm.screenshots.WorkerFixtures as F

/**
 * Mi cuenta, Mis dispositivos, Cambios pendientes y Mis avisos para el loop de diseño. Las dependencias que la pantalla
 * en reposo no toca se sustituyen por proxies que fallan si alguien las llama.
 */
private inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
    error("No se usa en la captura: ${method.name}")
} as T

private val devices = listOf(
    AccountDevice("dv1", F.ORG, "Cocina Andina", DevicePlatform.ANDROID, RecordStatus.ACTIVE, "2026-11-20T09:12:00Z", "2026-09-02T14:00:00Z"),
    AccountDevice("dv2", F.ORG, "Cocina Andina", DevicePlatform.WEB, RecordStatus.ACTIVE, null, "2026-10-15T08:30:00Z"),
    AccountDevice("dv3", F.ORG, "", DevicePlatform.IOS, RecordStatus.ACTIVE, "2026-11-18T19:45:00Z", "2026-11-01T11:00:00Z"),
)

private fun devicesViewModel(): DevicesViewModel {
    val repository = Proxy.newProxyInstance(AccountsRepository::class.java.classLoader, arrayOf(AccountsRepository::class.java)) { _, method, _ ->
        if (method.name == "devices") AccountsResult.Success(Page(devices)) else error("No se usa en la captura: ${method.name}")
    } as AccountsRepository
    return DevicesViewModel(ListMyDevices(repository), RevokeMyDevice(repository)).also { it.open() }
}

private fun alert(n: Int, type: AlertType, title: String, message: String, status: AlertStatus, viewed: Boolean = false) = Alert(
    id = "al$n", type = type, title = title, message = message, status = status, createdAt = "2026-11-20T0$n:15:00Z",
    viewedAt = if (viewed) "2026-11-20T10:00:00Z" else null, attendedAt = null, locationId = F.SITE, locationName = "Sede Miraflores",
    sourceType = "CHECKLIST_ASSIGNMENT", sourceId = "as$n", sourceAvailable = true,
)

private val alerts = listOf(
    alert(7, AlertType.TEMPERATURE_DANGER, "Temperatura fuera de rango en cámara 2", "La cámara 2 registró 11 °C; el límite es 5 °C.", AlertStatus.SENT),
    alert(8, AlertType.CHECKLIST_MISSING, "Control de apertura sin completar", "El control «Apertura de cocina» no se completó a las 10:00.", AlertStatus.SENT, viewed = true),
    alert(9, AlertType.TRAINING_ASSIGNED, "Nueva capacitación asignada", "Se te asignó «Alérgenos: qué debes saber».", AlertStatus.SENT),
)

private fun alertsViewModel(openDetail: Boolean): AlertsViewModel {
    val repository = object : AlertsRepository {
        override suspend fun inbox(org: String, filter: AlertFilter, page: PageRequest) = AlertsResult.Success(Page(alerts))
        override suspend fun unreadCount(org: String) = AlertsResult.Success(2)
        override suspend fun detail(org: String, id: String) = AlertsResult.Success(alerts.first { it.id == id })
        override suspend fun view(org: String, id: String) = AlertsResult.Success(alerts.first { it.id == id })
        override suspend fun attend(org: String, id: String) = AlertsResult.Success(alerts.first { it.id == id })
        override suspend fun authorizeRules(org: String, role: String) = AlertsResult.Success(Unit)
        override suspend fun rules(org: String, locationId: String?, page: PageRequest) = AlertsResult.Success(Page<AlertRule>(emptyList()))
        override suspend fun rule(org: String, locationId: String?, id: String): AlertsResult<AlertRule> = error("No se usa")
    }
    var next = 0
    return AlertsViewModel(ManageAlerts(repository, unused<AlertRuleTransport>(), unused<AlertRuleDraftRepository>()), newId = { "id-${++next}" })
        .also { it.open(F.USER, F.ORG, "WORKER"); if (openDetail) it.detail("al7") }
}

private const val NOW_MS = 1_732_000_000_000L

private val pending = OfflineUiState(
    status = OfflineStatus(online = false, pending = 3, attention = 1),
    items = listOf(
        PendingItem("p1", "Revisión «Apertura de cocina»", PendingState.WAITING, NOW_MS - 300_000, "", 0, canRetry = false, dependents = 0),
        PendingItem("p2", "Registro de limpieza de campana", PendingState.NEEDS_REVIEW, NOW_MS - 3_600_000,
            "El plan de limpieza cambió mientras no tenías conexión.", 2, canRetry = false, dependents = 1),
        PendingItem("p3", "Foto de evidencia", PendingState.FAILED, NOW_MS - 7_200_000, "No se pudo enviar la foto. Revisa tu conexión.", 3, canRetry = true, dependents = 0),
    ),
    now = NOW_MS,
)

internal val accountScreens: List<ScreenSpec> = listOf(
    ScreenSpec("cuenta-admin") { { Shell(WorkspaceDestination.Account, role = "ADMIN") } },
    ScreenSpec("cuenta-worker") { { Shell(WorkspaceDestination.Account) } },
    ScreenSpec("dispositivos") {
        val viewModel = devicesViewModel()
        val content: @Composable () -> Unit = {
            Shell(WorkspaceDestination.Account, role = "ADMIN", secondaryTitle = "Mis dispositivos", secondaryEditor = true, secondaryContent = { DevicesScreen(viewModel) })
        }
        content
    },
    ScreenSpec("pendientes") {
        { PendingChangesScreen(pending, onBack = {}, onSync = {}, onRetry = {}, onDiscard = {}, onDismissNotice = {}) }
    },
    ScreenSpec("avisos-lista") {
        val viewModel = alertsViewModel(openDetail = false)
        val content: @Composable () -> Unit = {
            Shell(WorkspaceDestination.Home, role = "ADMIN", secondaryTitle = "Mis avisos", secondaryEditor = true, secondaryContent = { AlertsScreen(viewModel) })
        }
        content
    },
    ScreenSpec("avisos-detalle") {
        val viewModel = alertsViewModel(openDetail = true)
        val content: @Composable () -> Unit = {
            Shell(WorkspaceDestination.Home, role = "ADMIN", secondaryTitle = "Mis avisos", secondaryEditor = true, secondaryContent = { AlertsScreen(viewModel) })
        }
        content
    },
)
