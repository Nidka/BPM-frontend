package pe.bpm.features.incidents.presentation

import kotlin.time.Instant
import platform.Foundation.*

internal actual fun incidentDeadline(date: String, time: String, timeZone: String): String? =
    runCatching {
            require(
                date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
                    time.matches(Regex("[0-9]{2}:[0-9]{2}"))
            )
            val zone = requireNotNull(NSTimeZone.timeZoneWithName(timeZone))
            val formatter =
                NSDateFormatter().apply {
                    locale = NSLocale(localeIdentifier = "en_US_POSIX")
                    this.timeZone = zone
                    dateFormat = "yyyy-MM-dd HH:mm"
                    lenient = false
                }
            val input = "$date $time"
            val parsed = requireNotNull(formatter.dateFromString(input))
            require(formatter.stringFromDate(parsed) == input)
            val seconds = parsed.timeIntervalSince1970
            val currentOffset = zone.secondsFromGMTForDate(parsed)
            val offsets =
                listOf(-172800.0, -86400.0, 0.0, 86400.0, 172800.0)
                    .map { delta ->
                        zone.secondsFromGMTForDate(
                            NSDate.dateWithTimeIntervalSince1970(seconds + delta)
                        )
                    }
                    .distinct()
            require(
                offsets.count { offset ->
                    formatter.stringFromDate(
                        NSDate.dateWithTimeIntervalSince1970(seconds + currentOffset - offset)
                    ) == input
                } == 1
            )
            Instant.fromEpochMilliseconds((seconds * 1000).toLong()).toString()
        }
        .getOrNull()
