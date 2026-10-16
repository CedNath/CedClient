package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.utils.LowestBin
import ced.cedclient.utils.hotPotatoBooks
import ced.cedclient.utils.isRecombobulated
import ced.cedclient.utils.petInfo
import ced.cedclient.utils.skyblockData
import ced.cedclient.utils.skyblockId
import ced.cedclient.utils.stars
import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.text.DecimalFormat

/**
 * Adds "Lowest BIN" and "Craft Cost" lines to SkyBlock item tooltips.
 *
 * Craft cost = the item's crafting-table recipe from the item repo in config/notenoughupdates/repo (same folder as SearchOverlay),
 * each ingredient priced at Bazaar instant-buy or else lowest BIN. One level deep, like the recipe you'd craft by hand;
 * hidden when any ingredient has no price, so it never shows a too-low number.
 */
object ItemValue : Module(
    "Item Value",
    Category.Skyblock,
    "Shows the lowest BIN (or Bazaar buy / sell) and craft cost of SkyBlock items in their tooltip"
) {
    private val lowestBin = BooleanSetting("Lowest BIN", true, "Cheapest Buy It Now on the Auction House")
    private val craftCost = BooleanSetting("Craft Cost", true, "Cost of the recipe's ingredients (Bazaar instant-buy / lowest BIN); needs the item repo in config/notenoughupdates/repo")
    private val bazaarPrices = BooleanSetting("Bazaar Prices", true, "Bazaar items show instant buy / sell instead of lowest BIN")
    private val lowestBinFormat = TextSetting("Lowest BIN Text", "&5&oLowest BIN: &f&l#price", "#price = the price, & = colour code (&6 gold, &e yellow, &b aqua...)", maxLength = 64)
    private val craftCostFormat = TextSetting("Craft Cost Text", "&5&oCraft Cost:&r &f&l#price", "#price = the price, & = colour code (&6 gold, &e yellow, &b aqua...)", maxLength = 64)
    private val bazaarBuyFormat = TextSetting("BZ Buy Text", "&5&oBZ Buy: &f&l#price", "#price = instant-buy price, & = colour code", maxLength = 64)
    private val bazaarSellFormat = TextSetting("BZ Sell Text", "&5&oBZ Sell: &f&l#price", "#price = instant-sell price, & = colour code", maxLength = 64)
    private val worth = BooleanSetting("Item Worth", true, "Lowest BIN of the plain item plus everything applied to it (enchants, recomb, books, stars, gems...). Hold Shift for the breakdown.")
    private val worthFormat = TextSetting("Item Worth Text", "&5&oItem Worth: &6&l#price", "#price = the total, & = colour code", maxLength = 64)
    private val numbers = DropdownSetting("Numbers", listOf("Short", "Full"), description = "Short = 5.2M, Full = 5,200,000")

    // shortcut: only "recipe" (crafting table); forge and other "recipes" entries are skipped, add if wanted.
    @Volatile
    private var recipes: Map<String, List<Pair<String, Double>>> = emptyMap()

    private val GROUPED = DecimalFormat("#,##0")
    private val SHORT = DecimalFormat("0.#")

    init {
        addSettings(lowestBin, bazaarPrices, craftCost, worth, lowestBinFormat, bazaarBuyFormat, bazaarSellFormat, craftCostFormat, worthFormat, numbers)
        LowestBin.start()
        Thread(::loadRecipes, "CedClient-Recipes").apply { isDaemon = true }.start()
        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            if (!isEnabled) return@register
            val id = stack.skyblockId ?: return@register
            val bazaar = LowestBin.bazaarOf(stack)
            if (bazaar != null) {
                if (bazaarPrices.value) {
                    lines += line(bazaarBuyFormat.value, bazaar.first)
                    if (bazaar.second > 0) lines += line(bazaarSellFormat.value, bazaar.second)
                }
            } else if (lowestBin.value) {
                LowestBin.of(stack)?.let { lines += line(lowestBinFormat.value, it) }
            }
            if (craftCost.value) craftCost(id)?.let { lines += line(craftCostFormat.value, it) }
            if (worth.value) addWorth(stack, id, lines)
        }
    }

    // ---------------------------------------------------------------- item worth

    private fun addWorth(stack: ItemStack, id: String, lines: MutableList<Component>) {
        val parts = worthParts(stack, id)
        // Only the base price = nothing added; the Lowest BIN line already says it.
        if (parts.size < 2) return
        lines += line(worthFormat.value, parts.sumOf { it.second })
        if (!Minecraft.getInstance().hasShiftDown()) {
            lines += Component.literal("§8Hold Shift for the breakdown")
            return
        }
        for ((label, price) in parts) lines += Component.literal("§7 $label: §6" + if (numbers.value == "Full") GROUPED.format(price) else short(price))
    }

    /** What the item is worth, piece by piece: (label, coins). Pieces without a price are left out. */
    private fun worthParts(stack: ItemStack, id: String): List<Pair<String, Double>> {
        val tag = stack.skyblockData ?: return emptyList()
        val parts = ArrayList<Pair<String, Double>>()
        fun add(label: String, itemId: String, count: Double = 1.0) {
            if (count <= 0) return
            LowestBin.ofId(itemId)?.let { parts += label to it * count }
        }

        // Pets and books are priced whole (level / enchant included).
        val base = if (id == "PET" || id == "ENCHANTED_BOOK") LowestBin.of(stack) else LowestBin.ofId(id)
        base?.let { parts += "Base" to it }
        if (id != "ENCHANTED_BOOK") {
            // Read the tag here: vanilla's ItemStack.enchantments shadows our extension.
            val ench = tag.getCompound("enchantments").orElse(null)
            val enchants = ench?.keySet()?.sumOf { LowestBin.ofId("${it.uppercase()};${ench.getIntOr(it, 0)}") ?: 0.0 } ?: 0.0
            if (enchants > 0) parts += "Enchants" to enchants
        }
        stack.petInfo?.get("heldItem")?.asString?.let { add("Pet Item", it) }

        if (stack.isRecombobulated) add("Recombobulator", "RECOMBOBULATOR_3000")
        val hpb = stack.hotPotatoBooks
        add("Hot Potato Books", "HOT_POTATO_BOOK", minOf(hpb, 10).toDouble())
        add("Fuming Potato Books", "FUMING_POTATO_BOOK", (hpb - 10).toDouble())
        add("Art of War", "THE_ART_OF_WAR", tag.getIntOr("art_of_war_count", 0).toDouble())
        add("Art of Peace", "THE_ART_OF_PEACE", tag.getIntOr("artOfPeaceApplied", 0).toDouble())
        add("Jalapeno Book", "JALAPENO_BOOK", tag.getIntOr("jalapeno_count", 0).toDouble())
        add("Wood Singularity", "WOOD_SINGULARITY", tag.getIntOr("wood_singularity_count", 0).toDouble())
        add("Mana Disintegrators", "MANA_DISINTEGRATOR", tag.getIntOr("mana_disintegrator_count", 0).toDouble())
        add("Transmission Tuners", "TRANSMISSION_TUNER", tag.getIntOr("tuned_transmission", 0).toDouble())
        add("Polarvoid Books", "POLARVOID_BOOK", tag.getIntOr("polarvoid", 0).toDouble())
        if (tag.getIntOr("ethermerge", 0) > 0) { add("Etherwarp Merger", "ETHERWARP_MERGER"); add("Etherwarp Conduit", "ETHERWARP_CONDUIT") }

        // Stars: each level's essence (+ extra items / coins) from the repo table; levels 6-10 it doesn't list are master stars.
        val starCost = starCosts[id]
        for (level in 1..stack.stars) {
            val cost = starCost?.levels?.get(level)
            if (cost != null) {
                add("Stars", "ESSENCE_${starCost.essence.uppercase()}", cost.first.toDouble())
                for ((itemId, count) in cost.second) {
                    if (itemId == "SKYBLOCK_COIN") parts += "Stars" to count else add("Stars", itemId, count)
                }
            } else if (level in 6..10) {
                add("Master Stars", "${MASTER_STARS[level - 6]}_MASTER_STAR")
            }
        }

        tag.getList("ability_scroll").orElse(null)?.forEach { e -> e.asString().ifPresent { add("Scrolls", it) } }
        for (key in listOf("power_ability_scroll", "dye_item", "skin", "drill_part_upgrade_module", "drill_part_fuel_tank", "drill_part_engine")) {
            tag.getString(key).orElse(null)?.let { add(labelOf(key), it.uppercase()) }
        }
        tag.getString("talisman_enrichment").orElse(null)?.let { add("Enrichment", "TALISMAN_ENRICHMENT_${it.uppercase()}") }
        tag.getCompound("runes").orElse(null)?.let { r -> r.keySet().forEach { add("Rune", "${it.uppercase()}_RUNE;${r.getIntOr(it, 1)}") } }

        // Gems: "RUBY_0": "FINE" or {quality: "FINE"}; universal slots name the gem in "COMBAT_0_gem".
        tag.getCompound("gems").orElse(null)?.let { gems ->
            for (slot in gems.keySet()) {
                if (slot.endsWith("_gem") || slot == "unlocked_slots") continue
                val quality = gems.getString(slot).orElse(null) ?: gems.getCompound(slot).orElse(null)?.getString("quality")?.orElse(null) ?: continue
                val gem = gems.getString("${slot}_gem").orElse(null) ?: slot.substringBeforeLast('_')
                add("Gemstones", "${quality.uppercase()}_${gem.uppercase()}_GEM")
            }
        }
        // Same label = one line (e.g. 3 scrolls, 5 gems).
        return parts.groupBy({ it.first }, { it.second }).map { (label, prices) -> label to prices.sum() }
    }

    private fun labelOf(key: String) = key.removePrefix("drill_part_").split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun line(format: String, price: Double): Component =
        Component.literal(format.replace('&', '§').replace("#price", if (numbers.value == "Full") GROUPED.format(price) else short(price)))

    /** 5200000 -> "5.2M", 17750 -> "17.8k". */
    private fun short(v: Double): String {
        val (div, unit) = when {
            v >= 1e9 -> 1e9 to "B"
            v >= 1e6 -> 1e6 to "M"
            v >= 1e3 -> 1e3 to "k"
            else -> return GROUPED.format(v)
        }
        return SHORT.format(v / div) + unit
    }

    private fun craftCost(id: String): Double? {
        val ingredients = recipes[id] ?: return null
        var total = 0.0
        for ((ingredient, count) in ingredients) total += (LowestBin.ofId(ingredient) ?: return null) * count
        return total
    }

    private val MASTER_STARS = listOf("FIRST", "SECOND", "THIRD", "FOURTH", "FIFTH")

    /** Essence type ("Wither") and per star level: essence amount + extra (item ID, count), coins as SKYBLOCK_COIN. */
    private class StarCost(val essence: String, val levels: Map<Int, Pair<Int, List<Pair<String, Double>>>>)

    @Volatile
    private var starCosts: Map<String, StarCost> = emptyMap()

    private fun loadStarCosts() {
        val file = FabricLoader.getInstance().configDir.resolve("notenoughupdates/repo/constants/essencecosts.json")
        if (!Files.isRegularFile(file)) return
        val map = HashMap<String, StarCost>()
        JsonParser.parseString(Files.readString(file)).asJsonObject.entrySet().forEach { (id, v) ->
            runCatching {
                val o = v.asJsonObject
                val extras = o.getAsJsonObject("items")
                val levels = o.entrySet().mapNotNull { (k, amount) ->
                    val level = k.toIntOrNull() ?: return@mapNotNull null
                    val items = extras?.getAsJsonArray(k)?.map { e ->
                        val s = e.asString
                        s.substringBeforeLast(':') to (s.substringAfterLast(':').toDoubleOrNull() ?: 1.0)
                    }.orEmpty()
                    level to (amount.asInt to items)
                }.toMap()
                map[id] = StarCost(o["type"].asString, levels)
            }
        }
        starCosts = map
    }

    private fun loadRecipes() {
        runCatching { loadStarCosts() }.onFailure { println("[ItemValue] Failed to load star costs: ${it.message}") }
        val dir = FabricLoader.getInstance().configDir.resolve("notenoughupdates/repo/items")
        if (!Files.isDirectory(dir)) return
        val map = HashMap<String, List<Pair<String, Double>>>()
        Files.list(dir).use { files ->
            files.forEach { f ->
                runCatching {
                    val o = JsonParser.parseString(Files.readString(f)).asJsonObject
                    val recipe = o.getAsJsonObject("recipe") ?: return@runCatching
                    // "A1": "ENCHANTED_DIAMOND:32", empty slots are ""; "count" is how many the recipe makes.
                    val makes = recipe["count"]?.asInt?.coerceAtLeast(1) ?: 1
                    val slots = recipe.entrySet().filter { it.key != "count" }.mapNotNull { (_, v) ->
                        val s = v.asString.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        s.substringBeforeLast(':') to (s.substringAfterLast(':').toIntOrNull() ?: 1)
                    }.groupBy({ it.first }, { it.second }).map { (ingredient, counts) -> ingredient to counts.sum().toDouble() / makes }
                    if (slots.isNotEmpty()) map[o["internalname"].asString] = slots
                }
            }
        }
        recipes = map
    }
}
