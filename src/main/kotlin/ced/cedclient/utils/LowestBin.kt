package ced.cedclient.utils

import com.google.gson.JsonParser
import net.minecraft.world.item.ItemStack
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Auction House lowest BIN per item, refreshed every 10 minutes on a background thread.
 *
 * Two public feeds with different coverage and ID formats: lb.tricked.dev (also has books,
 * shards and other Bazaar items) first, hysky.de (has potions tricked lacks) as fallback.
 * [of] turns an item into both feeds' ID formats. Also keeps Hypixel's Bazaar instant-buy prices for [ofId] (craft costs).
 */
object LowestBin {
    private const val TRICKED_URL = "https://lb.tricked.dev/lowestbins"
    private const val HYSKY_URL = "https://hysky.de/api/auctions/lowestbins"
    private const val BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar"
    private const val REFRESH_MS = 10 * 60 * 1000L

    @Volatile
    private var tricked: Map<String, Double> = emptyMap()
    @Volatile
    private var hysky: Map<String, Double> = emptyMap()
    @Volatile
    private var bazaar: Map<String, Double> = emptyMap()
    @Volatile
    private var bazaarSell: Map<String, Double> = emptyMap()

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val LEVEL = Regex("""\[Lvl (\d+)]""")

    @Volatile
    private var started = false

    /** Starts the refresh loop (once). */
    fun start() {
        if (started) return
        started = true
        Thread({
            while (true) {
                fetch(TRICKED_URL)?.let { tricked = it }
                fetch(HYSKY_URL)?.let { hysky = it }
                fetchBazaar()?.let { (buy, sell) -> bazaar = buy; bazaarSell = sell }
                Thread.sleep(REFRESH_MS)
            }
        }, "CedClient-LowestBin").apply { isDaemon = true }.start()
    }

    /** Lowest BIN for a SkyBlock item, or null when neither feed has it (or they haven't loaded yet). */
    fun of(stack: ItemStack): Double? {
        val keys = keys(stack)
        return keys.firstNotNullOfOrNull(tricked::get) ?: keys.firstNotNullOfOrNull(hysky::get)
    }

    /** Bazaar instant-buy and instant-sell price (sell 0 = no buy orders), or null when it's not a Bazaar product. */
    fun bazaarOf(stack: ItemStack): Pair<Double, Double>? =
        keys(stack).firstNotNullOfOrNull { k -> bazaar[k]?.let { it to (bazaarSell[k] ?: 0.0) } }

    /** Bazaar instant-buy and instant-sell price by product ID ("ESSENCE_WITHER"), or null when it isn't a Bazaar product. */
    fun bazaarOfId(id: String): Pair<Double, Double>? = bazaar[id]?.let { it to (bazaarSell[id] ?: 0.0) }

    /**
     * Price of one item by NEU repo ID ("ENCHANTED_DIAMOND", "INK_SACK-4", "ULTIMATE_WISE;5"): Bazaar instant-buy
     * when it's a Bazaar product, else lowest BIN. Null when nobody sells it.
     */
    fun ofId(neuId: String): Double? {
        val colon = neuId.replace('-', ':')
        val book = Regex("""^([A-Z_]+);(\d+)$""").matchEntire(neuId)?.destructured
        val bz = listOfNotNull(neuId, colon, book?.let { (n, l) -> "ENCHANTMENT_${n}_$l" })
        val ah = listOfNotNull(neuId, colon, book?.let { (n, l) -> "ENCHANTED_BOOK-$n-$l" })
        return bz.firstNotNullOfOrNull(bazaar::get) ?: ah.firstNotNullOfOrNull(tricked::get) ?: ah.firstNotNullOfOrNull(hysky::get)
    }

    /** The item's ID in tricked's and hysky's formats, most specific first. */
    private fun keys(stack: ItemStack): List<String> {
        val id = stack.skyblockId ?: return emptyList()
        val data = stack.skyblockData
        return when (id) {
            "PET" -> {
                val pet = stack.petInfo ?: return emptyList()
                val type = pet["type"]?.asString ?: return emptyList()
                val tier = pet["tier"]?.asString ?: return emptyList()
                val level = LEVEL.find(stack.hoverName.string)?.groupValues?.get(1)
                listOfNotNull(level?.let { "PET-$type-$tier-$it" }, "PET-$type-$tier", level?.let { "LVL_${it}_${tier}_$type" })
            }
            "ENCHANTED_BOOK" -> {
                // Books with several enchants have no single price.
                // Not stack.enchantments: vanilla's ItemStack.getEnchantments() member wins over that extension.
                val ench = data?.getCompound("enchantments")?.orElse(null) ?: return listOf(id)
                val name = ench.keySet().singleOrNull() ?: return listOf(id)
                val lvl = ench.getIntOr(name, 0)
                val n = name.uppercase()
                listOf("ENCHANTED_BOOK-$n-$lvl", "ENCHANTMENT_${n}_$lvl")
            }
            "RUNE", "UNIQUE_RUNE" -> {
                val runes = data?.getCompound("runes")?.orElse(null) ?: return listOf(id)
                val name = runes.keySet().singleOrNull() ?: return listOf(id)
                val lvl = runes.getIntOr(name, 0)
                listOf("RUNE-$name-$lvl", "${name}_RUNE_$lvl")
            }
            "NEW_YEAR_CAKE" -> data?.getIntOr("new_years_cake", 0)?.takeIf { it > 0 }
                ?.let { listOf("NEW_YEAR_CAKE-$it", "NEW_YEAR_CAKE_$it") } ?: listOf(id)
            // shortcut: potions (hysky's ABSORPTION_POTION_3_EXTENDED style) aren't mapped; add if someone wants them.
            else -> listOf(id)
        }
    }

    /** Instant-buy and instant-sell price per product. */
    private fun fetchBazaar(): Pair<Map<String, Double>, Map<String, Double>>? = runCatching {
        val request = HttpRequest.newBuilder(URI.create(BAZAAR_URL)).timeout(Duration.ofSeconds(20)).GET().build()
        val products = JsonParser.parseString(client.send(request, HttpResponse.BodyHandlers.ofString()).body()).asJsonObject.getAsJsonObject("products")
        val status = products.entrySet().associate { (k, v) -> k to v.asJsonObject.getAsJsonObject("quick_status") }
        val buy = status.mapValues { it.value["buyPrice"].asDouble }.filterValues { it > 0 }
        val sell = status.mapValues { it.value["sellPrice"].asDouble }
        if (buy.isEmpty()) null else buy to sell
    }.getOrNull()

    private fun fetch(url: String): Map<String, Double>? = runCatching {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build()
        val body = client.send(request, HttpResponse.BodyHandlers.ofString()).body()
        JsonParser.parseString(body).asJsonObject.entrySet().associate { (k, v) -> k to v.asDouble }.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
