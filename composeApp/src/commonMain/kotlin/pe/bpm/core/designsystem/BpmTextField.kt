package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
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
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    navigation: BpmFieldNavigation? = null,
) {
    val onActivity = LocalUserActivity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val action = when {
        keyboardOptions.imeAction != ImeAction.Default -> keyboardOptions.imeAction
        navigation != null -> if (navigation.next != null) ImeAction.Next else ImeAction.Done
        !singleLine -> ImeAction.Default
        else -> ImeAction.Done
    }
    fun finishInput() { keyboard?.hide(); focus.clearFocus() }
    fun nextInput() {
        val target = navigation?.next
        if (target != null) target.requestFocus() else focus.moveFocus(FocusDirection.Next)
    }
    OutlinedTextField(
        value = value,
        onValueChange = { next -> if (next != value) onActivity(); onValueChange(next) },
        label = { Text(label) },
        modifier = modifier.fillMaxWidth()
            .then(navigation?.let { Modifier.focusRequester(it.requester) } ?: Modifier)
            .semantics { contentDescription = label },
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(14.dp),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        keyboardOptions = keyboardOptions.copy(imeAction = action),
        keyboardActions = keyboardActions ?: KeyboardActions(
            onNext = { nextInput() },
            onDone = { finishInput() },
            onSearch = { finishInput() },
        ),
        visualTransformation = visualTransformation,
    )
}
