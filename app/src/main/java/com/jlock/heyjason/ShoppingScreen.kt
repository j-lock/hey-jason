package com.jlock.heyjason

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val tripMessages = listOf(
    "Nice haul, Jason! 🛒",
    "Shopping done. The herd eats tonight. 🐘",
    "Peanuts acquired. 🥜",
    "Trunk's full! 🧺",
)

@Composable
fun ShoppingScreen(snackbar: SnackbarHostState, onTab: (Int) -> Unit) {
    val items by ShopStore.items.collectAsState()
    val history by ShopStore.history.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ShopItem?>(null) }

    val toGet = items.filter { !it.got }
    val inCart = items.filter { it.got }.sortedBy { it.name.lowercase() }
    val grouped = SHOP_CATEGORIES.mapNotNull { c ->
        val list = toGet.filter { it.cat == c.key }.sortedBy { it.addedAt }
        if (list.isEmpty()) null else c to list
    }
    // Recomputed whenever the list or history changes.
    val suggestions = remember(items, history) { ShopStore.suggestions() }

    Scaffold(
        containerColor = Ink,
        snackbarHost = {
            SnackbarHost(snackbar) { data -> Snackbar(data, containerColor = CardHi, contentColor = TextHi) }
        },
        bottomBar = { ShopAdd { names -> ShopStore.add(names) } },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = pad.calculateTopPadding() + 12.dp,
                bottom = pad.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "switch") { ViewSwitcher(current = 2, onSelect = onTab) }
            item(key = "header") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Shopping list", color = TextHi, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                items.isEmpty() -> "Nothing to buy. Wallet says thanks."
                                toGet.isEmpty() -> "Everything's in the cart! 🎉"
                                inCart.isEmpty() -> "${toGet.size} to get"
                                else -> "${toGet.size} to get · ${inCart.size} in the cart"
                            },
                            color = if (items.isNotEmpty() && toGet.isEmpty()) Mint else TextLo,
                            fontSize = 14.sp,
                        )
                    }
                    Elephant(72.dp)
                }
            }

            if (suggestions.isNotEmpty()) {
                item(key = "again") {
                    Column {
                        ShopLabel("Buy again", TextLo)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            suggestions.forEach { s -> Pill("+ $s") { ShopStore.add(listOf(s)) } }
                        }
                    }
                }
            }

            if (items.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("🛒", fontSize = 64.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Add things as you think of them.\nTry “milk, eggs, 2 bread and bananas”.",
                            color = TextLo,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            grouped.forEach { (cat, list) ->
                item(key = "cat_${cat.key}") { ShopLabel("${cat.emoji}  ${cat.label}", TextLo) }
                items(list, key = { it.id }) { s ->
                    ShopRow(s, Modifier.animateItem(), onToggle = { ShopStore.toggle(s) }, onEdit = { editing = s })
                }
            }

            if (inCart.isNotEmpty()) {
                item(key = "cartLabel") { ShopLabel("🛒  In the cart · ${inCart.size}", Mint) }
                items(inCart, key = { it.id }) { s ->
                    ShopRow(s, Modifier.animateItem(), onToggle = { ShopStore.toggle(s) }, onEdit = { editing = s })
                }
                item(key = "finish") {
                    Column(Modifier.padding(top = 10.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(Peanut)
                                .clickable {
                                    val left = toGet.size
                                    val n = ShopStore.finishTrip()
                                    val msg = tripMessages.random() + " Got $n" +
                                        if (left > 0) ", $left left for next time." else "."
                                    scope.launch { snackbar.showSnackbar(msg) }
                                }
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("✓  Done shopping", color = OnPeanut, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Clears what's in the cart. Anything not found stays on the list.",
                            color = TextLo,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }

    editing?.let { s ->
        ShopEditDialog(
            item = s,
            onDismiss = { editing = null },
            onSave = {
                ShopStore.update(it)
                editing = null
            },
            onDelete = {
                ShopStore.remove(s)
                editing = null
            },
        )
    }
}

@Composable
private fun ShopLabel(text: String, color: Color) {
    Text(
        text.uppercase(),
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(top = 12.dp, start = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun ShopRow(s: ShopItem, modifier: Modifier, onToggle: () -> Unit, onEdit: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (s.got) Slate else Card)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBubble(s.got, onToggle)
        Spacer(Modifier.width(14.dp))
        Text(
            s.name,
            color = if (s.got) TextLo else TextHi,
            fontSize = 18.sp,
            textDecoration = if (s.got) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "⋯",
            color = TextLo,
            fontSize = 22.sp,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onEdit)
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ShopEditDialog(item: ShopItem, onDismiss: () -> Unit, onSave: (ShopItem) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(item.name) }
    var cat by remember { mutableStateOf(item.cat) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit item") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Peanut,
                        unfocusedBorderColor = CardHi,
                        cursorColor = Peanut,
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Spacer(Modifier.height(14.dp))
                Text("Section", color = TextLo, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SHOP_CATEGORIES.forEach { c ->
                        Pill("${c.emoji} ${c.label}", selected = c.key == cat) { cat = c.key }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(item.copy(name = name.trim().ifBlank { item.name }, cat = cat))
            }) { Text("Save", color = Peanut) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = Coral) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = TextLo) }
            }
        },
    )
}

@Composable
private fun ShopAdd(onAdd: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) onAdd(splitShopInput(spoken))
    }
    val submit: () -> Unit = {
        val names = splitShopInput(text)
        if (names.isNotEmpty()) {
            onAdd(names)
            text = ""
        }
    }
    Surface(color = Slate, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Row(
            Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Add items… milk, eggs, bread", color = TextLo) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Card,
                    unfocusedContainerColor = Card,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Peanut,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Spacer(Modifier.width(8.dp))
            RoundButton("🎤") {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "What do you need? e.g. milk, eggs and bread")
                try {
                    voice.launch(i)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(ctx, "Voice input isn't available", Toast.LENGTH_SHORT).show()
                }
            }
            Spacer(Modifier.width(8.dp))
            RoundButton("+", bg = Peanut, fg = OnPeanut) { submit() }
        }
    }
}
