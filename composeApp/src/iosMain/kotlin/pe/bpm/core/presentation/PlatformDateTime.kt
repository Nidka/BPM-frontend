package pe.bpm.core.presentation

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.dateWithTimeIntervalSince1970

internal actual fun localDateTime(epochMilliseconds: Long): String = NSDateFormatter().apply {
    locale = NSLocale("es_PE")
    dateFormat = "dd/MM/yyyy · HH:mm z"
}.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMilliseconds / 1000.0))
