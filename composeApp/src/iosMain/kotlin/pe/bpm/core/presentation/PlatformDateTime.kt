package pe.bpm.core.presentation

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.dateWithTimeIntervalSince1970

internal actual fun localDateTime(epochMilliseconds: Long): String = NSDateFormatter().apply {
    locale = NSLocale("es_PE")
    dateFormat = "dd/MM/yyyy · HH:mm z"
}.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMilliseconds / 1000.0))

internal actual fun localMinutesOfDay(epochMilliseconds: Long): Int {
    val parts = platform.Foundation.NSCalendar.currentCalendar.components(
        platform.Foundation.NSCalendarUnitHour or platform.Foundation.NSCalendarUnitMinute,
        fromDate = NSDate.dateWithTimeIntervalSince1970(epochMilliseconds / 1000.0),
    )
    return (parts.hour * 60 + parts.minute).toInt()
}
