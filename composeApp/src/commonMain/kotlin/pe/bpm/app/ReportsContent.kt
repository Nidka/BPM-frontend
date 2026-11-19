package pe.bpm.app

import pe.bpm.core.designsystem.BpmFilterChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.BpmSpacing
import pe.bpm.features.reports.domain.ReportAccess
import pe.bpm.features.reports.presentation.*

/**
 * Pantalla de reportes. La raíz entrega persona, empresa, rol y sedes; los datos de personas
 * (capacitación) y las constancias solo se ofrecen a administración y el servidor lo vuelve a validar.
 */
@Composable
internal fun ReportsContent(
    userId: String, organizationId: String, role: String?, locations: List<ReportLocationOption>,
    reports: ReportsViewModel, admin: ReportsAdminViewModel, modifier: Modifier = Modifier,
) {
    val reportsState by reports.state.collectAsStateWithLifecycle()
    val adminState by admin.state.collectAsStateWithLifecycle()
    val canAdminister = ReportAccess.canAdminister(role)
    var section by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(userId, organizationId, role) {
        reports.open(userId, organizationId, role)
        if (canAdminister) admin.open(userId, organizationId, role)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        if (canAdminister) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BpmFilterChip(selected = section == 0, onClick = { section = 0 }, label = { Text("Cumplimiento") }, modifier = Modifier.heightIn(min = 48.dp))
            BpmFilterChip(selected = section == 1, onClick = { section = 1 }, label = { Text("Equipo y constancias") }, modifier = Modifier.heightIn(min = 48.dp))
        }
        if (section == 0 || !canAdminister) ReportsScreen(reports, reportsState, locations)
        else ReportsAdminScreen(admin, adminState)
    }
}
