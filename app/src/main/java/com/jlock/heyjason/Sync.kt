package com.jlock.heyjason

import android.app.PendingIntent
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Small on/off settings kept on this phone only. */
object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("heyjason", 0)

    fun syncOn(ctx: Context) = p(ctx).getBoolean("syncOn", false)
    fun setSyncOn(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("syncOn", on).apply()

    /** Has this phone completed its first sync (ids checked for clashes)? */
    fun joined(ctx: Context) = p(ctx).getBoolean("syncJoined", false)
    fun setJoined(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("syncJoined", v).apply()

    fun lastSync(ctx: Context) = p(ctx).getLong("lastSync", 0L)
    fun setLastSync(ctx: Context, t: Long) = p(ctx).edit().putLong("lastSync", t).apply()

    /** "Ring on this phone" — when off, reminders still sync here but never alert. */
    fun ring(ctx: Context) = p(ctx).getBoolean("ring", true)
    fun setRing(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("ring", on).apply()
}

sealed class SyncStatus {
    object Off : SyncStatus()
    object Idle : SyncStatus()
    object Syncing : SyncStatus()
    /** Google wants Jason to tap "Sign in" again (e.g. access was removed). */
    object NeedsSignIn : SyncStatus()
    data class Failed(val message: String) : SyncStatus()
}

class NeedsSignInException(val resolution: PendingIntent?) : Exception("Sign-in needed")

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

object Sync {
    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val STATE_FILE = "state.json"
    private const val ATT_PREFIX = "att_"
    private const val WORK_NOW = "heyjason-sync-now"
    private const val WORK_PERIODIC = "heyjason-sync-periodic"

    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    val status: StateFlow<SyncStatus> = _status

    private lateinit var appCtx: Context

    /** Called once when the app process starts. */
    fun install(ctx: Context) {
        appCtx = ctx.applicationContext
        _status.value = if (Prefs.syncOn(appCtx)) SyncStatus.Idle else SyncStatus.Off
        Store.onLocalChange = { requestSoon(appCtx) }
        ShopStore.onLocalChange = { requestSoon(appCtx) }
        if (Prefs.syncOn(appCtx)) schedulePeriodic(appCtx)
    }

    fun authorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Returns a Drive access token, or throws [NeedsSignInException] if Jason must approve again. */
    suspend fun token(ctx: Context): String {
        val res: AuthorizationResult = Identity.getAuthorizationClient(ctx).authorize(authorizationRequest()).await()
        if (res.hasResolution()) throw NeedsSignInException(res.pendingIntent)
        return res.accessToken ?: throw NeedsSignInException(null)
    }

    // ---------- Scheduling ----------

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Sync shortly (a few seconds after the last change, so several quick edits go up together). */
    fun requestSoon(ctx: Context, delaySeconds: Long = 3) {
        if (!Prefs.syncOn(ctx)) return
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, req)
    }

    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun turnOn(ctx: Context) {
        Prefs.setSyncOn(ctx, true)
        _status.value = SyncStatus.Idle
        schedulePeriodic(ctx)
    }

    fun turnOff(ctx: Context) {
        Prefs.setSyncOn(ctx, false)
        Prefs.setJoined(ctx, false)
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NOW)
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_PERIODIC)
        _status.value = SyncStatus.Off
    }

    // ---------- The sync itself ----------

    /** Runs one full sync. Safe to call from the UI or the background; only one runs at a time. */
    suspend fun run(ctx: Context): SyncStatus = mutex.withLock {
        if (!Prefs.syncOn(ctx)) return SyncStatus.Off.also { _status.value = it }
        _status.value = SyncStatus.Syncing
        val result = try {
            withContext(Dispatchers.IO) {
                var tok = token(ctx)
                try {
                    syncWith(ctx, Drive(tok))
                } catch (e: UnauthorizedException) {
                    // Token went stale; clear it and try once more with a fresh one.
                    runCatching { GoogleAuthUtil.clearToken(ctx, tok) }
                    tok = token(ctx)
                    syncWith(ctx, Drive(tok))
                }
            }
            Prefs.setLastSync(ctx, System.currentTimeMillis())
            SyncStatus.Idle
        } catch (e: NeedsSignInException) {
            SyncStatus.NeedsSignIn
        } catch (e: UnauthorizedException) {
            SyncStatus.NeedsSignIn
        } catch (e: IOException) {
            SyncStatus.Failed("Couldn't reach Google Drive. Will try again soon.")
        } catch (e: Exception) {
            SyncStatus.Failed(e.message ?: "Something went wrong while syncing.")
        }
        _status.value = result
        result
    }

    private fun syncWith(ctx: Context, drive: Drive) {
        Store.init(ctx)
        ShopStore.init(ctx)
        val files = drive.list()
        val stateId = files[STATE_FILE]?.id

        // 1. Read the copy in Drive (if any).
        var remoteReminders = emptyList<Reminder>()
        var remoteRemDeleted = emptyMap<Int, Long>()
        var remoteShop = emptyList<ShopItem>()
        var remoteShopDeleted = emptyMap<Long, Long>()
        var remoteHistory = emptyMap<String, Int>()
        var remoteText: String? = null
        if (stateId != null) {
            remoteText = String(drive.download(stateId), Charsets.UTF_8)
            val o = JSONObject(remoteText)
            val ra = o.optJSONArray("reminders") ?: JSONArray()
            remoteReminders = (0 until ra.length()).map { reminderFromJson(ra.getJSONObject(it)) }
            remoteRemDeleted = o.optJSONObject("remindersDeleted").toLongMap { it.toInt() }
            val sa = o.optJSONArray("shopping") ?: JSONArray()
            remoteShop = (0 until sa.length()).map { shopItemFromJson(sa.getJSONObject(it)) }
            remoteShopDeleted = o.optJSONObject("shoppingDeleted").toLongMap { it.toLong() }
            val h = o.optJSONObject("shoppingHistory") ?: JSONObject()
            remoteHistory = h.keys().asSequence().associateWith { h.optInt(it) }
        }

        // 2. First time on this phone: renumber any reminders whose ids clash with the other phone's.
        if (!Prefs.joined(ctx)) {
            val clashes = Merge.clashingIds(Store.items.value, remoteReminders, remoteRemDeleted) { taken ->
                var n: Int
                do { n = kotlin.random.Random.nextInt(1, 400_000) } while (n in taken)
                n
            }
            for ((old, new) in clashes) {
                Scheduler.cancel(ctx, old)
                Notifs.cancel(ctx, old)
                Notifs.cancelEarly(ctx, old)
                Store.rekey(old, new)
            }
        }

        // 3. Merge.
        val before = Store.items.value.associateBy { it.id }
        val preview = Merge.reminders(Store.items.value, Store.deleted, remoteReminders, remoteRemDeleted)

        // 4. Download attachments the other phone added, before showing the reminders here.
        val incoming = preview.items.flatMap { it.attachments }.map { it.file }.toSet()
        for (name in incoming) {
            val local = File(Store.attachDir, name)
            val remote = files[ATT_PREFIX + name]
            if (!local.exists() && remote != null) {
                val tmp = File(Store.attachDir, "$name.part")
                tmp.writeBytes(drive.download(remote.id))
                tmp.renameTo(local)
            }
        }

        // 5. Save locally and fix up alarms for anything that changed.
        val rem = Store.mergeFromSync(remoteReminders, remoteRemDeleted)
        val shop = ShopStore.mergeFromSync(remoteShop, remoteHistory, remoteShopDeleted)
        val history = ShopStore.history.value
        val needed = rem.items.flatMap { it.attachments }.map { it.file }.toSet()
        val after = rem.items.associateBy { it.id }
        for ((id, old) in before) {
            if (id !in after) {
                Scheduler.cancel(ctx, id)
                Notifs.cancel(ctx, id)
                Notifs.cancelEarly(ctx, id)
                old.attachments.forEach { Store.attachmentFile(it).delete() }
            }
        }
        for ((id, r) in after) {
            val old = before[id]
            if (old != r) {
                if (r.done) {
                    Notifs.cancel(ctx, id)
                    Notifs.cancelEarly(ctx, id)
                }
                Scheduler.schedule(ctx, r)
            }
        }

        // 6. Upload the merged copy if it differs from what's in Drive.
        val out = JSONObject()
        out.put("version", 1)
        out.put("reminders", JSONArray().apply { rem.items.sortedBy { it.id }.forEach { put(reminderToJson(it)) } })
        out.put("remindersDeleted", JSONObject().apply { rem.deleted.forEach { (k, v) -> put(k.toString(), v) } })
        out.put("shopping", JSONArray().apply { shop.items.sortedBy { it.id }.forEach { put(shopItemToJson(it)) } })
        out.put("shoppingDeleted", JSONObject().apply { shop.deleted.forEach { (k, v) -> put(k.toString(), v) } })
        out.put("shoppingHistory", JSONObject().apply { history.forEach { (k, v) -> put(k, v) } })
        val outText = out.toString()
        if (!sameState(outText, remoteText)) {
            val bytes = outText.toByteArray(Charsets.UTF_8)
            if (stateId == null) drive.create(STATE_FILE, "application/json", bytes)
            else drive.update(stateId, "application/json", bytes)
        }

        // 7. Upload this phone's new attachments; remove ones no reminder uses any more.
        for (name in needed) {
            val local = File(Store.attachDir, name)
            if (local.exists() && files[ATT_PREFIX + name] == null) {
                val mime = rem.items.flatMap { it.attachments }.firstOrNull { it.file == name }?.mime
                    ?: "application/octet-stream"
                drive.create(ATT_PREFIX + name, mime, local.readBytes())
            }
        }
        for ((fname, f) in files) {
            if (fname.startsWith(ATT_PREFIX) && fname.removePrefix(ATT_PREFIX) !in needed) {
                runCatching { drive.delete(f.id) }
            }
        }

        Prefs.setJoined(ctx, true)
    }

    /** Compares two state files, ignoring key order. */
    private fun sameState(a: String, b: String?): Boolean {
        if (b == null) return false
        return try {
            normalize(JSONObject(a)) == normalize(JSONObject(b))
        } catch (e: Exception) {
            false
        }
    }

    private fun normalize(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().associateWith { normalize(v.opt(it)) }
        is JSONArray -> (0 until v.length()).map { normalize(v.opt(it)) }
        is Number -> v.toDouble()
        else -> v
    }

    private fun <K> JSONObject?.toLongMap(key: (String) -> K): Map<K, Long> {
        if (this == null) return emptyMap()
        return keys().asSequence().associate { key(it) to getLong(it) }
    }
}

