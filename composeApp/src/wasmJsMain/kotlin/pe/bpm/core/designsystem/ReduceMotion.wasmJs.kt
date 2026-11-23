@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package pe.bpm.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Web: media query `prefers-reduced-motion`. */
@Composable
actual fun rememberSystemReduceMotion(): Boolean = remember { browserPrefersReducedMotion() }

@JsFun("() => window.matchMedia('(prefers-reduced-motion: reduce)').matches")
private external fun browserPrefersReducedMotion(): Boolean
