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
import androidx.compose.material3.SnackbarHostState
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
    var showCalendar by rememberSaveable { mutableStateOf(false) }
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

    fun confirm(r: Reminder) {
        scope.launch { snackbar.showSnackbar("Got it! I'll remind you ${describe(r.timeMillis)} 🐘") }
    }

    fun openReminder(r: Reminder) {
        editing = r
        isNew = false
    }

    /** New reminder; on a chosen calendar day it defaults to 9 AM (or an hour from now for today). */
    fun newReminder(title: String, day: LocalDate?, repeat: Repeat = Repeat.NONE) {
        val time = if (day == null || !day.isAfter(LocalDate.now())) defaultTime() else toMillis(day.atTime(9, 0))
        editing = Reminder(Store.nextId(), title, time, NlParser.guessEmoji(title), repeat = repeat)
        isNew = true
    }

    fun quickAdd(text: String) {
        val p = NlParser.parse(text)
        val t = p.time
        if (t == null) {
            // No time given — open the editor so Jason can pick one.
            newReminder(p.title, null, p.repeat)
        } else {
            val r = Reminder(Store.nextId(), p.title, t, NlParser.guessEmoji(p.title), repeat = p.repeat)
            Actions.save(ctx, r)
            confirm(r)
        }
    }

    fun toggle(r: Reminder) {
        if (r.done) {
            Actions.uncomplete(ctx, r)
        } else {
            Actions.complete(ctx, r)
            celebration = Pair((celebration?.first ?: 0) + 1, celebrationMessages.random())
        }
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
                )
            }
        } else if (showCalendar) {
            CalendarScreen(
                items = items,
                snackbar = snackbar,
                onList = { showCalendar = false },
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
                onCalendar = { showCalendar = true },
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
