package com.jlock.heyjason

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val weekdayFmt = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
private val shortDateFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
private val longDateFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())

fun toLdt(millis: Long): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())

fun toMillis(ldt: LocalDateTime): Long =
    ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun dateLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when {
        d == today -> "Today"
        d == today.plusDays(1) -> "Tomorrow"
        d == today.minusDays(1) -> "Yesterday"
        d.isAfter(today) && d.isBefore(today.plusDays(7)) -> d.format(weekdayFmt)
        d.year == today.year -> d.format(shortDateFmt)
        else -> d.format(longDateFmt)
    }
}

fun dayLabel(millis: Long): String = dateLabel(toLdt(millis).toLocalDate())

fun timeLabel(millis: Long): String = toLdt(millis).format(timeFmt)

/** e.g. "today at 3:00 PM", "Thursday at 9:00 AM". */
fun describe(millis: Long): String {
    val prefix = when (val d = dayLabel(millis)) {
        "Today" -> "today"
        "Tomorrow" -> "tomorrow"
        else -> d
    }
    return "$prefix at ${timeLabel(millis)}"
}

/** "in 12 min" when it's less than an hour away, otherwise null. */
fun soonLabel(millis: Long, now: Long): String? {
    val diff = millis - now
    if (diff in 0 until 60 * 60_000L) return "in ${maxOf(1L, diff / 60_000L)} min"
    return null
}

/** About an hour from now, rounded up to the next 5 minutes. */
fun defaultTime(): Long {
    val t = LocalDateTime.now().plusHours(1).withSecond(0).withNano(0)
    val r = t.minute % 5
    return toMillis(if (r == 0) t else t.plusMinutes((5 - r).toLong()))
}
