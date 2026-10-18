package com.jlock.heyjason

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.BreakIterator

/** The everyday row shown in the editor before you open the full picker. */
val QUICK_EMOJIS = listOf(
    "🐘", "📌", "💊", "📞", "🛒", "💼", "🏠", "💪", "🎂", "🩺", "💸", "🚗", "🐶", "🗑️", "🧺", "💡", "❤️", "✈️",
)

data class EmojiEntry(val emoji: String, val words: String)

private fun e(spec: String): List<EmojiEntry> = spec.trim().lines().map { line ->
    val t = line.trim()
    val i = t.indexOf(' ')
    if (i < 0) EmojiEntry(t, "") else EmojiEntry(t.substring(0, i), t.substring(i + 1))
}

val EMOJI_CATEGORIES: List<Pair<String, List<EmojiEntry>>> = listOf(
    "Events & fun" to e("""
        🎵 music note song concert
        🎶 music notes song concert melody
        🎤 microphone sing karaoke concert show
        🎸 guitar rock concert band music
        🎹 piano keyboard music lesson
        🥁 drum band music
        🎷 saxophone jazz music
        🎻 violin orchestra symphony music
        🎺 trumpet band music
        🎧 headphones music podcast listen
        🎬 movie film cinema clapper
        🍿 popcorn movie cinema
        🎭 theatre theater play drama show
        🎟️ ticket admission event show
        🎫 ticket event pass
        🎉 party celebration tada
        🎊 confetti party celebrate
        🎈 balloon party birthday
        🎁 gift present birthday christmas
        🎂 birthday cake party
        🥳 party celebrate birthday face
        🎄 christmas tree holiday
        🎃 halloween pumpkin
        🕯️ candle dinner
        💍 ring wedding engagement anniversary
        💐 flowers bouquet anniversary
        👰 wedding bride
        🎓 graduation school class
        🏆 trophy win award
        🎮 video game gaming
        🎲 dice board game
        🧩 puzzle
        🎨 art paint painting
        📸 camera photo picture
        🎯 target goal dart
        🎳 bowling
        🎡 fair ferris wheel
        🎢 roller coaster amusement park
    """),
    "Sports & fitness" to e("""
        ⚽ soccer football game match
        🏀 basketball game raptors
        🏈 football game
        ⚾ baseball game jays
        🏒 hockey game leafs
        🎾 tennis
        🏐 volleyball
        ⛳ golf
        🏓 ping pong table tennis
        🥊 boxing
        🏋️ weights lifting gym workout
        🤸 gymnastics stretch
        🧘 yoga meditate stretch
        🏃 run running jog
        🚴 bike cycling ride
        🏊 swim swimming pool
        ⛷️ ski skiing
        🏂 snowboard
        ⛸️ skate skating
        🎣 fishing
        🥾 hike hiking boots
        🏕️ camping camp tent
        🏟️ stadium arena game event
        🏅 medal race
        💪 strong gym workout muscle
    """),
    "Food & drink" to e("""
        🍽️ dinner lunch restaurant meal reservation
        🍕 pizza
        🍔 burger
        🌮 taco
        🍣 sushi
        🍜 noodles ramen soup
        🍝 pasta spaghetti
        🥗 salad healthy
        🥪 sandwich lunch
        🍳 breakfast eggs cook
        🥞 pancakes brunch breakfast
        🍰 cake dessert
        🍪 cookie bake
        🍩 donut
        🍎 apple fruit
        🍌 banana fruit
        🥑 avocado
        🥕 carrot vegetable
        🍞 bread bakery
        🧀 cheese
        🍗 chicken
        🥩 steak meat
        🐟 fish
        ☕ coffee cafe tea
        🍵 tea
        🍺 beer pub bar
        🍷 wine
        🍸 cocktail drinks bar
        🥂 cheers celebrate toast champagne
        🧃 juice box
        🥛 milk
        💧 water drink hydrate
        🛒 groceries shopping cart
        🧑‍🍳 cook chef
    """),
    "Travel & places" to e("""
        ✈️ flight plane airport travel trip
        🛫 departure flight airport
        🚗 car drive
        🚙 suv car
        🚕 taxi uber cab
        🚌 bus
        🚆 train
        🚇 subway metro ttc
        🚲 bicycle bike
        ⛽ gas fuel station
        🅿️ parking
        🚢 cruise ship boat
        ⛵ sailing boat
        🧳 luggage suitcase pack travel
        🗺️ map trip
        📍 location pin place
        🏠 home house
        🏡 house garden yard
        🏢 office building work
        🏥 hospital
        🏦 bank
        🏫 school
        🏪 store shop
        🏨 hotel
        ⛪ church
        🏖️ beach vacation
        🏔️ mountain
        🌲 forest tree park
        🌍 world earth travel
        🛂 passport customs
    """),
    "People & health" to e("""
        🩺 doctor checkup appointment physio clinic
        💊 pill medicine meds prescription
        💉 vaccine shot needle
        🦷 dentist teeth tooth
        👓 glasses eye optometrist
        💆 massage spa physio relax
        💇 haircut barber salon hair
        💅 nails manicure
        🧠 brain think therapy
        ❤️ heart love
        🫶 love care
        👶 baby kids
        👨‍👩‍👧 family
        👫 friends couple date
        💑 couple date anniversary
        👵 grandma
        👴 grandpa
        🙏 thanks pray please
        👋 hello wave hi
        🤝 meeting deal handshake
        😴 sleep tired nap bed
        😊 happy smile
        😎 cool
        🤒 sick ill
        🛌 bed rest sleep
        🧴 lotion skincare
        🪥 toothbrush brush teeth
        🚿 shower
    """),
    "Animals & nature" to e("""
        🐘 elephant
        🐶 dog puppy walk vet
        🐱 cat kitten vet
        🐟 fish
        🐦 bird
        🐴 horse
        🐢 turtle
        🐇 rabbit bunny
        🐝 bee
        🦋 butterfly
        🌸 flower blossom spring
        🌻 sunflower garden
        🌱 plant seedling garden grow
        🪴 houseplant water plants
        🍂 fall autumn leaves
        ❄️ snow winter cold
        ☀️ sun sunny summer
        🌧️ rain weather
        🌈 rainbow
        🌙 moon night
        ⭐ star
        🔥 fire hot
    """),
    "Work & home" to e("""
        💼 work job briefcase business
        💻 laptop computer
        📱 phone mobile
        📞 call phone telephone
        ✉️ email mail letter
        📧 email
        📦 package parcel delivery
        📬 mailbox mail
        📅 calendar date schedule
        🗓️ calendar plan
        📝 note write memo
        📋 clipboard list checklist
        📊 chart report
        📚 books study read library
        📖 book read
        ✏️ pencil write homework
        🖨️ printer print
        💡 idea light bulb
        🔑 key keys lock
        🔧 wrench fix repair
        🔨 hammer build fix
        🧰 toolbox tools
        🪛 screwdriver fix
        🧹 broom sweep clean
        🧺 laundry basket
        🧽 sponge clean dishes
        🗑️ garbage trash bin
        ♻️ recycling recycle
        🛏️ bed bedroom
        🛋️ couch sofa living room
        🚪 door
        🔋 battery charge
        🔌 plug charge
        🧾 receipt bill
        💸 money pay bill
        💰 money bag savings
        💳 credit card pay
        🏧 atm cash bank
        🛠️ tools repair maintenance
        ✂️ scissors cut
    """),
    "Symbols" to e("""
        ✅ check done complete
        ☑️ checkbox task
        ❗ important urgent
        ❓ question
        ⚠️ warning caution
        🚨 alarm emergency urgent
        ⏰ alarm clock wake
        ⏳ hourglass time wait
        🔔 bell reminder notification
        📌 pin important
        ⭐ star favourite
        💯 hundred perfect
        🔴 red circle
        🟠 orange circle
        🟡 yellow circle
        🟢 green circle
        🔵 blue circle
        🟣 purple circle
        ⚫ black circle
        ⚪ white circle
        🔁 repeat
        ➕ plus add
        ❤️‍🔥 heart fire
        ✨ sparkles magic
        💤 sleep zzz
    """),
)

