package pe.bpm.core.presentation

import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970

internal actual fun localUtcOffsetMinutes(epochMilliseconds: Long): Int =
    (platform.Foundation.NSCalendar.currentCalendar.timeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMilliseconds / 1000.0)) / 60).toInt()
