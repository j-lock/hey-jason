package com.jlock.heyjason

import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters


@Composable
fun EditorScreen(
    initial: Reminder,
    isNew: Boolean,
    onClose: () -> Unit,
    onSaved: (Reminder) -> Unit,
    onDelete: (Reminder) -> Unit,
) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(initial.title) }
    var emoji by remember { mutableStateOf(initial.emoji) }
    var dateTime by remember { mutableStateOf(toLdt(initial.timeMillis)) }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var alarm by remember { mutableStateOf(initial.alarm) }
    var note by remember { mutableStateOf(initial.note) }
    var place by remember { mutableStateOf(initial.place) }
    val early = remember { mutableStateListOf<Int>().apply { addAll(initial.early) } }
    val attachments = remember { mutableStateListOf<Attachment>().apply { addAll(initial.attachments) } }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    var showFsiPrompt by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val cancel: () -> Unit = {
        // Throw away files added during this edit that were never saved.
        attachments.filter { it !in initial.attachments }.forEach { Store.attachmentFile(it).delete() }
        onClose()
    }
    val save: () -> Unit = {
        val millis = toMillis(dateTime)
        val r = initial.copy(
            title = title.trim().ifBlank { "Reminder" },
            emoji = emoji,
            timeMillis = millis,
            repeat = repeat,
            alarm = alarm,
            note = note.trim(),
            attachments = attachments.toList(),
            early = early.distinct().sortedDescending().take(Scheduler.MAX_EARLY),
            place = place,
            done = if (millis > System.currentTimeMillis()) false else initial.done,
            completedAt = if (millis > System.currentTimeMillis()) 0L else initial.completedAt,
        )
        initial.attachments.filter { it !in r.attachments }.forEach { Store.attachmentFile(it).delete() }
        onSaved(r)
    }

    BackHandler { cancel() }

    // A place shared from Google Maps lands in whichever reminder is open.
    val shared = Places.pendingShare.value
    LaunchedEffect(shared) {
        if (shared != null) {
            place = shared
            Places.pendingShare.value = null
        }
    }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingPhoto
        if (f != null) {
            if (ok && f.length() > 0) {
                val label = "Photo " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))
                attachments.add(Attachment(f.name, label, "image/jpeg"))
            } else {
                f.delete()
            }
        }
        pendingPhoto = null
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importUri(ctx, uri)?.let { attachments.add(it) }
    }
    val pickDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri(ctx, uri)?.let { attachments.add(it) }
    }

    fun pickDate() {
        DatePickerDialog(
            ctx,
            { _, y, m, d -> dateTime = dateTime.with(LocalDate.of(y, m + 1, d)) },
            dateTime.year, dateTime.monthValue - 1, dateTime.dayOfMonth,
        ).show()
    }

    fun pickTime() {
        TimePickerDialog(
            ctx,
            { _, h, min -> dateTime = dateTime.with(LocalTime.of(h, min)) },
            dateTime.hour, dateTime.minute, false,
        ).show()
    }

    fun chooseAlarm(on: Boolean) {
        alarm = on
        if (on && Build.VERSION.SDK_INT >= 34) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) showFsiPrompt = true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding(),
    ) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = cancel) { Text("Cancel", color = TextLo, fontSize = 16.sp) }
            Text(
                if (isNew) "New reminder" else "Edit reminder",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                color = TextHi,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = save) {
                Text("Save", color = Peanut, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            // WHAT
            Label("What")
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("What should I remember?", color = TextLo) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 20.sp, color = TextHi),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            // Typed a time into the title? Offer to use it.
            val titleParse = remember(title) { NlParser.parse(title) }
            val suggested = titleParse.time
            if (suggested != null && titleParse.title != title.trim()) {
                Spacer(Modifier.height(8.dp))
                Pill("✨ Set for ${describe(suggested)}") {
                    dateTime = toLdt(suggested)
                    title = titleParse.title
                    if (titleParse.repeat != Repeat.NONE) repeat = titleParse.repeat
                    titleParse.early.forEach { m -> if (m !in early) early.add(m) }
                }
            }

            Spacer(Modifier.height(12.dp))
            EmojiQuickRow(emoji) { emoji = it }

            // WHEN
            Label("When")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("📅  " + dateLabel(dateTime.toLocalDate()), Modifier.weight(1f)) { pickDate() }
                Pill("🕒  " + timeLabel(toMillis(dateTime)), Modifier.weight(1f)) { pickTime() }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val now = LocalDateTime.now().withSecond(0).withNano(0)
                Pill("In 1 hour") { dateTime = now.plusHours(1) }
                Pill("Tonight 8pm") {
                    val t = now.toLocalDate().atTime(20, 0)
                    dateTime = if (t.isAfter(now)) t else t.plusDays(1)
                }
                Pill("Tomorrow 9am") { dateTime = now.toLocalDate().plusDays(1).atTime(9, 0) }
                Pill("Saturday") {
                    dateTime = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).atTime(10, 0)
                }
                Pill("Next week") { dateTime = now.toLocalDate().plusWeeks(1).atTime(9, 0) }
            }

            // WHERE
            Label("Where")
            PlaceField(place) { place = it }

            // REPEAT
            Label("Repeat")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Repeat.values().forEach { r ->
                    Pill(r.label, selected = repeat == r) { repeat = r }
                }
            }

            // HOW
            Label("How should I get your attention?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("🔔  Notification", Modifier.weight(1f), selected = !alarm) { chooseAlarm(false) }
                Pill("⏰  Alarm", Modifier.weight(1f), selected = alarm) { chooseAlarm(true) }
            }
            Text(
                if (alarm) "Rings and fills the screen until you tap it — great for the important stuff."
                else "A regular notification with a sound.",
                color = TextLo,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )

            // HEADS-UP
            Label("Heads-up beforehand")
            val shown = (EARLY_PRESETS + early.filter { it !in EARLY_PRESETS }).distinct().sorted()
            FlowPills(shown) { m ->
                Pill("⏳ " + earlyLabel(m), selected = m in early) {
                    if (m in early) early.remove(m) else if (early.size < Scheduler.MAX_EARLY) early.add(m)
                }
            }
            val eventMs = toMillis(dateTime)
            Text(
                if (early.isEmpty()) "Only at the time itself. Pick one or more to get warned earlier."
                else "You'll hear from me " + early.sortedDescending().joinToString(", ") { m ->
                    val at = eventMs - m * 60_000L
                    describe(at) + if (at <= System.currentTimeMillis()) " (passed)" else ""
                } + ", and again at the time.",
                color = TextLo,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )

            // NOTE
            Label("Note")
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("Details, addresses, a list…", color = TextLo) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            // ATTACHMENTS
            Label("Attachments")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("📷 Camera", Modifier.weight(1f)) {
                    val f = File(Store.attachDir, "photo_${System.currentTimeMillis()}.jpg")
                    pendingPhoto = f
                    try {
                        takePhoto.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))
                    } catch (e: ActivityNotFoundException) {
                        pendingPhoto = null
                        Toast.makeText(ctx, "No camera app found", Toast.LENGTH_SHORT).show()
                    }
                }
                Pill("🖼️ Photo", Modifier.weight(1f)) {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                Pill("📄 File", Modifier.weight(1f)) {
                    try {
                        pickDoc.launch(arrayOf("*/*"))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(ctx, "No file picker found", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            attachments.toList().forEach { a ->
                AttachmentRow(a, onOpen = { openAttachment(ctx, a) }, onRemove = { attachments.remove(a) })
            }

            if (!isNew) {
                Spacer(Modifier.height(28.dp))
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("🗑️  Delete reminder", color = Coral, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showFsiPrompt) {
        AlertDialog(
            onDismissRequest = { showFsiPrompt = false },
            title = { Text("One quick permission") },
            text = {
                Text("So alarms can pop up over your lock screen, turn on “Allow full screen notifications” for Hey, Jason on the next screen.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showFsiPrompt = false
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName))
                        )
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Couldn't open settings", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Open settings", color = Peanut) }
            },
            dismissButton = {
                TextButton(onClick = { showFsiPrompt = false }) { Text("Later", color = TextLo) }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this reminder?") },
            text = { Text("Its attachments will be deleted too. Even elephants can't remember deleted things.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(initial)
                }) { Text("Delete", color = Coral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep", color = TextLo) }
            },
        )
    }
}

private val EARLY_PRESETS = listOf(5, 15, 30, 60, 120, 1440, 2880, 10080)

/** Pills that wrap onto several lines. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowPills(values: List<Int>, content: @Composable (Int) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { content(it) }
    }
}

@Composable
private fun Label(text: String) {
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
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Peanut,
    unfocusedBorderColor = CardHi,
    focusedContainerColor = Card,
    unfocusedContainerColor = Card,
    cursorColor = Peanut,
    focusedTextColor = TextHi,
    unfocusedTextColor = TextHi,
)

@Composable
private fun AttachmentRow(a: Attachment, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(a)
        Spacer(Modifier.width(12.dp))
        Text(
            a.name,
            color = TextHi,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "✕",
            color = TextLo,
            fontSize = 18.sp,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onRemove)
                .padding(10.dp),
        )
    }
}

@Composable
private fun Thumb(a: Attachment) {
    val bmp = remember(a.file) { if (a.isImage) decodeThumb(Store.attachmentFile(a), 160) else null }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
    } else {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Slate),
            contentAlignment = Alignment.Center,
        ) {
            Text(iconFor(a.mime), fontSize = 22.sp)
        }
    }
}

private fun iconFor(mime: String): String = when {
    mime.startsWith("image/") -> "🖼️"
    mime == "application/pdf" -> "📕"
    mime.startsWith("audio/") -> "🎵"
    mime.startsWith("video/") -> "🎬"
    mime.contains("sheet") || mime.contains("excel") || mime.contains("csv") -> "📊"
    mime.contains("word") || mime.contains("document") || mime.startsWith("text/") -> "📝"
    else -> "📄"
}

private fun decodeThumb(f: File, target: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }
}

/** Copies a picked file into the app's own storage so it survives the original being deleted. */
private fun importUri(ctx: Context, uri: Uri): Attachment? {
    return try {
        val cr = ctx.contentResolver
        var name = "file"
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val f = File(Store.attachDir, "${System.currentTimeMillis()}_$safe")
        val input = cr.openInputStream(uri) ?: return null
        input.use { inp -> f.outputStream().use { out -> inp.copyTo(out) } }
        Attachment(f.name, name, mime)
    } catch (e: Exception) {
        Toast.makeText(ctx, "Couldn't attach that file", Toast.LENGTH_SHORT).show()
        null
    }
}

fun openAttachment(ctx: Context, a: Attachment) {
    try {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", Store.attachmentFile(a))
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, a.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(i)
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app on your phone can open this file", Toast.LENGTH_SHORT).show()
    }
}
