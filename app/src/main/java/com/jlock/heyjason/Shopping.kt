package com.jlock.heyjason

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ShopItem(
    val id: Long,
    val name: String,
    val cat: String,
    val got: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
)

data class ShopCategory(val key: String, val label: String, val emoji: String, val words: List<String>)

/** Store sections, in a sensible walking order. */
val SHOP_CATEGORIES = listOf(
    ShopCategory("produce", "Produce", "🥦", listOf(
        "apple", "banana", "orange", "lemon", "lime", "grape", "berr", "strawberr", "blueberr", "melon", "pear", "peach",
        "plum", "cherr", "avocado", "tomato", "potato", "onion", "garlic", "carrot", "lettuce", "spinach", "kale",
        "cucumber", "pepper", "broccoli", "cauliflower", "celery", "mushroom", "zucchini", "corn", "salad", "herb",
        "cilantro", "parsley", "basil", "ginger", "fruit", "veg", "squash", "pumpkin", "cabbage", "beans sprout",
    )),
    ShopCategory("bakery", "Bakery", "🍞", listOf(
        "bread", "bagel", "bun", "roll", "croissant", "muffin", "tortilla", "wrap", "pita", "cake", "donut", "baguette",
    )),
    ShopCategory("meat", "Meat & fish", "🥩", listOf(
        "chicken", "beef", "pork", "steak", "ground beef", "ground turkey", "bacon", "sausage", "ham", "turkey", "lamb", "fish", "salmon",
        "tuna steak", "shrimp", "meat", "ribs", "deli", "hot dog", "wings",
    )),
    ShopCategory("dairy", "Dairy & eggs", "🥛", listOf(
        "milk", "cheese", "yogurt", "yoghurt", "butter", "cream", "egg", "sour cream", "cottage", "margarine",
    )),
    ShopCategory("frozen", "Frozen", "🧊", listOf(
        "frozen", "ice cream", "pizza", "fries", "popsicle", "ice",
    )),
    ShopCategory("pantry", "Pantry", "🥫", listOf(
        "peanut butter", "almond butter", "ground coffee", "coffee beans", "rice", "pasta", "noodle", "cereal", "oat", "flour", "sugar", "salt", "spice", "oil", "vinegar", "sauce",
        "ketchup", "mustard", "mayo", "soup", "can", "beans", "tuna", "peanut", "jam", "honey", "syrup", "coffee",
        "tea", "snack", "chip", "cracker", "cookie", "chocolate", "candy", "nut", "granola", "bar", "juice", "pop",
        "soda", "water", "drink", "beer", "wine", "broth", "stock", "baking",
    )),
    ShopCategory("household", "Household", "🧻", listOf(
        "paper towel", "toilet paper", "tissue", "napkin", "detergent", "soap", "dish", "sponge", "trash bag",
        "garbage bag", "foil", "wrap", "bag", "batter", "bulb", "cleaner", "bleach", "laundry", "filter",
    )),
    ShopCategory("personal", "Personal care", "🧴", listOf(
        "shampoo", "conditioner", "toothpaste", "toothbrush", "floss", "deodorant", "razor", "lotion", "sunscreen",
        "vitamin", "medicine", "pill", "advil", "tylenol", "bandage", "contact", "body wash",
    )),
    ShopCategory("pets", "Pets", "🐾", listOf("dog food", "cat food", "litter", "treats", "pet")),
    ShopCategory("other", "Other", "🛍️", emptyList()),
)

fun categoryOf(key: String): ShopCategory = SHOP_CATEGORIES.firstOrNull { it.key == key } ?: SHOP_CATEGORIES.last()

fun guessCategory(name: String): String {
    val n = name.lowercase()
    // Longer keywords first so "ice cream" beats "cream", "paper towel" beats "paper".
    val pairs = SHOP_CATEGORIES.flatMap { c -> c.words.map { it to c.key } }.sortedByDescending { it.first.length }
    for ((w, key) in pairs) {
        if (Regex("\\b" + Regex.escape(w)).containsMatchIn(n)) return key
    }
    return "other"
}