private const val RECENT_KEY = "recentEmojis"

fun recentEmojis(ctx: Context): List<String> =
    ctx.getSharedPreferences("heyjason", 0).getString(RECENT_KEY, "")!!
        .split("|").filter { it.isNotBlank() }

fun rememberEmojiUse(ctx: Context, emoji: String) {
    val list = (listOf(emoji) + recentEmojis(ctx).filter { it != emoji }).take(12)
    ctx.getSharedPreferences("heyjason", 0).edit().putString(RECENT_KEY, list.joinToString("|")).apply()
}

/** First visible character (handles multi-part emoji like 👨‍👩‍👧). */
private fun firstGrapheme(s: String): String {
    val t = s.trim()
    if (t.isEmpty()) return ""
    val it = BreakIterator.getCharacterInstance()
    it.setText(t)
    return t.substring(0, it.next())
}

private fun looksLikeEmoji(s: String): Boolean {
    val g = firstGrapheme(s)
    return g.isNotEmpty() && g.none { it.isLetterOrDigit() } && g.codePointAt(0) > 0x2000
}

/** The editor's emoji row: ＋ opens the full picker; recent picks come first. */
@Composable
fun EmojiQuickRow(selected: String, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    val row = remember(selected, showPicker) {
        (listOf(selected) + recentEmojis(ctx) + QUICK_EMOJIS).distinct()
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Peanut)
                .clickable { showPicker = true },
            contentAlignment = Alignment.Center,
        ) { Text("+", fontSize = 24.sp, color = OnPeanut, fontWeight = FontWeight.Bold) }
        row.forEach { em ->
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (em == selected) Peanut.copy(alpha = 0.35f) else Card)
                    .clickable { onPick(em) },
                contentAlignment = Alignment.Center,
            ) { Text(em, fontSize = 22.sp) }
        }
    }
    if (showPicker) {
        EmojiPickerDialog(
            onDismiss = { showPicker = false },
            onPick = { em ->
                rememberEmojiUse(ctx, em)
                onPick(em)
                showPicker = false
            },
        )
    }
}

