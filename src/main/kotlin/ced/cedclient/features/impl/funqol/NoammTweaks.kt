package ced.cedclient.features.impl.funqol

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.mixin.accessor.AbstractContainerScreenAccessor
import ced.cedclient.utils.Color
import ced.cedclient.utils.Debug
import ced.cedclient.utils.skyblockId
import ced.cedclient.utils.stars
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

// ------------------------------------------------------------------------------------------------ Enchant Colors

/**
 * Recolors the enchantment lines in SkyBlock item lore. Hypixel already tells the kinds apart (maxed enchants are drawn
 * gold, ultimates bold), so those two get their own colors here, and the rest are colored by level (or one color).
 * Also: Roman numerals as numbers, and one enchant per line instead of a comma list. Only lines made of "Name ROMAN"
 * parts are touched.
 * shortcut: "maxed" is read off Hypixel's gold, so it only works while that is how the lore is colored.
 */
object EnchantColors : Module("Enchant Colors", Category.Visuals, "Colors enchantments in item lore: maxed, ultimate and by level.") {
    private val maxColor = ColorSetting("Maxed", Color(255, 170, 0), "Enchants Hypixel shows as maxed (gold).")
    private val ultColor = ColorSetting("Ultimate", Color(255, 85, 255), "Ultimate enchants (bold).")
    private val byLevel = BooleanSetting("Color Others By Level", true, "Other enchants take the level colors below; off: the Normal color.")
    private val normalColor = ColorSetting("Normal", Color(85, 85, 255), "Other enchants when Color By Level is off.")
    private val low = ColorSetting("Bad Level Color", Color(170, 170, 170), "Levels 1-3.")
    private val mid = ColorSetting("Normal Level Color", Color(0, 170, 170), "Levels 4-5.")
    private val high = ColorSetting("High Level Color", Color(255, 170, 0), "Level 6 and up (not maxed).")
    private val boldMax = BooleanSetting("Bold Max Level", true, "Maxed enchants in bold.")
    private val rainbow = BooleanSetting("Rainbow Max Level", false, "Maxed enchants cycle through the colors instead of the Maxed color.")
    private val rainbowSpeed = NumberSetting("Rainbow Speed", 1.0, 0.1, 3.0, 0.1, "How fast the rainbow moves.").withDependency { rainbow.value }
    private val rainbowSat = NumberSetting("Rainbow Saturation", 1.0, 0.0, 1.0, 0.05, "How strong the rainbow colors are.").withDependency { rainbow.value }
    private val numbers = BooleanSetting("Numbers Instead Of Roman", false, "Sharpness 7 instead of Sharpness VII.")
    private val onePerLine = BooleanSetting("One Per Line", false, "Each enchant on its own line instead of a comma list.")

    private val PART = Regex("""^([A-Z][A-Za-z' -]*?) ([IVXL]+)$""")
    private const val GOLD = 0xFFAA00
    private var lastLog = 0L

    // Athen's tiers: Ultimate; Max (the enchant's max level); High (just under it); Normal (two under); Bad (lower).
    private enum class Kind { MAX, ULTIMATE, HIGH, NORMAL, BAD }

