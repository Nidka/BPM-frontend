package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** Búsqueda controlada con resultados opcionales en el árbol principal, sin ventanas Popup.
 * El consumidor conserva consulta, permisos, espera y cancelación; el componente solo presenta.
 */
@Composable
fun BpmSearchField(
    value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false,
    error: String? = null, supportingText: String? = null, placeholder: String? = null,
    suggestions: List<String> = emptyList(), onSelect: (String) -> Unit = {},
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    navigation: BpmFieldNavigation? = null,
) {
    var expanded by remember(value, suggestions) { mutableStateOf(true) }
    var activeIndex by remember(value, suggestions) { mutableStateOf<Int?>(null) }
    val fieldFocus = navigation?.requester ?: remember { FocusRequester() }
    val visible = expanded && suggestions.isNotEmpty() && !loading && enabled
    Column(modifier.fillMaxWidth().semantics { isTraversalGroup = true }) {
        BpmTextField(value, onValueChange, label,
            modifier = (if (navigation == null) Modifier.focusRequester(fieldFocus) else Modifier)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when {
                        event.key == Key.DirectionDown && suggestions.isNotEmpty() && !loading && enabled -> {
                            activeIndex = if (visible) ((activeIndex ?: -1) + 1).coerceAtMost(suggestions.lastIndex) else 0
                            expanded = true; true
                        }
                        event.key == Key.DirectionUp && visible && activeIndex != null -> {
                            activeIndex = activeIndex?.takeIf { it > 0 }?.minus(1); true
                        }
                        (event.key == Key.Enter || event.key == Key.NumPadEnter) && visible && activeIndex != null -> {
                            val result = suggestions[activeIndex!!]
                            expanded = false; activeIndex = null; onSelect(result); true
                        }
                        event.key == Key.Escape && visible -> { expanded = false; activeIndex = null; true }
                        else -> false
                    }
                },
            enabled = enabled, error = error,
            supportingText = if (visible && activeIndex != null) "Sugerencia ${activeIndex!! + 1} de ${suggestions.size}: ${suggestions[activeIndex!!]}" else supportingText,
            placeholder = placeholder,
            keyboardOptions = keyboardOptions, navigation = navigation,
            trailingIcon = if (loading) ({ CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = "Buscando" }, strokeWidth = 2.dp) }) else null)
        if (visible) {
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(4.dp)) {
                    Text("Sugerencias", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(12.dp).semantics { heading(); liveRegion = LiveRegionMode.Polite })
                    suggestions.forEachIndexed { index, suggestion ->
                        val interaction = remember(suggestion) { MutableInteractionSource() }
                        val focused by interaction.collectIsFocusedAsState()
                        val selectSuggestion: () -> Unit = { expanded = false; onSelect(suggestion); fieldFocus.requestFocus() }
                        BpmFeedback(MaterialTheme.colorScheme.primary) {
                            Surface(onClick = selectSuggestion,
                                interactionSource = interaction,
                                shape = MaterialTheme.shapes.small,
                                color = if (activeIndex == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = if (focused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .semantics { role = Role.Button; selected = activeIndex == index }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                            Key.Enter, Key.NumPadEnter -> { selectSuggestion(); true }
                                            Key.Escape -> { expanded = false; fieldFocus.requestFocus(); true }
                                            else -> false
                                        }
                                    }) { Text(suggestion, modifier = Modifier.padding(12.dp)) }
                        }
                    }
                }
            }
        }
    }
}
