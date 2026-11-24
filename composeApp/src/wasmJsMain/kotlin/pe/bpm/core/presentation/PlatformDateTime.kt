@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package pe.bpm.core.presentation

internal actual fun localDateTime(epochMilliseconds: Long): String = browserDateTime(epochMilliseconds.toDouble())

@JsFun("(value) => new Intl.DateTimeFormat('es-PE', {dateStyle:'short', timeStyle:'short'}).format(new Date(value))")
private external fun browserDateTime(value: Double): String

internal actual fun localUtcOffsetMinutes(epochMilliseconds: Long): Int = browserUtcOffsetMinutes(epochMilliseconds.toDouble())

@JsFun("(value) => -new Date(value).getTimezoneOffset()")
private external fun browserUtcOffsetMinutes(value: Double): Int
