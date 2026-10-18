package pe.bpm.core.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

// Observe input without consuming it. Used by the app and separate dialog roots.
fun Modifier.trackUserActivity(onActivity: () -> Unit): Modifier =
    onPreviewKeyEvent { onActivity(); false }.pointerInput(onActivity) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Scroll || event.changes.any { it.pressed }) onActivity()
            }
        }
    }
