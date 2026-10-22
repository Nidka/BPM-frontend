package pe.bpm.core.presentation

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal actual fun localDateTime(epochMilliseconds: Long): String =
    SimpleDateFormat("dd/MM/yyyy · HH:mm z", Locale.forLanguageTag("es-PE")).format(Date(epochMilliseconds))
