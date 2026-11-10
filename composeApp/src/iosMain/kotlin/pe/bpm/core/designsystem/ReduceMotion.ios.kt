package pe.bpm.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/** iOS: preferencia «Reducir movimiento» de Accesibilidad. */
@Composable
actual fun rememberReduceMotion(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }
