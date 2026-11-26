@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package pe.bpm.core.presentation

internal actual fun localUtcOffsetMinutes(epochMilliseconds: Long): Int = browserUtcOffsetMinutes(epochMilliseconds.toDouble())

@JsFun("(value) => -new Date(value).getTimezoneOffset()")
private external fun browserUtcOffsetMinutes(value: Double): Int
