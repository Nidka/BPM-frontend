package pe.bpm.core.presentation

internal actual fun localUtcOffsetMinutes(epochMilliseconds: Long): Int = java.util.TimeZone.getDefault().getOffset(epochMilliseconds) / 60_000
