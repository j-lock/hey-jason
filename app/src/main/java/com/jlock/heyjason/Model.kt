package com.jlock.heyjason

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

enum class Repeat(val label: String) {
    NONE("Once"), DAILY("Daily"), WEEKLY("Weekly"), MONTHLY("Monthly"), YEARLY("Yearly")
}

data class Attachment(val file: String, val name: String, val mime: String) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

data class Reminder(
    val id: Int,
    val title: String,
    val timeMillis: Long,
    val emoji: String = "🐘",
    val note: String = "",
    val repeat: Repeat = Repeat.NONE,
    val alarm: Boolean = false,
    val done: Boolean = false,
    val completedAt: Long = 0L,
    val attachments: List<Attachment> = emptyList(),
    /** Heads-up alerts, in minutes before the reminder's time. */
    val early: List<Int> = emptyList(),
)

/** "1 hour", "2 days", "15 min"… */
fun earlyLabel(minutes: Int): String {
    fun plural(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"
    return when {
        minutes >= 10080 && minutes % 10080 == 0 -> plural(minutes / 10080, "week")
        minutes >= 1440 && minutes % 1440 == 0 -> plural(minutes / 1440, "day")
        minutes >= 60 && minutes % 60 == 0 -> plural(minutes / 60, "hour")
        else -> "$minutes min"
    }
}

/** Compact form for badges: "1h", "2d", "15m". */
fun earlyShort(minutes: Int): String = when {
    minutes >= 10080 && minutes % 10080 == 0 -> "${minutes / 10080}w"
    minutes >= 1440 && minutes % 1440 == 0 -> "${minutes / 1440}d"
    minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes}m"
}

object Recurrence {
    /** Next occurrence of a repeating reminder strictly after [after]. */
    fun next(from: Long, repeat: Repeat, after: Long = System.currentTimeMillis()): Long {
        if (repeat == Repeat.NONE) return from
        val base = ZonedDateTime.ofInstant(Instant.ofEpochMilli(from), ZoneId.systemDefault())
        var i = 1L
        while (i < 100_000) {
            val t = when (repeat) {
                Repeat.DAILY -> base.plusDays(i)
                Repeat.WEEKLY -> base.plusWeeks(i)
                Repeat.MONTHLY -> base.plusMonths(i)
                Repeat.YEARLY -> base.plusYears(i)
                Repeat.NONE -> base
            }
            val ms = t.toInstant().toEpochMilli()
            if (ms > after) return ms
            i++
        }
        return from
    }
}

/** All reminders, saved as a small JSON file in the app's private storage. */
object Store {
    private var file: File? = null
    lateinit var attachDir: File
        private set

    private val _items = MutableStateFlow<List<Reminder>>(emptyList())
    val items: StateFlow<List<Reminder>> = _items

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val dir = ctx.applicationContext.filesDir
        attachDir = File(dir, "attachments").apply { mkdirs() }
        file = File(dir, "reminders.json")
        _items.value = load()
    }

    fun get(id: Int): Reminder? = _items.value.firstOrNull { it.id == id }

    @Synchronized
    fun nextId(): Int = (_items.value.maxOfOrNull { it.id } ?: 0) + 1

    @Synchronized
    fun upsert(r: Reminder) {
        val list = _items.value.filter { it.id != r.id } + r
        _items.value = list
        save(list)
    }

    @Synchronized
    fun delete(id: Int) {
        val r = get(id) ?: return
        r.attachments.forEach { attachmentFile(it).delete() }
        val list = _items.value.filter { it.id != id }
        _items.value = list
        save(list)
    }

    fun attachmentFile(a: Attachment): File = File(attachDir, a.file)

    private fun load(): List<Reminder> {
        val f = file ?: return emptyList()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val atts = o.optJSONArray("attachments") ?: JSONArray()
                val early = o.optJSONArray("early") ?: JSONArray()
                Reminder(
                    id = o.getInt("id"),
                    title = o.optString("title"),
                    timeMillis = o.getLong("time"),
                    emoji = o.optString("emoji", "🐘"),
                    note = o.optString("note"),
                    repeat = runCatching { Repeat.valueOf(o.optString("repeat")) }.getOrDefault(Repeat.NONE),
                    alarm = o.optBoolean("alarm"),
                    done = o.optBoolean("done"),
                    completedAt = o.optLong("completedAt"),
                    attachments = (0 until atts.length()).map { j ->
                        val a = atts.getJSONObject(j)
                        Attachment(a.getString("file"), a.optString("name"), a.optString("mime"))
                    },
                    early = (0 until early.length()).map { j -> early.getInt(j) },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<Reminder>) {
        val f = file ?: return
        val arr = JSONArray()
        list.forEach { r ->
            val atts = JSONArray()
            r.attachments.forEach { a ->
                val ao = JSONObject()
                ao.put("file", a.file)
                ao.put("name", a.name)
                ao.put("mime", a.mime)
                atts.put(ao)
            }
            val o = JSONObject()
            o.put("id", r.id)
            o.put("title", r.title)
            o.put("time", r.timeMillis)
            o.put("emoji", r.emoji)
            o.put("note", r.note)
            o.put("repeat", r.repeat.name)
            o.put("alarm", r.alarm)
            o.put("done", r.done)
            o.put("completedAt", r.completedAt)
            o.put("attachments", atts)
            o.put("early", JSONArray(r.early))
            arr.put(o)
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
    }
}
