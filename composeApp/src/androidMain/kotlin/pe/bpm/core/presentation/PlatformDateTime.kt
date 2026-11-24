package pe.bpm.core.presentation

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal actual fun localDateTime(epochMilliseconds: Long): String =
    SimpleDateFormat("dd/MM/yyyy · HH:mm z", Locale.forLanguageTag("es-PE")).format(Date(epochMilliseconds))

internal actual fun localMinutesOfDay(epochMilliseconds: Long): Int =
    java.util.Calendar.getInstance().apply { timeInMillis = epochMilliseconds }.let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
