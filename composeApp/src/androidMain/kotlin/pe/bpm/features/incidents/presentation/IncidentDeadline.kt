package pe.bpm.features.incidents.presentation

import java.time.LocalDateTime
import java.time.ZoneId

internal actual fun incidentDeadline(date: String, time: String, timeZone: String): String? =
    runCatching {
            require(
                date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
                    time.matches(Regex("[0-9]{2}:[0-9]{2}"))
            )
            val local = LocalDateTime.parse("${date}T$time:00")
            val zone = ZoneId.of(timeZone)
            val offsets = zone.rules.getValidOffsets(local)
            require(offsets.size == 1)
            local.toInstant(offsets.single()).toString()
        }
        .getOrNull()
