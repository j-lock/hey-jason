package com.jlock.heyjason

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Turns "call mom tomorrow at 5pm" into title "Call mom" + a time.
 * Understands: today/tonight/tomorrow, weekdays, "next week", "oct 20", "10/20",
 * "the 20th", "in 10 minutes", "3pm", "15:30", "at 7", noon, morning/evening,
 * and repeats like "every day", "every monday", "weekly", "monthly", "yearly".
 */
object NlParser {
    data class Result(val title: String, val time: Long?, val repeat: Repeat)

    private const val WD_FULL = "(monday|tuesday|wednesday|thursday|friday|saturday|sunday)"
    private const val WD_ANY =
        "(mon(?:day)?|tues?(?:day)?|wed(?:nesday)?|thu(?:rs?)?(?:day)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)"
    private const val MONTH =
        "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
    private const val AMOUNT =
        "(half\\s+an?|an?|\\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty[ -]five)"

    private val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty five" to 45,
    )

    private fun dow(s: String): DayOfWeek = when (s.take(2)) {
        "mo" -> DayOfWeek.MONDAY
        "tu" -> DayOfWeek.TUESDAY
        "we" -> DayOfWeek.WEDNESDAY
        "th" -> DayOfWeek.THURSDAY
        "fr" -> DayOfWeek.FRIDAY
        "sa" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }

    private fun month(s: String): Int =
        listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
            .indexOf(s.take(3)) + 1

    private fun nextWeekday(today: LocalDate, d: DayOfWeek, allowToday: Boolean): LocalDate {
        var diff = (d.value - today.dayOfWeek.value + 7) % 7
        if (diff == 0 && !allowToday) diff = 7
        return today.plusDays(diff.toLong())
    }

    private fun futureDate(today: LocalDate, m: Int, d: Int, y: Int?): LocalDate? {
        if (m !in 1..12) return null
        val year = y?.let { if (it < 100) 2000 + it else it } ?: today.year
        val date = runCatching { LocalDate.of(year, m, d) }.getOrNull() ?: return null
        return if (y == null && date.isBefore(today)) date.plusYears(1) else date
    }

    /** Converts an hour plus optional am/pm into 0..23. Without am/pm, 1-7 is assumed to be PM. */
    private fun hour24(h: Int, meridiem: String?): Int? {
        if (meridiem.isNullOrEmpty()) {
            return when (h) {
                in 1..7 -> h + 12
                in 0..23 -> h
                else -> null
            }
        }
        if (h !in 1..12) return null
        val pm = meridiem.startsWith("p")
        return when {
            h == 12 && !pm -> 0
            h == 12 -> 12
            pm -> h + 12
            else -> h
        }
    }

    fun parse(input: String, now: LocalDateTime = LocalDateTime.now()): Result {
        val lower = String(CharArray(input.length) { input[it].lowercaseChar() })
        val removed = BooleanArray(input.length)

        fun take(pattern: String): MatchResult? {
            for (m in Regex(pattern).findAll(lower)) {
                if (m.range.none { removed[it] }) {
                    for (i in m.range) removed[i] = true
                    return m
                }
            }
            return null
        }

        val today = now.toLocalDate()
        var rep = Repeat.NONE
        var date: LocalDate? = null
        var time: LocalTime? = null
        var partTime: LocalTime? = null
        var exact: LocalDateTime? = null

        // --- Repeats ---
        take("\\b(every\\s*day|daily|each\\s+day)\\b")?.let { rep = Repeat.DAILY }
        if (rep == Repeat.NONE) take("\\b(?:every|each)\\s+$WD_ANY\\b")?.let {
            rep = Repeat.WEEKLY
            date = nextWeekday(today, dow(it.groupValues[1]), true)
        }
        if (rep == Repeat.NONE) take("\\b(every\\s*week|weekly|each\\s+week)\\b")?.let { rep = Repeat.WEEKLY }
        if (rep == Repeat.NONE) take("\\b(every\\s*month|monthly|each\\s+month)\\b")?.let { rep = Repeat.MONTHLY }
        if (rep == Repeat.NONE) take("\\b(every\\s*year|yearly|annually|each\\s+year)\\b")?.let { rep = Repeat.YEARLY }

        // --- "in 10 minutes", "in 2 days", "in half an hour" ---
        take("\\bin\\s+$AMOUNT\\s*(minutes?|mins?|m|hours?|hrs?|h|days?|d|weeks?|wks?|w|months?)\\b")?.let { m ->
            val amt = m.groupValues[1]
            val unit = m.groupValues[2]
            val half = amt.startsWith("half")
            val n = (amt.toIntOrNull() ?: numberWords[amt.replace('-', ' ')] ?: 1).toLong()
            exact = when {
                unit.startsWith("mo") -> now.plusMonths(n)
                unit.startsWith("m") -> now.plusMinutes(if (half) 30 else n)
                unit.startsWith("h") -> if (half) now.plusMinutes(30) else now.plusHours(n)
                unit.startsWith("d") -> now.plusDays(n)
                else -> now.plusWeeks(n)
            }
        }

        // --- Dates ---
        if (exact == null && date == null) {
            take("\\b(?:the\\s+)?day\\s+after\\s+(?:tomorrow|tmrw)\\b")?.let { date = today.plusDays(2) }
            if (date == null) take("\\b(tomorrow|tomorow|tommorow|tommorrow|tmrw|tmr)\\b")?.let { date = today.plusDays(1) }
            if (date == null) take("\\btoday\\b")?.let { date = today }
            if (date == null) take("\\btonight\\b")?.let {
                date = today
                partTime = LocalTime.of(20, 0)
            }
            if (date == null) take("\\b(?:(next|this|on|by|coming)\\s+)?$WD_FULL\\b")?.let {
                date = nextWeekday(today, dow(it.groupValues[2]), it.groupValues[1] == "this")
            }
            if (date == null) take("\\b(next|this|on|by)\\s+$WD_ANY\\b")?.let {
                date = nextWeekday(today, dow(it.groupValues[2]), it.groupValues[1] == "this")
            }
            // Bare short forms (not "sat"/"sun", which are common words).
            if (date == null) take("\\b(mon|tues?|wed|thu(?:rs?)?|fri)\\b")?.let {
                date = nextWeekday(today, dow(it.groupValues[1]), false)
            }
            if (date == null) take("\\b(?:this\\s+)?weekend\\b")?.let {
                date = nextWeekday(today, DayOfWeek.SATURDAY, true)
            }
            if (date == null) take("\\bnext\\s+week\\b")?.let { date = today.plusWeeks(1) }
            if (date == null) take("\\bnext\\s+month\\b")?.let { date = today.plusMonths(1) }
            if (date == null) take("\\b(?:on\\s+)?$MONTH\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?\\b")?.let {
                date = futureDate(today, month(it.groupValues[1]), it.groupValues[2].toInt(), it.groupValues[3].toIntOrNull())
            }
            if (date == null) take("\\b(?:on\\s+)?(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?$MONTH\\b")?.let {
                date = futureDate(today, month(it.groupValues[2]), it.groupValues[1].toInt(), null)
            }
            if (date == null) take("\\b(?:on\\s+)?(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b")?.let {
                date = futureDate(today, it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toIntOrNull())
            }
            if (date == null) take("\\b(?:on\\s+)?the\\s+(\\d{1,2})(?:st|nd|rd|th)\\b")?.let {
                val d = it.groupValues[1].toInt()
                val thisMonth = runCatching { today.withDayOfMonth(d) }.getOrNull()
                date = if (thisMonth != null && !thisMonth.isBefore(today)) thisMonth
                else runCatching { today.plusMonths(1).withDayOfMonth(d) }.getOrNull()
            }
        }

        // --- Times ---
        take("(?:\\bat\\s+|@\\s*)?\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.?|p\\.m\\.?)(?![a-z])")?.let { m ->
            hour24(m.groupValues[1].toInt(), m.groupValues[3])?.let { h ->
                val min = (m.groupValues[2].toIntOrNull() ?: 0).coerceIn(0, 59)
                time = LocalTime.of(h, min)
            }
        }
        if (time == null) take("(?:\\bat\\s+|@\\s*)?\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b")?.let { m ->
            hour24(m.groupValues[1].toInt(), null)?.let { h -> time = LocalTime.of(h, m.groupValues[2].toInt()) }
        }
        if (time == null) take("(?:\\bat|@)\\s*(\\d{1,2})\\b")?.let { m ->
            val h0 = m.groupValues[1].toInt()
            val h = if (h0 == 12) 12 else hour24(h0, null)
            if (h != null) time = LocalTime.of(h, 0)
        }
        if (time == null) take("\\b(?:at\\s+)?(noon|midday)\\b")?.let { time = LocalTime.NOON }
        if (time == null) take("\\b(?:at\\s+)?midnight\\b")?.let { time = LocalTime.of(23, 59) }
        take("\\b(?:in\\s+the\\s+|this\\s+)?(morning|afternoon|evening)\\b")?.let { m ->
            if (partTime == null) partTime = when (m.groupValues[1]) {
                "morning" -> LocalTime.of(9, 0)
                "afternoon" -> LocalTime.of(14, 0)
                else -> LocalTime.of(18, 0)
            }
        }
        take("\\bat\\s+night\\b")?.let { if (partTime == null) partTime = LocalTime.of(21, 0) }

        // --- Combine ---
        val d = date
        val t = time ?: partTime
        val ex = exact
        val result: LocalDateTime? = when {
            ex != null -> if (t != null && ex.toLocalDate() != today) LocalDateTime.of(ex.toLocalDate(), t) else ex
            d != null && t != null -> LocalDateTime.of(d, t).let { dt ->
                when {
                    dt.isAfter(now) || d != today -> dt
                    time == null -> now.plusHours(1)   // e.g. "tonight" said after 8pm
                    else -> dt.plusDays(1)             // that time already passed today
                }
            }
            d != null -> if (d == today) now.plusHours(1) else LocalDateTime.of(d, LocalTime.of(9, 0))
            t != null -> LocalDateTime.of(today, t).let { if (it.isAfter(now)) it else it.plusDays(1) }
            rep != Repeat.NONE -> LocalDateTime.of(today, LocalTime.of(9, 0)).let { if (it.isAfter(now)) it else it.plusDays(1) }
            else -> null
        }?.withSecond(0)?.withNano(0)

        // --- Clean up the title ---
        val sb = StringBuilder()
        input.forEachIndexed { i, c -> sb.append(if (removed[i]) ' ' else c) }
        var title = sb.toString().replace(Regex("\\s+"), " ").trim()
        title = title.replace(
            Regex("(?i)^(hey,?\\s+jason[,!.]?\\s*)?(please\\s+)?(remind\\s+me\\s+(to\\s+|about\\s+)?)?"), ""
        )
        val dangling = Regex("(?i)(\\s+|^)(at|on|by|in|the|this|next|every|each|and|for|@|,|-)$")
        val leading = Regex("(?i)^(to|on|at|by|and|,|-)\\s+")
        for (pass in 0 until 5) {
            title = title.replace(dangling, "").replace(leading, "").trim().trimEnd(',', ';', ':', '-', '@').trim()
        }
        if (title.isBlank()) title = "Reminder"
        title = title.replaceFirstChar { it.uppercaseChar() }

        val nowMs = toMillis(now)
        val millis = result?.let {
            val m = toMillis(it)
            if (rep != Repeat.NONE && m <= nowMs) Recurrence.next(m, rep, nowMs) else m
        }
        return Result(title, millis, rep)
    }

    private val emojiRules = listOf(
        "💊" to listOf("pill", "med", "medicine", "vitamin", "prescription", "pharmacy"),
        "📞" to listOf("call", "phone", "ring", "text"),
        "🛒" to listOf("buy", "grocer", "shop", "store", "pick up", "milk"),
        "🎂" to listOf("birthday", "bday", "anniversary"),
        "💪" to listOf("gym", "workout", "run", "exercise", "walk", "yoga"),
        "🩺" to listOf("doctor", "dentist", "appointment", "clinic", "checkup"),
        "💸" to listOf("pay", "bill", "rent", "invoice", "bank", "tax"),
        "💼" to listOf("meeting", "work", "email", "boss", "report", "deadline"),
        "🚗" to listOf("car", "oil change", "tire", "drive", "gas"),
        "🐶" to listOf("dog", "cat", "vet", "pet"),
        "🗑️" to listOf("garbage", "trash", "recycling", "bins"),
        "🧺" to listOf("laundry", "clean", "dishes", "vacuum"),
    )

    fun guessEmoji(title: String): String {
        val t = title.lowercase()
        for ((emoji, words) in emojiRules) {
            if (words.any { Regex("\\b" + Regex.escape(it)).containsMatchIn(t) }) return emoji
        }
        return "🐘"
    }
}
