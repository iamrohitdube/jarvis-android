package dev.jarvis.ui.common

import dev.jarvis.agent.DataStamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun formatTime(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    timeFmt.format(Instant.ofEpochMilli(ms).atZone(zone))

/** "10:42 AM" for today, otherwise "20 Nov 2026, 10:42 AM". */
fun formatDateTime(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    return if (t.toLocalDate() == LocalDate.now(zone)) timeFmt.format(t) else dateFmt.format(t) + ", " + timeFmt.format(t)
}

/**
 * The "last updated" label for connector data used in a reply, e.g.
 * "INDmoney · updated 10:42 AM" or "INDmoney · saved data from 10:30 AM".
 */
fun dataLabel(stamps: List<DataStamp>, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (stamps.isEmpty()) return null
    return stamps.groupBy { it.source }.map { (source, list) ->
        val oldest = list.minBy { it.updatedAt }
        val time = formatDateTime(oldest.updatedAt, zone)
        if (list.any { it.cached }) "$source · saved data from $time" else "$source · updated $time"
    }.joinToString(" · ")
}