/** "milk, eggs and 2 bread" → ["Milk", "Eggs", "2 bread"]. */
fun splitShopInput(text: String): List<String> =
    text.split(Regex("\\s*(?:,|;|\\n|\\band\\b|&|\\+)\\s*", RegexOption.IGNORE_CASE))
        .map { it.trim().trimEnd('.') }
        .filter { it.isNotBlank() }
        .map { it.replaceFirstChar { c -> c.uppercaseChar() } }

object ShopStore {
    private var file: File? = null
    private val _items = MutableStateFlow<List<ShopItem>>(emptyList())
    val items: StateFlow<List<ShopItem>> = _items
    /** How often each item has been bought (lower-case name → count). */
    private val _history = MutableStateFlow<Map<String, Int>>(emptyMap())
    val history: StateFlow<Map<String, Int>> = _history

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.applicationContext.filesDir, "shopping.json")
        load()
    }

    @Synchronized
    fun add(names: List<String>) {
        val existing = _items.value.filter { !it.got }.map { it.name.lowercase() }.toSet()
        var id = System.currentTimeMillis()
        val added = names.filter { it.lowercase() !in existing }.distinctBy { it.lowercase() }.map { n ->
            ShopItem(id++, n, guessCategory(n))
        }
        if (added.isEmpty()) return
        _items.value = _items.value + added
        save()
    }

    @Synchronized
    fun update(item: ShopItem) {
        _items.value = _items.value.map { if (it.id == item.id) item else it }
        save()
    }

    @Synchronized
    fun toggle(item: ShopItem) = update(item.copy(got = !item.got))

    @Synchronized
    fun remove(item: ShopItem) {
        _items.value = _items.value.filter { it.id != item.id }
        save()
    }

    /** Clears everything in the cart and remembers it for "Buy again". Returns how many were cleared. */
    @Synchronized
    fun finishTrip(): Int {
        val got = _items.value.filter { it.got }
        if (got.isEmpty()) return 0
        val h = _history.value.toMutableMap()
        got.forEach { val k = it.name.lowercase(); h[k] = (h[k] ?: 0) + 1 }
        _history.value = h
        _items.value = _items.value.filter { !it.got }
        save()
        return got.size
    }

    /** Frequently bought things that aren't already on the list. */
    fun suggestions(limit: Int = 10): List<String> {
        val onList = _items.value.map { it.name.lowercase() }.toSet()
        return _history.value.entries
            .filter { it.key !in onList }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key.replaceFirstChar { c -> c.uppercaseChar() } }
    }

    @Synchronized
    fun forget(name: String) {
        _history.value = _history.value - name.lowercase()
        save()
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("items") ?: JSONArray()
            _items.value = (0 until arr.length()).map { i ->
                val it = arr.getJSONObject(i)
                ShopItem(
                    id = it.getLong("id"),
                    name = it.optString("name"),
                    cat = it.optString("cat", "other"),
                    got = it.optBoolean("got"),
                    addedAt = it.optLong("addedAt"),
                )
            }
            val h = o.optJSONObject("history") ?: JSONObject()
            _history.value = h.keys().asSequence().associateWith { h.optInt(it) }
        } catch (e: Exception) {
            // Start fresh if the file is unreadable.
        }
    }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        _items.value.forEach { s ->
            val o = JSONObject()
            o.put("id", s.id)
            o.put("name", s.name)
            o.put("cat", s.cat)
            o.put("got", s.got)
            o.put("addedAt", s.addedAt)
            arr.put(o)
        }
        val h = JSONObject()
        _history.value.forEach { (k, v) -> h.put(k, v) }
        val root = JSONObject()
        root.put("items", arr)
        root.put("history", h)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(f)
    }
}
