package com.jlock.heyjason

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

object Places {
    /** Rough centre of southern Ontario, used when the phone's location isn't available. */
    private const val DEFAULT_LAT = 43.9
    private const val DEFAULT_LON = -79.4

    /** A place shared in from Google Maps, waiting to be dropped into the open reminder. */
    val pendingShare = mutableStateOf<Place?>(null)

    private var cachedLocation: Location? = null

    /**
     * Searches OpenStreetMap (via the free Photon service) for places matching [query],
     * nearest to [near] first. Returns an empty list on any network problem.
     */
    suspend fun search(query: String, near: Location?): List<Place> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        val lat = near?.latitude ?: DEFAULT_LAT
        val lon = near?.longitude ?: DEFAULT_LON
        val url = "https://photon.komoot.io/api/?q=" + URLEncoder.encode(q, "UTF-8") +
            "&limit=8&lang=en&lat=$lat&lon=$lon&location_bias_scale=0.4"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", "HeyJason/1.0 (personal Android reminder app)")
        }
        try {
            if (conn.responseCode != 200) return@withContext emptyList()
            parsePhoton(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    fun parsePhoton(json: String): List<Place> {
        val out = mutableListOf<Place>()
        val features = JSONObject(json).optJSONArray("features") ?: return out
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val p = f.optJSONObject("properties") ?: continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates")
            val lon = coords?.optDouble(0)
            val lat = coords?.optDouble(1)
            val street = listOf(p.optString("housenumber"), p.optString("street"))
                .filter { it.isNotBlank() }.joinToString(" ")
            val city = p.optString("city").ifBlank { p.optString("town").ifBlank { p.optString("district") } }
            val address = listOf(street, city, p.optString("state"))
                .filter { it.isNotBlank() }.distinct().joinToString(", ")
            val name = p.optString("name").ifBlank { street.ifBlank { city } }
            if (name.isBlank()) continue
            val place = Place(name = name, address = if (address == name) "" else address, lat = lat, lon = lon)
            if (out.none { it.name == place.name && it.address == place.address }) out += place
        }
        return out
    }

    /**
     * Google Maps shares text like:
     *   "Back in Motion Physio\n123 Main St, Toronto, ON\nhttps://maps.app.goo.gl/abc"
     * (sometimes just a name and a link). Returns null if it doesn't look like a place.
     */
    fun parseShared(text: String): Place? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val url = lines.firstOrNull { it.startsWith("http") } ?: Regex("https?://\\S+").find(text)?.value ?: ""
        val rest = lines.filter { !it.startsWith("http") }.map { it.replace(Regex("https?://\\S+"), "").trim() }
            .filter { it.isNotBlank() }.distinct()
        val name = rest.firstOrNull() ?: return if (url.isNotBlank()) Place("Shared place", url = url) else null
        return Place(name = name, address = rest.drop(1).joinToString(", "), url = url)
    }

    private fun query(p: Place) = listOf(p.name, p.address).filter { it.isNotBlank() }.joinToString(", ")

    /** Turn-by-turn directions in Google Maps (falls back to any maps app). */
    fun directionsIntent(p: Place): Intent {
        val dest = if (p.lat != null && p.lon != null) "${p.lat},${p.lon}" else Uri.encode(query(p))
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$dest"))
    }

    /** Shows the place on a map. */
    fun mapIntent(p: Place): Intent {
        val uri = when {
            p.url.isNotBlank() -> Uri.parse(p.url)
            p.lat != null && p.lon != null -> Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${Uri.encode(p.name)})")
            else -> Uri.parse("geo:0,0?q=" + Uri.encode(query(p)))
        }
        return Intent(Intent.ACTION_VIEW, uri)
    }

    fun open(ctx: Context, intent: Intent) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, "No maps app found", Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens Google Maps searching for [text], so Jason can find the place and share it back. */
    fun findInGoogleMaps(ctx: Context, text: String) {
        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(text.ifBlank { " " }))
        val maps = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
        try {
            ctx.startActivity(maps)
        } catch (e: Exception) {
            open(ctx, Intent(Intent.ACTION_VIEW, uri))
        }
        Toast.makeText(ctx, "Tap the place, then Share → Hey, Jason", Toast.LENGTH_LONG).show()
    }

    fun hasLocationPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Approximate current location, or null. Never blocks for more than a few seconds. */
    @SuppressLint("MissingPermission")
    suspend fun approxLocation(ctx: Context): Location? {
        cachedLocation?.let { return it }
        if (!hasLocationPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null) {
            cachedLocation = last
            return last
        }
        if (Build.VERSION.SDK_INT < 30 || providers.isEmpty()) return null
        val fresh = withTimeoutOrNull(4000) {
            suspendCancellableCoroutine<Location?> { cont ->
                try {
                    lm.getCurrentLocation(providers.first(), null, ctx.mainExecutor) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        cachedLocation = fresh
        return fresh
    }
}

/** Receives places shared from Google Maps and hands them to the reminder that's open. */
class ShareActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val text = buildString {
            intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let { append(it).append("\n") }
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { append(it) }
        }
        val place = Places.parseShared(text)
        if (place == null) {
            Toast.makeText(this, "That didn't look like a place", Toast.LENGTH_SHORT).show()
        } else {
            Places.pendingShare.value = place
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        finish()
    }
}
