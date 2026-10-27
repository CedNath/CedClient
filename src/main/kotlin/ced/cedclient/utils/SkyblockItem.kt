package ced.cedclient.utils

import ced.cedclient.mixin.accessor.CustomDataAccessor
import ced.cedclient.state.Sidebar
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack

/**
 * SkyBlock item data, for every feature that looks at items. Hypixel keeps an item's SkyBlock data in its
 * custom_data component ({id: "HYPERION", uuid: ..., modifier: "heroic", enchantments: {...}, ...}); these read it
 * in place, without copying. All of them return null / empty / 0 for non-SkyBlock items.
 *
 * Read-only: never change the tag [skyblockData] returns, it is the item's own.
 */

/** The item's SkyBlock data (custom_data), or null when it has none. Do not modify it. */
val ItemStack.skyblockData: CompoundTag?
    get() {
        if (isEmpty) return null
        val data = get(DataComponents.CUSTOM_DATA) ?: return null
        return (data as CustomDataAccessor).`ced$tag`().takeUnless { it.isEmpty }
    }

/** SkyBlock item ID, e.g. "HYPERION", "ENCHANTED_BOOK", "PET". */
val ItemStack.skyblockId: String? get() = skyblockData?.getString("id")?.orElse(null)

/** Hypixel's per-item UUID (unique items only: weapons, armor, pets...). */
val ItemStack.skyblockUuid: String? get() = skyblockData?.getString("uuid")?.orElse(null)

/** Reforge, e.g. "heroic", "withered". */
val ItemStack.reforge: String? get() = skyblockData?.getString("modifier")?.orElse(null)

/** Enchantments as name -> level, e.g. {"ultimate_wise": 5, "sharpness": 7}. */
val ItemStack.enchantments: Map<String, Int>
    get() {
        val tag = skyblockData?.getCompound("enchantments")?.orElse(null) ?: return emptyMap()
        return tag.keySet().associateWith { tag.getIntOr(it, 0) }
    }

/** Dungeon stars (upgrade_level, or dungeon_item_level on older items), 0 when none. */
val ItemStack.stars: Int
    get() {
        val tag = skyblockData ?: return 0
        return tag.getIntOr("upgrade_level", tag.getIntOr("dungeon_item_level", 0))
    }

val ItemStack.isRecombobulated: Boolean get() = (skyblockData?.getIntOr("rarity_upgrades", 0) ?: 0) > 0

val ItemStack.hotPotatoBooks: Int get() = skyblockData?.getIntOr("hot_potato_count", 0) ?: 0

/** Lore lines as plain text (no formatting codes), top to bottom. */
val ItemStack.loreLines: List<String>
    get() = get(DataComponents.LORE)?.lines()?.map { Sidebar.clean(it.string) }.orEmpty()

/** The item's name as plain text. */
val ItemStack.cleanName: String get() = Sidebar.clean(hoverName.string)

/** Pet data from petInfo (type, tier, heldItem, exp, ...), or null for non-pets. */
val ItemStack.petInfo: JsonObject?
    get() = skyblockData?.getString("petInfo")?.orElse(null)?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }

enum class SkyblockRarity {
    COMMON, UNCOMMON, RARE, EPIC, LEGENDARY, MYTHIC, DIVINE, SUPREME, SPECIAL, VERY_SPECIAL, ULTIMATE, ADMIN;

    val display = name.replace('_', ' ')

    companion object {
        // Longest first, so "VERY SPECIAL" wins over "SPECIAL".
        private val byLength = entries.sortedByDescending { it.display.length }

        /** Rarity from a lore line like "LEGENDARY DUNGEON SWORD" or "a VERY SPECIAL ...". */
        fun fromLine(line: String): SkyblockRarity? = byLength.firstOrNull { line.contains(it.display) }

        // A recombobulated item's rarity line has an obfuscated "a" at each end ("a MYTHIC SWORD a"): not part of the text.
        private val RECOMB_MARK = Regex("""\b[a-z]\b""")

        /** The bottom line of a lore that names the rarity: all capitals, apart from the recombobulator's "a" marks. */
        fun isRarityLine(line: String): Boolean {
            val t = line.replace(RECOMB_MARK, "").trim()
            return t == t.uppercase()
        }
    }
}

/** Rarity from the last lore line that names one (Hypixel puts "LEGENDARY SWORD" at the bottom). */
val ItemStack.rarity: SkyblockRarity?
    get() = loreLines.asReversed().firstNotNullOfOrNull { line -> SkyblockRarity.fromLine(line).takeIf { SkyblockRarity.isRarityLine(line) } }
