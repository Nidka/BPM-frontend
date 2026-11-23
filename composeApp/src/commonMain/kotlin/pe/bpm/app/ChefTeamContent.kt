package pe.bpm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.workspace.domain.Location
import pe.bpm.features.workspace.domain.Member
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_done

/**
 * «Equipo» del jefe de cocina: lista–detalle de solo lectura con las personas de sus sedes ([team] ya viene filtrada con
 * [teamOf]). Cada fila dice rol y sede; el detalle, rol, correo y sedes en común. No hay acciones de gestión (alta, rol,
 * baja): son del administrador y el servidor las rechaza. El avance por persona no está disponible en la API, así que no
 * se muestra. Con la lista vacía explica por qué en lugar de dejar la pantalla en blanco.
 */
@Composable
internal fun ChefTeamContent(team: List<Member>, sites: List<Location>, modifier: Modifier = Modifier) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    if (team.isEmpty()) {
        BpmPlayEmpty(Res.drawable.mascot_done, "Aún no hay personas para mostrar",
            "Aquí verás a quienes trabajan en tus sedes cuando el administrador las asigne y tu cuenta pueda consultarlas.", modifier)
        return
    }
    val shown = team.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.email.contains(query.trim(), ignoreCase = true) }
    val selected = team.firstOrNull { it.id == selectedId }
    BpmListDetail(modifier, detail = selected?.let { member -> { MemberDetail(member, sites, onBack = { selectedId = null }) } },
        emptyDetail = { Text("Elige a una persona para ver sus datos.", style = BpmPlayType.sub, color = BpmPlayColors.muted) }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BpmSearchField(query, { query = it }, "Buscar personas", fullWidth = true)
            if (shown.isEmpty()) EmptyState("Sin coincidencias", "Prueba con otro nombre o correo.")
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { // lista-apilada: filas densas de personas
                shown.forEach { member ->
                    BpmPersonRow(member.name, detail = memberLine(member, sites), selected = member.id == selectedId, onClick = { selectedId = member.id })
                }
            }
        }
    }
}

@Composable
private fun MemberDetail(member: Member, sites: List<Location>, onBack: () -> Unit) {
    BpmDetailPane(member.name, subtitle = memberRoleLabel(member.role), onBack = onBack) {
        BpmSection("Datos") {
            BpmDetailFields(listOf(
                BpmDetailField("Rol", memberRoleLabel(member.role)),
                BpmDetailField("Correo", member.email),
                BpmDetailField("Sedes en común", memberSites(member, sites)),
            ))
        }
    }
}