class UnauthorizedException : Exception("Unauthorized")

/** Minimal Google Drive client for the app's hidden appDataFolder. */
class Drive(private val token: String) {
    data class DFile(val id: String, val name: String)

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun check(c: HttpURLConnection): HttpURLConnection {
        val code = c.responseCode
        if (code == 401) throw UnauthorizedException()
        if (code == 403) {
            val body = c.errorStream?.bufferedReader()?.readText().orEmpty()
            if ("insufficient" in body.lowercase() || "scope" in body.lowercase()) throw UnauthorizedException()
            throw IOException("Drive said no (403)")
        }
        if (code !in 200..299) throw IOException("Drive error $code")
        return c
    }

    fun list(): Map<String, DFile> {
        val out = HashMap<String, DFile>()
        var page: String? = null
        do {
            var url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&pageSize=1000" +
                "&fields=" + URLEncoder.encode("nextPageToken,files(id,name)", "UTF-8")
            if (page != null) url += "&pageToken=" + URLEncoder.encode(page, "UTF-8")
            val c = check(open(url, "GET"))
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            c.disconnect()
            val arr = o.optJSONArray("files") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                out[f.getString("name")] = DFile(f.getString("id"), f.getString("name"))
            }
            page = o.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return out
    }

    fun download(id: String): ByteArray {
        val c = check(open("https://www.googleapis.com/drive/v3/files/$id?alt=media", "GET"))
        val bytes = c.inputStream.use { it.readBytes() }
        c.disconnect()
        return bytes
    }

