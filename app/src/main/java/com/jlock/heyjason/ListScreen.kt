package com.jlock.heyjason

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalTime

@Composable
fun ListScreen(
    items: List<Reminder>,
    snackbar: SnackbarHostState,
    onOpen: (Reminder) -> Unit,
    onNew: () -> Unit,
    onQuick: (String) -> Unit,
    onToggle: (Reminder) -> Unit,
    onClearDone: () -> Unit,
    onCalendar: () -> Unit,
) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(20_000)
            value = System.currentTimeMillis()
        }
    }
    val active = items.filter { !it.done }.sortedBy { it.timeMillis }
    val overdue = active.filter { it.timeMillis <= now }
    val upcoming = active.filter { it.timeMillis > now }.groupBy { dayLabel(it.timeMillis) }
    val done = items.filter { it.done }.sortedByDescending { it.completedAt }
    var showDone by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = Ink,
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(data, containerColor = CardHi, contentColor = TextHi)
            }
        },
        bottomBar = { QuickAdd(onSubmit = onQuick, onOpenEditor = onNew) },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = pad.calculateTopPadding() + 8.dp,
                bottom = pad.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Column {
                    Spacer(Modifier.height(4.dp))
                    ViewSwitcher(calendar = false, onList = {}, onCalendar = onCalendar)
                    Header(active.size, overdue.size)
                }
            }
            if (active.isEmpty()) item(key = "empty") { EmptyState() }

            if (overdue.isNotEmpty()) {
                item(key = "h_overdue") { SectionLabel("Overdue", Coral) }
                items(overdue, key = { it.id }) { r ->
                    ReminderCard(r, now, { onOpen(r) }, { onToggle(r) }, Modifier.animateItem())
                }
            }
            upcoming.forEach { (label, list) ->
                item(key = "h_$label") { SectionLabel(label, TextLo) }
                items(list, key = { it.id }) { r ->
                    ReminderCard(r, now, { onOpen(r) }, { onToggle(r) }, Modifier.animateItem())
                }
            }
            if (done.isNotEmpty()) {
                item(key = "h_done") {
                    DoneHeader(done.size, showDone, { showDone = !showDone }, onClearDone)
                }
                if (showDone) {
                    items(done, key = { it.id }) { r ->
                        ReminderCard(r, now, { onOpen(r) }, { onToggle(r) }, Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(count: Int, overdue: Int) {
    val hour = LocalTime.now().hour
    val greet = when {
        hour < 5 -> "Up late?"
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }
    val base = when (count) {
        0 -> "Nothing to remember right now"
        1 -> "1 thing to remember"
        else -> "$count things to remember"
    }
    val sub = if (overdue > 0) "$base · $overdue overdue" else base
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(greet, color = TextLo, fontSize = 15.sp)
            Text("Hey, Jason", color = TextHi, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = if (overdue > 0) Peanut else TextLo, fontSize = 14.sp)
        }
        Elephant(84.dp)
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Elephant(170.dp)
        Spacer(Modifier.height(20.dp))
        Text("Nothing to remember.", color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("Enjoy the peanuts 🥜", color = TextLo, fontSize = 16.sp)
        Spacer(Modifier.height(20.dp))
        Text(
            "Try typing “call mom tomorrow at 5pm”\nor “take pills every day 8am” below.",
            color = TextLo,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(
        text.uppercase(),
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(top = 12.dp, start = 4.dp),
    )
}

@Composable
private fun DoneHeader(count: Int, expanded: Boolean, onToggle: () -> Unit, onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "✓ REMEMBERED · $count",
            color = Mint,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onToggle)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        )
        Text(
            if (expanded) "Hide" else "Show",
            color = TextLo,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggle)
                .padding(8.dp),
        )
        if (expanded) {
            Text(
                "Clear",
                color = Coral,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClear)
                    .padding(8.dp),
            )
        }
    }
}

@Composable
fun ReminderCard(
    r: Reminder,
    now: Long,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val overdue = !r.done && r.timeMillis <= now
    val whenText = when {
        r.done -> "Remembered · ${dayLabel(r.completedAt)}"
        overdue -> "${dayLabel(r.timeMillis)} · ${timeLabel(r.timeMillis)}"
        else -> soonLabel(r.timeMillis, now)?.let { "$it · ${timeLabel(r.timeMillis)}" } ?: timeLabel(r.timeMillis)
    }
    val badges = buildString {
        if (r.repeat != Repeat.NONE) append("  🔁 ${r.repeat.label}")
        if (r.alarm) append("  ⏰")
        if (r.attachments.isNotEmpty()) append("  📎${r.attachments.size}")
        if (r.note.isNotBlank()) append("  📝")
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Card)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBubble(r.done, onToggle)
        Spacer(Modifier.width(12.dp))
        Text(r.emoji, fontSize = 24.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                r.title,
                color = if (r.done) TextLo else TextHi,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                textDecoration = if (r.done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                whenText + badges,
                color = if (overdue) Coral else TextLo,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun QuickAdd(onSubmit: (String) -> Unit, onOpenEditor: () -> Unit) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val parsed = remember(text) { if (text.isBlank()) null else NlParser.parse(text) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) text = spoken
    }
    val submit: () -> Unit = {
        if (text.isBlank()) {
            onOpenEditor()
        } else {
            onSubmit(text.trim())
            text = ""
        }
    }

    Surface(color = Slate, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        ) {
            val p = parsed
            val pt = p?.time
            if (p != null && pt != null) {
                val rep = if (p.repeat != Repeat.NONE) " · 🔁 ${p.repeat.label}" else ""
                Text(
                    "🐘 “${p.title}” · ${describe(pt)}$rep",
                    color = Trunk,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Remind me to… e.g. dentist tue 3pm", color = TextLo) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Card,
                        unfocusedContainerColor = Card,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Peanut,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                Spacer(Modifier.width(8.dp))
                RoundButton("🎤") {
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "What should I remember?")
                    try {
                        voice.launch(i)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(ctx, "Voice input isn't available", Toast.LENGTH_SHORT).show()
                    }
                }
                Spacer(Modifier.width(8.dp))
                RoundButton(if (text.isBlank()) "+" else "➜", bg = Peanut, fg = OnPeanut) { submit() }
            }
        }
    }
}
