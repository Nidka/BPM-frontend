package pe.bpm.core.presentation

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable actual fun OnAppForeground(onForeground: () -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME, onEvent = onForeground)
}
