package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.focus.onFocusChanged
import pe.bpm.core.domain.InputRule
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp

/** Campo visual compartido; el estado, la validación y las acciones pertenecen a cada funcionalidad. */
@Composable
fun BpmTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions? = null,
    keyboardActions: KeyboardActions? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    navigation: BpmFieldNavigation? = null,
    supportingText: String? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    inputKind: BpmInputKind = BpmInputKind.Text,
    rule: InputRule? = null,
) {
    // Conserva selección y composición del IME; la funcionalidad sigue siendo dueña del texto.
    var editing by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val displayed = if (editing.text == value) editing else TextFieldValue(value, TextRange(value.length))
    SideEffect { if (editing != displayed) editing = displayed }
    val options = (keyboardOptions ?: inputKind.keyboardOptions()).forBpmField(singleLine, navigation?.let { it.next != null })
    var visited by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var editedSinceError by remember(error) { mutableStateOf(false) }
    val shownError = (if (visited || error != null) rule?.error(value) else null)
        ?: error?.takeUnless { editedSinceError }
    val onActivity = LocalUserActivity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun finishInput() { visited = true; keyboard?.hide(); focus.clearFocus() }
    fun nextInput() {
        visited = true
        val target = navigation?.next
        if (target != null) target.requestFocus() else focus.moveFocus(FocusDirection.Next)
    }
    Column(modifier.fillMaxWidth()) {
        val interactions = remember { MutableInteractionSource() }
        val colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = BpmTokens.fieldBorder,
            errorBorderColor = MaterialTheme.colorScheme.error, disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, errorContainerColor = Color.White,
            disabledContainerColor = BpmNeutralSurface,
        )
        val isError = shownError != null
        val shape = RoundedCornerShape(14.dp)
        val textColor = if (enabled) MaterialTheme.colorScheme.onSurface else BpmPlayColors.lockText
        // BasicTextField + decoración de Material: igual que `OutlinedTextField`, pero con el borde de 2 dp también en reposo.
        BasicTextField(
            value = displayed,
            onValueChange = { next ->
                editing = next
                if (next.text != value) { onActivity(); editedSinceError = true; onValueChange(next.text) }
            },
            modifier = Modifier.fillMaxWidth()
                .then(navigation?.let { Modifier.focusRequester(it.requester) } ?: Modifier)
                .onFocusChanged { state -> if (focused && !state.isFocused) visited = true; focused = state.isFocused }
                .semantics { contentDescription = label; if (shownError != null) error(shownError) }
                .defaultMinSize(minWidth = OutlinedTextFieldDefaults.MinWidth, minHeight = OutlinedTextFieldDefaults.MinHeight),
            enabled = enabled,
            textStyle = LocalTextStyle.current.merge(TextStyle(color = textColor)),
            cursorBrush = SolidColor(if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
            visualTransformation = visualTransformation,
            keyboardOptions = options,
            keyboardActions = keyboardActions ?: KeyboardActions(
                onNext = { nextInput() },
                onDone = { finishInput() },
                onSearch = { finishInput() },
            ),
            interactionSource = interactions,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = displayed.text, innerTextField = innerTextField, enabled = enabled, singleLine = singleLine,
                    visualTransformation = visualTransformation, interactionSource = interactions, isError = isError,
                    label = { Text(label) },
                    placeholder = placeholder?.let { { Text(it) } },
                    trailingIcon = trailingIcon, colors = colors,
                    container = { OutlinedTextFieldDefaults.Container(enabled, isError, interactions, colors = colors, shape = shape,
                        focusedBorderThickness = 2.dp, unfocusedBorderThickness = 2.dp) },
                )
            },
        )
        // Un nodo propio mantiene la ayuda disponible también en el árbol accesible de Compose web.
        (shownError ?: supportingText)?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall,
                color = if (shownError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
