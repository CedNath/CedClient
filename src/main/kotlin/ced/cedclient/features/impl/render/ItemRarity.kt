package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.utils.SkyblockRarity
import ced.cedclient.utils.loreLines
import ced.cedclient.utils.petInfo
import ced.cedclient.utils.skyblockId
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.ItemStack

/**
 * Item Rarity (after Noamm's): a colored shape behind SkyBlock items by rarity (the word on the last lore line), in menus
 * and the hotbar. A mixin on the one method every item icon is drawn through puts it right behind the icon.
 * Styles: Filled, Outline, Filled Outline, Circle. Color Style: Default (the chat colors) or Hypixel (Hypixel's own palette).
 */
object ItemRarity : Module("Item Rarity", Category.Visuals, "Colored background behind SkyBlock items by rarity.") {
    private val hotbar = BooleanSetting("Draw On Hotbar", true, "Also behind the hotbar items (and any item drawn with no menu open).")
    private val opacity = NumberSetting("Rarity Opacity", 30.0, 10.0, 100.0, 1.0, "How strong the color is, in percent.")
    private val style = DropdownSetting("Rarity Style", listOf("Filled", "Outline", "Filled Outline", "Circle"), "Filled", "The shape behind the item.")
    private val colorStyle = DropdownSetting("Color Style", listOf("Default", "Hypixel"), "Default", "Default: the chat colors. Hypixel: Hypixel's own rarity colors.")
    private val showCommon = BooleanSetting("Show Common", false, "Also mark common items.")

    private val DEFAULT = mapOf(
        SkyblockRarity.COMMON to 0xFFFFFF, SkyblockRarity.UNCOMMON to 0x55FF55, SkyblockRarity.RARE to 0x5555FF,
        SkyblockRarity.EPIC to 0xAA00AA, SkyblockRarity.LEGENDARY to 0xFFAA00, SkyblockRarity.MYTHIC to 0xFF55FF,
        SkyblockRarity.DIVINE to 0x55FFFF, SkyblockRarity.SUPREME to 0xAA0000, SkyblockRarity.ADMIN to 0xAA0000, SkyblockRarity.SPECIAL to 0xFF5555, SkyblockRarity.VERY_SPECIAL to 0xFF5555,
        SkyblockRarity.ULTIMATE to 0xAA0000,
    )
    private val HYPIXEL = mapOf(
        SkyblockRarity.COMMON to 0xFFFFFF, SkyblockRarity.UNCOMMON to 0x21FF2A, SkyblockRarity.RARE to 0x459BFF,
        SkyblockRarity.EPIC to 0xA335EE, SkyblockRarity.LEGENDARY to 0xFFA216, SkyblockRarity.MYTHIC to 0xFF55FF,
        SkyblockRarity.DIVINE to 0x55FFFF, SkyblockRarity.SUPREME to 0xD13228, SkyblockRarity.ADMIN to 0xD13228, SkyblockRarity.SPECIAL to 0xFF5555, SkyblockRarity.VERY_SPECIAL to 0xD13228,
        SkyblockRarity.ULTIMATE to 0xD13228,
    )

    // The lore read is not free and an icon is drawn every frame: remember the answer per stack for a moment.
    private val cache = java.util.IdentityHashMap<ItemStack, SkyblockRarity?>()
    private var cacheAt = 0L

    fun rarityOf(stack: ItemStack): SkyblockRarity? {
        val now = System.currentTimeMillis()
        if (now - cacheAt > 1500) { cache.clear(); cacheAt = now }
        return cache.getOrPut(stack) {
            if (stack.skyblockId == null) null
            // Pets (pet menu, held pets) have no rarity word in their lore; the tier is in petInfo.
            else stack.petInfo?.get("tier")?.asString?.let { t -> SkyblockRarity.entries.firstOrNull { it.name == t } }
                ?: stack.loreLines.asReversed().firstNotNullOfOrNull { l -> SkyblockRarity.fromLine(l).takeIf { SkyblockRarity.isRarityLine(l) } }
        }
    }

    init {
        addSettings(hotbar, opacity, style, colorStyle, showCommon)
    }

    private fun circle(g: GuiGraphicsExtractor, x: Int, y: Int, color: Int) {
        // 16 scanlines of a circle of radius 8 centred on the icon
        for (row in 0 until 16) {
            val dy = row + 0.5 - 8.0
            val half = Math.sqrt((64.0 - dy * dy).coerceAtLeast(0.0)).toInt()
            g.fill(x + 8 - half, y + row, x + 8 + half, y + row + 1, color)
        }
    }

    /** Mixin: called just before an item icon is drawn at ([x], [y]). */
    @JvmStatic
    fun drawBehind(g: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
        if (!isEnabled || stack.isEmpty) return
        if (Minecraft.getInstance().gui.screen() == null && !hotbar.value) return
        val r = rarityOf(stack) ?: return
        if (r == SkyblockRarity.COMMON && !showCommon.value) return
        val rgb = (if (colorStyle.value == "Hypixel") HYPIXEL else DEFAULT)[r] ?: return
        val a = (opacity.value / 100.0 * 255).toInt().coerceIn(0, 255)
        val soft = (a shl 24) or rgb
        val solid = (0xFF shl 24) or rgb
        fun border(c: Int) {
            g.fill(x, y, x + 16, y + 1, c); g.fill(x, y + 15, x + 16, y + 16, c)
            g.fill(x, y + 1, x + 1, y + 15, c); g.fill(x + 15, y + 1, x + 16, y + 15, c)
        }
        when (style.value) {
            "Outline" -> border(soft)
            "Filled Outline" -> { g.fill(x, y, x + 16, y + 16, soft); border(solid) }
            "Circle" -> circle(g, x, y, soft)
            else -> g.fill(x, y, x + 16, y + 16, soft)
        }
    }
}
