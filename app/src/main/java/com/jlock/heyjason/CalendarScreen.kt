package com.jlock.heyjason

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

enum class CalMode(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month") }

/** One appearance of a reminder on the calendar (repeating ones appear many times). */
data class Occ(val r: Reminder, val at: Long) {
    /** True for the actual next occurrence, which can be ticked off. */
    val isCurrent: Boolean get() = at == r.timeMillis
}

fun occurrences(items: List<Reminder>, from: LocalDate, until: LocalDate): Map<LocalDate, List<Occ>> {
    val zone = ZoneId.systemDefault()
    val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = until.atStartOfDay(zone).toInstant().toEpochMilli()
    val out = mutableListOf<Occ>()
    for (r in items) {
        if (r.repeat == Repeat.NONE || r.done) {
            if (r.timeMillis in start until end) out += Occ(r, r.timeMillis)
        } else {
            var t = r.timeMillis
            var n = 0
            while (t < end && n < 500) {
                if (t >= start) out += Occ(r, t)
                val next = Recurrence.next(r.timeMillis, r.repeat, t)
                if (next <= t) break
                t = next
                n++
            }
        }
    }
    return out.sortedBy { it.at }.groupBy { toLdt(it.at).toLocalDate() }
}

private val firstDow = WeekFields.of(Locale.getDefault()).firstDayOfWeek
private fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(firstDow))
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
private val dayFullFmt = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())
private val mdFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
private val dFmt = DateTimeFormatter.ofPattern("d", Locale.getDefault())

