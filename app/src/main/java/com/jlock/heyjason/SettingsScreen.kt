package com.jlock.heyjason

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Launches Google's "allow Hey, Jason to use Drive" screen and turns sync on when approved. */
@Composable
fun rememberSignIn(onDone: (Boolean, String?) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        // Ask Google for the result even when it says "cancelled" — the error inside tells us why.
        try {
            val r = Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(res.data)
            onDone(r.accessToken != null, null)
        } catch (e: ApiException) {
            val why = when (e.statusCode) {
                10 -> "Google doesn't recognise this app yet (code 10). Check the Android client in Google Cloud: " +
                    "package com.jlock.heyjason and the SHA-1 fingerprint."
                16, 12501 -> "Sign-in was cancelled (code ${e.statusCode})."
                7 -> "No internet connection (code 7)."
                else -> "Google sign-in error, code ${e.statusCode}: ${e.message}"
            }
            Toast.makeText(ctx, why, Toast.LENGTH_LONG).show()
            onDone(false, why)
        } catch (e: Exception) {
            val why = "Sign-in failed (result ${res.resultCode}): ${e.message ?: e.javaClass.simpleName}"
            Toast.makeText(ctx, why, Toast.LENGTH_LONG).show()
            onDone(false, why)
        }
    }
    return {
        scope.launch {
            try {
                Sync.token(ctx) // already allowed → no screen needed
                onDone(true, null)
            } catch (e: NeedsSignInException) {
                val pi = e.resolution
                if (pi != null) launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else onDone(false, "Google didn't offer a sign-in screen.")
            } catch (e: ApiException) {
                val why = if (e.statusCode == 10)
                    "Google doesn't recognise this app yet (code 10). Check the Android client in Google Cloud."
                else "Google sign-in error, code ${e.statusCode}: ${e.message}"
                Toast.makeText(ctx, why, Toast.LENGTH_LONG).show()
                onDone(false, why)
            } catch (e: Exception) {
                val why = "Couldn't reach Google: ${e.message}"
                Toast.makeText(ctx, why, Toast.LENGTH_LONG).show()
                onDone(false, why)
            }
        }
    }
}

fun agoLabel(t: Long, now: Long): String {
    if (t == 0L) return "never"
    val m = (now - t) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        else -> dayLabel(t) + " at " + timeLabel(t)
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Sync.status.collectAsState()
    var ring by remember { mutableStateOf(Prefs.ring(ctx)) }
    var confirmOff by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    // Re-read after each sync finishes.
    val lastSync = remember(status, now) { Prefs.lastSync(ctx) }

    var signInError by remember { mutableStateOf<String?>(null) }
    val signIn = rememberSignIn { ok, why ->
        signInError = if (ok) null else why
        if (ok) {
            Sync.turnOn(ctx)
            scope.launch {
                val s = Sync.run(ctx)
                val msg = when (s) {
                    is SyncStatus.Idle -> "Synced with Google Drive 🐘"
                    is SyncStatus.Failed -> s.message
                    else -> "Sign-in needed"
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Back", color = TextLo, fontSize = 16.sp) }
            Text("Settings", color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            SectionTitle("Sync between your phones")
            SettingsCard {
                val (icon, line, color) = when (status) {
                    SyncStatus.Off -> Triple("☁️", "Off — everything stays on this phone only.", TextLo)
                    SyncStatus.Syncing -> Triple("🔄", "Syncing…", Trunk)
                    SyncStatus.Idle -> Triple("✅", "On · last synced ${agoLabel(lastSync, now)}", Mint)
                    SyncStatus.NeedsSignIn -> Triple("⚠️", "Paused — Google needs you to sign in again.", Peanut)
                    is SyncStatus.Failed -> Triple("⚠️", (status as SyncStatus.Failed).message, Coral)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(icon, fontSize = 22.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(line, color = color, fontSize = 15.sp, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Keeps reminders, notes, places, attachments and your shopping list the same on every " +
                        "phone signed in to your Google account. Stored privately in a hidden Hey, Jason " +
                        "folder in your Google Drive.",
                    color = TextLo,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(14.dp))
                when (status) {
                    SyncStatus.Off -> Pill("🔐  Sign in with Google", Modifier.fillMaxWidth(), selected = true) { signIn() }
                    SyncStatus.NeedsSignIn -> Pill("🔐  Sign in again", Modifier.fillMaxWidth(), selected = true) { signIn() }
                    else -> {
                        Row {
                            Pill("🔄  Sync now", Modifier.weight(1f), selected = true) {
                                scope.launch { Sync.run(ctx) }
                            }
                            Spacer(Modifier.width(8.dp))
                            Pill("Turn off", Modifier.weight(1f)) { confirmOff = true }
                        }
                    }
                }
            }
            signInError?.let { err ->
                Text(
                    "⚠️ $err",
                    color = Coral,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }
            if (status != SyncStatus.Off) {
                Text(
                    "Changes upload straight away. The other phone picks them up when it opens Hey, Jason, " +
                        "or in the background about every 15 minutes.",
                    color = TextLo,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }

            SectionTitle("Alerts")
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ring on this phone", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (ring) "Reminders, heads-ups and alarms go off here."
                            else "Quiet: reminders still show up here, but this phone won't alert.",
                            color = TextLo,
                            fontSize = 13.sp,
                        )
                    }
                    Switch(
                        checked = ring,
                        onCheckedChange = { on ->
                            ring = on
                            Prefs.setRing(ctx, on)
                            if (on) Scheduler.rescheduleAll(ctx) else Scheduler.cancelAll(ctx)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Peanut, checkedThumbColor = OnPeanut),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
            Text("Hey, Jason ${version ?: ""} 🐘", color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Turn off sync on this phone?") },
            text = { Text("Your reminders stay on this phone, and the copy in Google Drive is kept for your other phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmOff = false
                    Sync.turnOff(ctx)
                }) { Text("Turn off", color = Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Keep syncing", color = TextLo) } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        color = TextLo,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp, start = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Card)
            .padding(16.dp),
    ) { content() }
}

/** Banner on the main list when sync needs attention. */
@Composable
fun SyncBanner(onOpenSettings: () -> Unit) {
    val status by Sync.status.collectAsState()
    if (status != SyncStatus.NeedsSignIn) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Peanut.copy(alpha = 0.18f))
            .clickable(onClick = onOpenSettings)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠️", fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Text("Sync paused — tap to sign in to Google again", color = Peanut, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text("›", color = Color.White, fontSize = 20.sp)
    }
}
