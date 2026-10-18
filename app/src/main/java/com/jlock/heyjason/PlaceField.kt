package com.jlock.heyjason

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The "Where" section: search-as-you-type, type-your-own, recent places, or find it in Google Maps. */
@Composable
fun PlaceField(place: Place?, onChange: (Place?) -> Unit) {
    if (place != null) {
        PlaceCard(place, onRemove = { onChange(null) })
        return
    }
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val allReminders by Store.items.collectAsState()
    val recent = remember(allReminders) {
        allReminders.sortedByDescending { it.id }.mapNotNull { it.place }.distinctBy { it.label.lowercase() }.take(6)
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(text) {
        searched = false
        failed = false
        if (text.trim().length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(450) // wait until typing pauses
        searching = true
        val found = try {
            Places.search(text, Places.approxLocation(ctx))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        searching = false
        searched = true
        failed = found == null
        results = found.orEmpty()
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("Search a place or type an address", color = TextLo) },
        leadingIcon = { Text("📍", fontSize = 18.sp) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                Text(
                    "✕",
                    color = TextLo,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { text = "" }
                        .padding(10.dp),
                )
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Peanut,
            unfocusedBorderColor = CardHi,
            focusedContainerColor = Card,
            unfocusedContainerColor = Card,
            cursorColor = Peanut,
            focusedTextColor = TextHi,
            unfocusedTextColor = TextHi,
        ),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { f ->
                // Ask once for approximate location so nearby places come first. Saying no is fine.
                val prefs = ctx.getSharedPreferences("heyjason", 0)
                if (f.isFocused && !Places.hasLocationPermission(ctx) && !prefs.getBoolean("askedLocation", false)) {
                    prefs.edit().putBoolean("askedLocation", true).apply()
                    askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            },
    )

    val typed = text.trim()
    if (typed.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Slate),
        ) {
            results.forEach { p ->
                PlaceRow("📍", p.name, p.address) { onChange(p) }
            }
            PlaceRow("✏️", "Use “$typed”", "Save exactly what you typed") { onChange(Place(name = typed)) }
            val status = when {
                searching -> "Searching…"
                failed -> "Couldn't search right now. Check your connection."
                searched && results.isEmpty() -> "No matches on OpenStreetMap."
                else -> null
            }
            if (status != null) {
                Text(status, color = TextLo, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    } else if (recent.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            recent.forEach { p -> Pill("🕘 " + p.label) { onChange(p) } }
        }
    }
    Spacer(Modifier.height(8.dp))
    Pill("🗺️  Not listed? Find in Google Maps") { Places.findInGoogleMaps(ctx, typed) }
}

@Composable
private fun PlaceRow(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 18.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextHi, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = TextLo, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A chosen place, with buttons to navigate or view it. */
@Composable
fun PlaceCard(place: Place, onRemove: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Card)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📍", fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(place.label, color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (place.address.isNotBlank() && place.address != place.label) {
                    Text(place.address, color = TextLo, fontSize = 13.sp)
                }
            }
            if (onRemove != null) {
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
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("🧭 Directions", Modifier.weight(1f)) { Places.open(ctx, Places.directionsIntent(place)) }
            Pill("🗺️ Show on map", Modifier.weight(1f)) { Places.open(ctx, Places.mapIntent(place)) }
        }
    }
}