@Composable
fun CalendarScreen(
    items: List<Reminder>,
    snackbar: SnackbarHostState,
    onList: () -> Unit,
    onOpen: (Reminder) -> Unit,
    onNewOn: (LocalDate) -> Unit,
    onQuick: (String) -> Unit,
    onToggle: (Reminder) -> Unit,
) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val today = toLdt(now).toLocalDate()
    var mode by rememberSaveable { mutableStateOf(CalMode.MONTH) }
    var selected by rememberSaveable { mutableStateOf(LocalDate.now()) }

    fun shift(dir: Long) {
        selected = when (mode) {
            CalMode.DAY -> selected.plusDays(dir)
            CalMode.WEEK -> selected.plusWeeks(dir)
            CalMode.MONTH -> selected.plusMonths(dir)
        }
    }

    val firstOfMonth = selected.withDayOfMonth(1)
    val gridStart = weekStart(firstOfMonth)
    val weeksInGrid = ((firstOfMonth.dayOfWeek.value - firstDow.value + 7) % 7 + selected.lengthOfMonth() + 6) / 7
    val wkStart = weekStart(selected)
    // One pass covers whatever the current view needs.
    val rangeStart = minOf(gridStart, wkStart)
    val rangeEnd = maxOf(gridStart.plusDays(weeksInGrid * 7L), wkStart.plusDays(7))
    val occ = occurrences(items, rangeStart, rangeEnd)

    val title = when (mode) {
        CalMode.DAY -> selected.format(dayFullFmt)
        CalMode.WEEK -> {
            val end = wkStart.plusDays(6)
            if (end.month == wkStart.month) "${wkStart.format(mdFmt)} – ${end.format(dFmt)}"
            else "${wkStart.format(mdFmt)} – ${end.format(mdFmt)}"
        }
        CalMode.MONTH -> selected.format(monthFmt)
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost = {
            SnackbarHost(snackbar) { data -> Snackbar(data, containerColor = CardHi, contentColor = TextHi) }
        },
        bottomBar = { QuickAdd(onSubmit = onQuick, onOpenEditor = { onNewOn(selected) }) },
    ) { pad ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .pointerInput(mode) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (total > 120f) shift(-1) else if (total < -120f) shift(1)
                        },
                    ) { _, dx -> total += dx }
                },
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = pad.calculateTopPadding() + 12.dp,
                bottom = pad.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "switch") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ViewSwitcher(calendar = true, onList = onList, onCalendar = {})
                    Spacer(Modifier.weight(1f))
                    Elephant(56.dp)
                }
            }
            item(key = "modes") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Card)
                        .padding(4.dp),
                ) {
                    CalMode.values().forEach { m ->
                        Segment(m.label, mode == m, Modifier.weight(1f)) { mode = m }
                    }
                }
            }
            item(key = "nav") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NavArrow("‹") { shift(-1) }
                    Text(
                        title,
                        color = TextHi,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    NavArrow("›") { shift(1) }
                }
                if (selected != today) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Pill("Back to today") { selected = today }
                    }
                }
            }

            when (mode) {
                CalMode.MONTH -> {
                    item(key = "grid") {
                        MonthGrid(gridStart, weeksInGrid, selected, today, now, occ) { selected = it }
                    }
                    item(key = "dayLabel") { DayHeading(selected, today, null) }
                    val list = occ[selected].orEmpty()
                    if (list.isEmpty()) {
                        item(key = "free") { FreeDay { onNewOn(selected) } }
                    } else {
                        items(list, key = { "${it.r.id}_${it.at}" }) { o -> OccRow(o, now, onOpen, onToggle) }
                    }
                }

                CalMode.WEEK -> {
                    for (i in 0 until 7) {
                        val d = wkStart.plusDays(i.toLong())
                        item(key = "wk_$d") {
                            DayHeading(d, today) {
                                selected = d
                                mode = CalMode.DAY
                            }
                        }
                        val list = occ[d].orEmpty()
                        if (list.isEmpty()) {
                            item(key = "wkfree_$d") {
                                Text("Free 🥜", color = TextLo.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                            }
                        } else {
                            items(list, key = { "${it.r.id}_${it.at}" }) { o -> OccRow(o, now, onOpen, onToggle) }
                        }
                    }
                }

                CalMode.DAY -> {
                    val list = occ[selected].orEmpty()
                    if (list.isEmpty()) {
                        item(key = "dayfree") { FreeDay { onNewOn(selected) } }
                    } else {
                        items(list, key = { "${it.r.id}_${it.at}" }) { o -> OccRow(o, now, onOpen, onToggle) }
                        item(key = "addday") {
                            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                                Pill("+ Add for ${dateLabel(selected).lowercaseIfRelative()}") { onNewOn(selected) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun String.lowercaseIfRelative(): String =
    if (this == "Today" || this == "Tomorrow" || this == "Yesterday") lowercase() else this

@Composable
private fun NavArrow(symbol: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Card)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = TextHi, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MonthGrid(
    gridStart: LocalDate,
    weeks: Int,
    selected: LocalDate,
    today: LocalDate,
    now: Long,
    occ: Map<LocalDate, List<Occ>>,
    onSelect: (LocalDate) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Slate)
            .padding(8.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                Text(
                    gridStart.plusDays(i.toLong()).dayOfWeek
                        .getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()),
                    color = TextLo,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        for (w in 0 until weeks) {
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val d = gridStart.plusDays((w * 7 + i).toLong())
                    DayCell(
                        date = d,
                        inMonth = d.month == selected.month,
                        isSelected = d == selected,
                        isToday = d == today,
                        list = occ[d].orEmpty(),
                        now = now,
                        modifier = Modifier.weight(1f),
                    ) { onSelect(d) }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    list: List<Occ>,
    now: Long,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .height(56.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Peanut.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(top = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (isToday) Peanut else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                color = when {
                    isToday -> OnPeanut
                    inMonth -> TextHi
                    else -> TextLo.copy(alpha = 0.45f)
                },
                fontSize = 14.sp,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            list.take(3).forEach { o ->
                val c = when {
                    o.r.done -> Mint
                    o.at <= now -> Coral
                    else -> Trunk
                }
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (inMonth) c else c.copy(alpha = 0.4f)),
                )
            }
            if (list.size > 3) Text("+", color = TextLo, fontSize = 9.sp)
        }
    }
}

@Composable
private fun DayHeading(d: LocalDate, today: LocalDate, onClick: (() -> Unit)?) {
    val label = when (val rel = dateLabel(d)) {
        "Today", "Tomorrow", "Yesterday" -> "$rel · ${d.format(dayFullFmt)}"
        else -> d.format(dayFullFmt)
    }
    Text(
        label.uppercase(),
        color = if (d == today) Peanut else TextLo,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    )
}

@Composable
private fun FreeDay(onAdd: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Elephant(96.dp)
        Spacer(Modifier.height(10.dp))
        Text("Nothing to remember this day.", color = TextLo, fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        Pill("+ Add a reminder", onClick = onAdd)
    }
}

@Composable
private fun OccRow(o: Occ, now: Long, onOpen: (Reminder) -> Unit, onToggle: (Reminder) -> Unit) {
    val r = o.r
    val overdue = !r.done && o.at <= now
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Card)
            .clickable { onOpen(r) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            timeLabel(o.at),
            color = if (overdue) Coral else Trunk,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(72.dp),
        )
        Text(r.emoji, fontSize = 22.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                r.title,
                color = if (r.done) TextLo else TextHi,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textDecoration = if (r.done) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val badges = buildString {
                if (r.repeat != Repeat.NONE) append("🔁 ${r.repeat.label}  ")
                if (r.alarm) append("⏰  ")
                if (r.early.isNotEmpty()) append("⏳" + r.early.sortedDescending().joinToString(",") { earlyShort(it) } + "  ")
                if (r.attachments.isNotEmpty()) append("📎${r.attachments.size}  ")
                if (r.note.isNotBlank()) append("📝")
            }
            if (badges.isNotBlank()) Text(badges.trim(), color = TextLo, fontSize = 12.sp, maxLines = 1)
        }
        if (o.isCurrent || r.done) {
            Spacer(Modifier.width(8.dp))
            CheckBubble(r.done) { onToggle(r) }
        }
    }
}
