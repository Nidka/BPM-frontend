package pe.bpm.core.presentation

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal actual fun localDateTime(epochMilliseconds: Long): String =
    SimpleDateFormat("dd/MM/yyyy · HH:mm z", Locale.forLanguageTag("es-PE")).format(Date(epochMilliseconds))

internal actual fun localUtcOffsetMinutes(epochMilliseconds: Long): Int = java.util.TimeZone.getDefault().getOffset(epochMilliseconds) / 60_000