    fun create(name: String, mime: String, bytes: ByteArray) {
        val boundary = "heyjason" + System.nanoTime()
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put("appDataFolder")).toString()
        val c = open("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        c.outputStream.use { os ->
            os.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n".toByteArray())
            os.write("--$boundary\r\nContent-Type: $mime\r\n\r\n".toByteArray())
            os.write(bytes)
            os.write("\r\n--$boundary--\r\n".toByteArray())
        }
        check(c).disconnect()
    }

    fun update(id: String, mime: String, bytes: ByteArray) {
        // HttpURLConnection has no PATCH, so use Google's method-override header.
        val c = open("https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media&fields=id", "POST")
        c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        c.doOutput = true
        c.setRequestProperty("Content-Type", mime)
        c.outputStream.use { it.write(bytes) }
        check(c).disconnect()
    }

    fun delete(id: String) {
        val c = open("https://www.googleapis.com/drive/v3/files/$id", "DELETE")
        val code = c.responseCode
        c.disconnect()
        if (code == 401) throw UnauthorizedException()
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        ShopStore.init(applicationContext)
        return when (Sync.run(applicationContext)) {
            is SyncStatus.Failed -> if (runAttemptCount < 3) Result.retry() else Result.success()
            else -> Result.success()
        }
    }
}
