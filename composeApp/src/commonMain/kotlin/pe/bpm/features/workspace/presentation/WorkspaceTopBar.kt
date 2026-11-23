package pe.bpm.features.workspace.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*

/** La cabecera identifica la app y su organización (igual para todos los roles); las acciones de cuenta viven en Mi cuenta o en Perfil. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WorkspaceTopBar(
    organizationName: String?, wide: Boolean, windowClass: BpmWindowClass, canChangeOrganization: Boolean,
    enabled: Boolean, onToggleOrganization: () -> Unit,
    onBack: (() -> Unit)? = null,
 onAlerts: (() -> Unit)? = null, unreadCount:Int? = null,
    onAccount: (() -> Unit)? = null,
    /** «Ajustes» (engranaje): accesibilidad, sonido y cuenta; disponible en todos los roles. */
    onSettings: (() -> Unit)? = null,
    /** Modo enfoque: no nulo cambia la cabecera a cerrar (X) + este título, sin avisos ni cuenta. */
    focusTitle: String? = null,
) {
    val organizationInteractions = remember { MutableInteractionSource() }
    val organizationFocused by organizationInteractions.collectIsFocusedAsState()
    // TopAppBar reserva 16 dp laterales propios; se completa hasta el margen común del contenido.
    Surface(color = MaterialTheme.colorScheme.surface) {
    TopAppBar(
        modifier = Modifier.padding(horizontal = (BpmSpacing.gutter(windowClass) - 16.dp).coerceAtLeast(0.dp)),
        windowInsets = WindowInsets(0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (onBack != null) BpmFeedback(MaterialTheme.colorScheme.primary) {
            IconButton(onClick = onBack, enabled = enabled) {
                if (focusTitle != null) BpmIcon(BpmSymbol.Close, contentDescription = "Cerrar") else BpmIcon(BpmSymbol.Back, contentDescription = "Volver")
            }
            }
        },
        actions = {
            if(onAlerts!=null && focusTitle == null) BpmFeedback(MaterialTheme.colorScheme.primary) {
                IconButton(onClick=onAlerts,enabled=enabled,modifier=Modifier.semantics{contentDescription=if(unreadCount==null) "Mis avisos" else "Mis avisos. $unreadCount sin leer"}) {
                    BadgedBox(badge={if(unreadCount!=null && unreadCount>0) Badge{Text(if(unreadCount>99) "99+" else unreadCount.toString())}}){BpmIcon(BpmSymbol.Bell)}
                }
            }
            if (onSettings != null && focusTitle == null) BpmFeedback(MaterialTheme.colorScheme.primary) {
                IconButton(onClick = onSettings, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Ajustes" }) {
                    BpmIcon(BpmSymbol.Settings)
                }
            }
            // «Mi cuenta» sale de la navegación principal y vive en el avatar de la cabecera.
            if (onAccount != null && focusTitle == null) BpmFeedback(MaterialTheme.colorScheme.primary) {
                IconButton(onClick = onAccount, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Mi cuenta" }) {
                    BpmIcon(BpmSymbol.Account)
                }
            }
        },
        title = {
            if (focusTitle != null) Text(focusTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!wide && onBack == null) Brand(compact = true)
                if (canChangeOrganization) BpmFeedback(MaterialTheme.colorScheme.primary) {
                Surface(onClick = onToggleOrganization, enabled = enabled,
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.small,
                    interactionSource = organizationInteractions,
                    border = BorderStroke(2.dp, if (organizationFocused) MaterialTheme.colorScheme.primary else Color.Transparent),
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 360.dp).offset(x = (-8).dp).semantics { // medida: nombre de la organización en la cabecera
                        contentDescription = "Cambiar organización. Actual: ${organizationName ?: "Sin selección"}"
                    }) {
                    WorkspaceContext(organizationName, canChangeOrganization = true, modifier = Modifier.padding(horizontal = 8.dp))
                }
                } else WorkspaceContext(organizationName, canChangeOrganization = false, modifier = Modifier.weight(1f))
            }
        },
    )
    }
}

/** Nombre de la organización en Nunito negrita, igual para todos los roles (sin etiqueta «Organización»). */
@Composable private fun WorkspaceContext(organizationName: String?, canChangeOrganization: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(organizationName ?: "Mi espacio", modifier = Modifier.weight(1f, fill = false), style = BpmPlayType.h2,
            color = BpmPlayColors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (canChangeOrganization) BpmIcon(BpmSymbol.ChevronDown, modifier = Modifier.size(16.dp))
    }
}
