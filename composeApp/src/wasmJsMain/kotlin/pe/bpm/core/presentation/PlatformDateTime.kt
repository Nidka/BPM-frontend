@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package pe.bpm.core.presentation

internal actual fun localDateTime(epochMilliseconds: Long): String = browserDateTime(epochMilliseconds.toDouble())

@JsFun("(value) => new Intl.DateTimeFormat('es-PE', {dateStyle:'short', timeStyle:'short'}).format(new Date(value))")
private external fun browserDateTime(value: Double): String

internal actual fun localMinutesOfDay(epochMilliseconds: Long): Int = browserMinutesOfDay(epochMilliseconds.toDouble())

@JsFun("(value) => { const d = new Date(value); return d.getHours() * 60 + d.getMinutes(); }")
private external fun browserMinutesOfDay(value: Double): Int