@Composable
fun EmojiPickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val recents = remember { recentEmojis(ctx) }
    val q = query.trim().lowercase()
    val typedEmoji = if (looksLikeEmoji(query)) firstGrapheme(query) else null
    val sections: List<Pair<String, List<String>>> = when {
        typedEmoji != null -> emptyList()
        q.isNotEmpty() -> {
            val hits = EMOJI_CATEGORIES.flatMap { it.second }
                .filter { en -> en.words.split(' ').any { w -> w.startsWith(q) } || en.words.contains(q) }
                .map { it.emoji }.distinct()
            listOf("Matches for “$q”" to hits)
        }
        else -> (if (recents.isNotEmpty()) listOf("Recent" to recents) else emptyList()) +
            EMOJI_CATEGORIES.map { (name, list) -> name to list.map { it.emoji } }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Ink)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Pick an emoji", color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Close", color = TextLo) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search (e.g. music) or type any emoji", color = TextLo) },
                leadingIcon = { Text("🔍") },
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
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            if (typedEmoji != null) {
                Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(typedEmoji, fontSize = 72.sp)
                        Spacer(Modifier.height(12.dp))
                        Pill("Use this emoji", selected = true) { onPick(typedEmoji) }
                    }
                }
            } else if (sections.all { it.second.isEmpty() }) {
                Text(
                    "Nothing found. Tip: tap the 😀 key on your keyboard and pick any emoji — it'll show up here to use.",
                    color = TextLo,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(52.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    sections.forEach { (name, list) ->
                        if (list.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    name.uppercase(),
                                    color = TextLo,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.5.sp,
                                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp, start = 4.dp),
                                )
                            }
                            items(list) { em ->
                                Box(
                                    Modifier
                                        .aspectRatio(1f)
                                        .padding(3.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onPick(em) },
                                    contentAlignment = Alignment.Center,
                                ) { Text(em, fontSize = 28.sp) }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Not here? Tap the 😀 key on your keyboard in the search box and pick any emoji.",
                            color = TextLo,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 20.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
