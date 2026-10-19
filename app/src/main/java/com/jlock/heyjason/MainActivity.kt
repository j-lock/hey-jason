package com.jlock.heyjason

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val openRequest = mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        ShopStore.init(this)
        Notifs.createChannels(this)
        handleIntent(intent)
        setContent {
            HeyJasonTheme {
                HeyJasonApp(openRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getIntExtra(Scheduler.EXTRA_ID, -1) ?: -1
        if (id >= 0) openRequest.value = id
    }
}

@Composable
fun HeyJasonApp(openRequest: MutableState<Int?>) {
    val ctx = LocalContext.current
    val items by Store.items.collectAsState()
    var editing by remember { mutableStateOf<Reminder?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var celebration by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Tapped a notification → open that reminder.
    val requested = openRequest.value
    LaunchedEffect(requested) {
        if (requested != null) {
            Store.get(requested)?.let {
                editing = it
                isNew = false
            }
            openRequest.value = null
        }
    }

    // Shared a place from Google Maps with no reminder open → start a new one with it.
    val sharedPlace = Places.pendingShare.value
    LaunchedEffect(sharedPlace, editing) {
        if (sharedPlace != null && editing == null) {
            editing = Reminder(Store.nextId(), "", defaultTime())
            isNew = true
        }
    }

    fun confirm(r: Reminder) {
        val extra = when (r.early.size) {
            0 -> ""
            1 -> " (+ heads-up ${earlyLabel(r.early[0])} before)"
            else -> " (+ ${r.early.size} heads-ups)"
        }
        scope.launch { snackbar.showSnackbar("Got it! I'll remind you ${describe(r.timeMillis)}$extra 🐘") }
    }

    fun openReminder(r: Reminder) {
        editing = r
        isNew = false
    }

    /** New reminder; on a chosen calendar day it defaults to 9 AM (or an hour from now for today). */
    fun newReminder(title: String, day: LocalDate?, repeat: Repeat = Repeat.NONE, early: List<Int> = emptyList()) {
        val time = if (day == null || !day.isAfter(LocalDate.now())) defaultTime() else toMillis(day.atTime(9, 0))
        editing = Reminder(Store.nextId(), title, time, NlParser.guessEmoji(title), repeat = repeat, early = early)
        isNew = true
    }

    fun quickAdd(text: String) {
        val p = NlParser.parse(text)
        val t = p.time
        if (t == null) {
            // No time given — open the editor so Jason can pick one.
            newReminder(p.title, null, p.repeat, p.early)
        } else {
            val r = Reminder(Store.nextId(), p.title, t, NlParser.guessEmoji(p.title), repeat = p.repeat, early = p.early)
            Actions.save(ctx, r)
            confirm(r)
        }
    }

    fun uncomplete(r: Reminder) {
        Actions.uncomplete(ctx, r)
        val overdue = r.timeMillis <= System.currentTimeMillis()
        scope.launch {
            snackbar.showSnackbar(
                if (overdue) "↩ Back on your list (overdue — tap it to pick a new time)"
                else "↩ Back on your list for ${describe(r.timeMillis)}"
            )
        }
    }

    fun complete(r: Reminder) {
        val before = r // kept so it can be undone, including a repeat's previous time
        Actions.complete(ctx, r)
        celebration = Pair((celebration?.first ?: 0) + 1, celebrationMessages.random())
        scope.launch {
            val res = snackbar.showSnackbar(
                message = if (r.repeat == Repeat.NONE) "✓ “${r.title}” done" else "✓ Done — next one is ${describe(Store.get(r.id)?.timeMillis ?: r.timeMillis)}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
            if (res == SnackbarResult.ActionPerformed) {
                Actions.save(ctx, before.copy(done = false, completedAt = 0L))
            }
        }
    }

    // Ticking the circle asks first, so an accidental tap doesn't complete anything.
    var confirmDone by remember { mutableStateOf<Reminder?>(null) }

    fun toggle(r: Reminder) {
        if (r.done) uncomplete(r) else confirmDone = r
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink),
    ) {
        val e = editing
        if (e != null) {
            key(e.id) {
                EditorScreen(
                    initial = e,
                    isNew = isNew,
                    onClose = { editing = null },
                    onSaved = { r ->
                        Actions.save(ctx, r)
                        editing = null
                        if (!r.done) confirm(Store.get(r.id) ?: r)
                    },
                    onDelete = { r ->
                        Actions.delete(ctx, r.id)
                        editing = null
                    },
                    onUncomplete = { r ->
                        editing = null
                        uncomplete(r)
                    },
                )
            }
        } else if (tab == 2) {
            ShoppingScreen(snackbar = snackbar, onTab = { tab = it })
        } else if (tab == 1) {
            CalendarScreen(
                items = items,
                snackbar = snackbar,
                onTab = { tab = it },
                onOpen = { openReminder(it) },
                onNewOn = { day -> newReminder("", day) },
                onQuick = { quickAdd(it) },
                onToggle = { toggle(it) },
            )
        } else {
            ListScreen(
                items = items,
                snackbar = snackbar,
                onOpen = { openReminder(it) },
                onNew = { newReminder("", null) },
                onQuick = { quickAdd(it) },
                onToggle = { toggle(it) },
                onClearDone = {
                    items.filter { it.done }.forEach { Actions.delete(ctx, it.id) }
                },
                onTab = { tab = it },
            )
        }

        confirmDone?.let { r ->
            AlertDialog(
                onDismissRequest = { confirmDone = null },
                title = { Text("Mark as done?") },
                text = {
                    Text(
                        "${r.emoji}  ${r.title}" +
                            if (r.repeat != Repeat.NONE)
                                "\n\nThis one repeats (${r.repeat.label.lowercase()}), so it'll move to its next time."
                            else ""
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDone = null
                        complete(r)
                    }) { Text("✓ Yes, done", color = Peanut, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDone = null }) { Text("Not yet", color = TextLo) }
                },
            )
        }

        val c = celebration
        if (c != null) {
            key(c.first) {
                Celebration(c.second) { celebration = null }
            }
        }
    }
}