    private fun roman(s: String): Int {
        val v = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50)
        var total = 0
        for (i in s.indices) {
            val c = v[s[i]] ?: return 0
            val next = v[s.getOrNull(i + 1)] ?: 0
            total += if (c < next) -c else c
        }
        return total
    }

    private fun colorFor(kind: Kind, level: Int) = when {
        kind == Kind.MAX -> maxColor.value.rgba
        kind == Kind.ULTIMATE -> ultColor.value.rgba
        !byLevel.value -> normalColor.value.rgba
        kind == Kind.HIGH -> high.value.rgba
        kind == Kind.NORMAL -> mid.value.rgba
        else -> low.value.rgba
    } and 0xFFFFFF

    /** [text] with every letter its own hue, sliding along with the clock (the tooltip is rebuilt every frame, so it moves). */
    private fun rainbowText(text: String): MutableComponent {
        val out = Component.empty()
        val t = System.currentTimeMillis() * rainbowSpeed.value / 4000.0
        text.forEachIndexed { i, ch ->
            val hue = (1.0 - ((t - i * 0.04) % 1.0 + 1.0) % 1.0).toFloat()
            out.append(Component.literal(ch.toString()).withColor(java.awt.Color.HSBtoRGB(hue, rainbowSat.value.toFloat(), 1f) and 0xFFFFFF))
        }
        return out
    }

    /** The lines [line] becomes, or null when it is not an enchantment line. */
    private fun process(line: Component): List<Component>? {
        val chars = StringBuilder()
        val rgbs = ArrayList<Int?>()
        val bolds = ArrayList<Boolean>()
        line.visit({ st, s ->
            for (c in s) { chars.append(c); rgbs += st.color?.value; bolds += st.isBold }
            java.util.Optional.empty<Unit>()
        }, net.minecraft.network.chat.Style.EMPTY)
        val text = chars.toString()
        if (text.length < 4 || !text.first().isUpperCase()) return null
        val trailing = text.endsWith(",")
        var idx = 0
        val parts = ArrayList<MutableComponent>()
        val split = text.removeSuffix(",").split(", ")
        for ((i, raw) in split.withIndex()) {
            val start = idx
            idx += raw.length + 2
            val m = PART.find(raw) ?: return null
            val lvl = roman(m.groupValues[2])
            if (lvl == 0) return null
            val key = EnchantLevels.key(m.groupValues[1])
            val ultimate = bolds.getOrNull(start) == true
            val gold = rgbs.getOrNull(start) == GOLD
            if (gold && !ultimate) EnchantLevels.learn(key, lvl) // the game shows it maxed: that is its max level
            val max = EnchantLevels.max(key)
            val kind = when {
                ultimate -> Kind.ULTIMATE
                gold || (max != null && lvl >= max) -> Kind.MAX
                max == null -> if (lvl >= 6) Kind.HIGH else if (lvl >= 4) Kind.NORMAL else Kind.BAD // nothing known: by level
                lvl > max - 2 -> Kind.HIGH
                lvl == max - 2 -> Kind.NORMAL
                else -> Kind.BAD
            }
            val shown = m.groupValues[1] + " " + (if (numbers.value) lvl.toString() else m.groupValues[2])
            val last = i == split.lastIndex
            val comma = if (onePerLine.value) "" else if (!last || trailing) "," else ""
            val text = shown + comma
            val c = if (kind == Kind.MAX && rainbow.value) rainbowText(text) else Component.literal(text).withColor(colorFor(kind, lvl))
            parts += if (kind == Kind.ULTIMATE || (kind == Kind.MAX && boldMax.value)) c.withStyle { s -> s.withBold(true) } else c
        }
        if (onePerLine.value) return parts
        val out = Component.empty()
        parts.forEachIndexed { i, p -> if (i > 0) out.append(Component.literal(" ")); out.append(p) }
        return listOf(out)
    }

    init {
        addSettings(maxColor, ultColor, byLevel, normalColor, low, mid, high, boldMax, rainbow, rainbowSpeed, rainbowSat, numbers, onePerLine)
        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            if (!isEnabled || stack.skyblockId == null) return@register
            var i = 0
            while (i < lines.size) {
                val replaced = process(lines[i])
                if (replaced == null) { i++; continue }
                lines.removeAt(i)
                lines.addAll(i, replaced)
                i += replaced.size
                val now = System.currentTimeMillis()
                if (now - lastLog > 5000) { lastLog = now; Debug.line("Enchant Colors", "recolored a line into ${replaced.size}") }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Protect Item


// ------------------------------------------------------------------------------------------------ Slot Binding


// ------------------------------------------------------------------------------------------------ Loadout Keybinds

