package com.jlock.heyjason

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

val celebrationMessages = listOf(
    "One less thing on your trunk.",
    "Elephant-level memory. 🐘",
    "Peanuts for you! 🥜",
    "Jumbo job, Jason!",
    "Done and dusted.",
    "Your memory is huge. Like, elephant huge.",
    "Trunk-tastic!",
    "Nailed it. The herd is proud.",
)

/** The mascot, with an optional gentle bob. */
@Composable
fun Elephant(size: Dp, modifier: Modifier = Modifier, bob: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "bob")
    val y by transition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "y",
    )
    Image(
        painter = painterResource(R.drawable.elephant_art),
        contentDescription = "Elephant",
        modifier = modifier
            .size(size)
            .graphicsLayer { translationY = if (bob) y.dp.toPx() else 0f },
    )
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Peanut else Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            color = if (selected) OnPeanut else TextHi,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
fun Segment(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Peanut else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) OnPeanut else TextLo,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** The 📋 List / 📅 Calendar toggle at the top of the main screens. */
@Composable
fun ViewSwitcher(calendar: Boolean, onList: () -> Unit, onCalendar: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Card)
            .padding(4.dp),
    ) {
        Segment("📋 List", !calendar, onClick = onList)
        Segment("📅 Calendar", calendar, onClick = onCalendar)
    }
}

@Composable
fun RoundButton(
    label: String,
    bg: Color = Card,
    fg: Color = TextHi,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 22.sp, color = fg, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun CheckBubble(checked: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (checked) Mint else Color.Transparent)
            .border(2.dp, if (checked) Mint else Trunk.copy(alpha = 0.6f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** Full-screen "Never forgot!" moment when you tick something off. */
@Composable
fun Celebration(message: String, onFinished: () -> Unit) {
    val scale = remember { Animatable(0.3f) }
    val fade = remember { Animatable(0f) }
    val fall = remember { Animatable(0f) }
    val confetti = remember {
        List(16) {
            Triple(Random.nextFloat(), 0.6f + Random.nextFloat() * 0.6f, listOf("🥜", "✨", "🎉", "💛", "⭐").random())
        }
    }
    LaunchedEffect(Unit) {
        launch { fall.animateTo(1f, tween(1700, easing = LinearEasing)) }
        launch { fade.animateTo(1f, tween(150)) }
        scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f))
        delay(1000)
        fade.animateTo(0f, tween(250))
        onFinished()
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(Color(0xD90B0D12)),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            confetti.forEach { (x, speed, emoji) ->
                Text(
                    emoji,
                    fontSize = 24.sp,
                    modifier = Modifier.offset(
                        x = maxWidth * x * 0.9f,
                        y = maxHeight * (fall.value * speed) - 40.dp,
                    ),
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
        ) {
            Elephant(190.dp, bob = false)
            Spacer(Modifier.height(16.dp))
            Text("Never forgot!", color = Peanut, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(message, color = TextHi, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Shown over the lock screen when an alarm-style reminder goes off.
 * With [earlyMinutes] it's a heads-up: primary button dismisses, secondary marks it done already.
 */
@Composable
fun AlarmScreen(r: Reminder, onPrimary: () -> Unit, onSecondary: () -> Unit, earlyMinutes: Int? = null) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Elephant(210.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            if (earlyMinutes != null) "Heads up, Jason!" else "Hey, Jason!",
            color = Peanut,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "${r.emoji}  ${r.title}",
            color = TextHi,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (earlyMinutes != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "In ${earlyLabel(earlyMinutes)} · ${describe(r.timeMillis)}",
                color = Trunk,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (r.note.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(r.note, color = TextLo, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(48.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Peanut)
                .clickable(onClick = onPrimary)
                .padding(vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (earlyMinutes != null) "👍  Got it" else "✓  Got it", color = OnPeanut, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Card)
                .clickable(onClick = onSecondary)
                .padding(vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (earlyMinutes != null) "✓  Done already" else "💤  Snooze 10 min", color = TextHi, fontSize = 18.sp)
        }
    }
}
