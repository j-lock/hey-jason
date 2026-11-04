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

/** Where a reminder happens. lat/lon are known when picked from search; url when shared from Google Maps. */
data class Place(
    val name: String,
    val address: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val url: String = "",
) {
    val label: String get() = name.ifBlank { address }
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
    val place: Place? = null,
    /** Last time this reminder was changed on any phone (used to merge between phones). */
    val updatedAt: Long = 0L,
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
    private var deletedFile: File? = null
    lateinit var attachDir: File
        private set

    private val _items = MutableStateFlow<List<Reminder>>(emptyList())
    val items: StateFlow<List<Reminder>> = _items

    /** Deleted reminder id → when it was deleted, so a sync can delete it on the other phone too. */
    var deleted: Map<Int, Long> = emptyMap()
        private set

    /** Called after every local change (used to kick off a sync). */
    var onLocalChange: (() -> Unit)? = null

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val dir = ctx.applicationContext.filesDir
        attachDir = File(dir, "attachments").apply { mkdirs() }
        file = File(dir, "reminders.json")
        deletedFile = File(dir, "reminders_deleted.json")
        _items.value = load()
        deleted = loadDeleted()
    }

    fun get(id: Int): Reminder? = _items.value.firstOrNull { it.id == id }

    /**
     * A random id, so two phones creating reminders at the same time don't pick the same one.
     * Kept below 400,000 so notification and alarm codes derived from it never overlap.
     */
    @Synchronized
    fun nextId(): Int {
        val taken = _items.value.map { it.id }.toSet() + deleted.keys
        while (true) {
            val id = kotlin.random.Random.nextInt(1, 400_000)
            if (id !in taken) return id
        }
    }

    @Synchronized
    fun upsert(r: Reminder) {
        val stamped = r.copy(updatedAt = System.currentTimeMillis())
        val list = _items.value.filter { it.id != r.id } + stamped
        _items.value = list
        if (r.id in deleted) {
            deleted = deleted - r.id
            saveDeleted()
        }
        save(list)
        onLocalChange?.invoke()
    }

    @Synchronized
    fun delete(id: Int) {
        val r = get(id) ?: return
        r.attachments.forEach { attachmentFile(it).delete() }
        val list = _items.value.filter { it.id != id }
        _items.value = list
        deleted = deleted + (id to System.currentTimeMillis())
        save(list)
        saveDeleted()
        onLocalChange?.invoke()
    }

    /**
     * Used by sync: merges the other phone's data in (re-merging against anything changed here
     * while the sync was running) without stamping new edit times.
     */
    @Synchronized
    fun mergeFromSync(items: List<Reminder>, del: Map<Int, Long>): Merge.Result<Int, Reminder> {
        val m = Merge.reminders(_items.value, deleted, items, del)
        _items.value = m.items
        deleted = m.deleted
        save(m.items)
        saveDeleted()
        return m
    }

    /** Used when a phone first joins sync and one of its ids is already taken by the other phone. */
    @Synchronized
    fun rekey(oldId: Int, newId: Int) {
        val r = get(oldId) ?: return
        _items.value = _items.value.filter { it.id != oldId } + r.copy(id = newId)
        save(_items.value)
    }

    fun attachmentFile(a: Attachment): File = File(attachDir, a.file)

    private fun load(): List<Reminder> {
        val f = file ?: return emptyList()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i -> reminderFromJson(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<Reminder>) {
        val f = file ?: return
        val arr = JSONArray()
        list.forEach { arr.put(reminderToJson(it)) }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
    }

    private fun loadDeleted(): Map<Int, Long> {
        val f = deletedFile ?: return emptyMap()
        if (!f.exists()) return emptyMap()
        return try {
            val o = JSONObject(f.readText())
            o.keys().asSequence().associate { it.toInt() to o.getLong(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveDeleted() {
        val f = deletedFile ?: return
        val o = JSONObject()
        deleted.forEach { (k, v) -> o.put(k.toString(), v) }
        f.writeText(o.toString())
    }
}

fun reminderFromJson(o: JSONObject): Reminder {
    val atts = o.optJSONArray("attachments") ?: JSONArray()
    val early = o.optJSONArray("early") ?: JSONArray()
    return Reminder(
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
        place = o.optJSONObject("place")?.let { p ->
            Place(
                name = p.optString("name"),
                address = p.optString("address"),
                lat = if (p.has("lat")) p.optDouble("lat") else null,
                lon = if (p.has("lon")) p.optDouble("lon") else null,
                url = p.optString("url"),
            )
        },
        updatedAt = o.optLong("updated"),
    )
}

fun reminderToJson(r: Reminder): JSONObject {
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
    r.place?.let { pl ->
        val po = JSONObject()
        po.put("name", pl.name)
        po.put("address", pl.address)
        pl.lat?.let { po.put("lat", it) }
        pl.lon?.let { po.put("lon", it) }
        po.put("url", pl.url)
        o.put("place", po)
    }
    o.put("updated", r.updatedAt)
    return o
}
