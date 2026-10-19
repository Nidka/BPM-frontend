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

/** La cabecera identifica la app y su organización; las acciones de cuenta viven en Mi cuenta. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WorkspaceTopBar(
    organizationName: String?, wide: Boolean, canChangeOrganization: Boolean,
    enabled: Boolean, onToggleOrganization: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val organizationInteractions = remember { MutableInteractionSource() }
    val organizationFocused by organizationInteractions.collectIsFocusedAsState()
    TopAppBar(
        windowInsets = WindowInsets(0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        navigationIcon = {
            if (onBack != null) BpmFeedback(MaterialTheme.colorScheme.primary) {
            IconButton(onClick = onBack, enabled = enabled) {
                BpmIcon(BpmSymbol.Back, contentDescription = "Volver")
            }
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!wide && onBack == null) Brand(compact = true)
                if (canChangeOrganization) BpmFeedback(MaterialTheme.colorScheme.primary) {
                Surface(onClick = onToggleOrganization, enabled = enabled,
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.small,
                    interactionSource = organizationInteractions,
                    border = BorderStroke(2.dp, if (organizationFocused) MaterialTheme.colorScheme.primary else Color.Transparent),
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 360.dp).semantics {
                        contentDescription = "Cambiar organización. Actual: ${organizationName ?: "Sin selección"}"
                    }) {
                    WorkspaceContext(organizationName, wide, canChangeOrganization = true, modifier = Modifier.padding(horizontal = 8.dp))
                }
                } else WorkspaceContext(organizationName, wide, canChangeOrganization = false, modifier = Modifier.weight(1f))
            }
        },
    )
}

@Composable private fun WorkspaceContext(organizationName: String?, wide: Boolean,
    canChangeOrganization: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
        Text(if (wide) "Organización" else BpmBrand.name,
            style = if (wide) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
            color = if (wide) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (wide) FontWeight.Normal else FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(organizationName ?: "Mi espacio", modifier = Modifier.weight(1f, fill = false),
                style = if (wide) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
                color = if (wide) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (canChangeOrganization) BpmIcon(BpmSymbol.ChevronDown, modifier = Modifier.size(16.dp))
        }
    }
}
