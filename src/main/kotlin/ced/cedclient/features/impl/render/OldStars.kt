package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.world.item.ItemStack

/**
 * Item names with the old colored star layout: the "✪✪✪✪✪➎" tail becomes one colored ✪ per star
 * (hooks: ItemStackHoverNameMixin for the name, ItemDecorationsMixin for the star count over the item).
 */
object OldStars : Module(
    "Old Stars",
    Category.Render,
    "Reverts Hypixel star item names to the old colored-star layout, with master stars before normal stars."
) {
    private val colors = ChatFormatting.entries.filter { it.isColor }
        .associateBy { it.getName().split('_').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) } }
    private val names = colors.keys.toList()

    private val normalColor = DropdownSetting("Normal Star Color", names, "Gold", "Color of the first five stars.")
    private val masterColor = DropdownSetting("Master Star Color", names, "Red", "Color of the stars that are master stars.")
    private val asStack = BooleanSetting("Stars as Stack Size", false, "Shows the item's star count where the stack size goes.")
    private val stackColor = DropdownSetting("Stack Size Color", names, "Gold", "Color of the star count.").withDependency { asStack.value }
    private val stackOverride = BooleanSetting("Stack Size Override", false, "Also replaces a real stack size on starred items.").withDependency { asStack.value }

    init { addSettings(normalColor, masterColor, asStack, stackColor, stackOverride) }

    private const val STAR = '✪'
    private const val MASTER = "➊➋➌➍➎"

    private fun color(name: String) = TextColor.fromLegacyFormat(colors[name]!!)

    private class Part(val text: String, val style: Style)

    private fun parts(c: Component): List<Part> {
        val out = ArrayList<Part>()
        c.visit({ style, text -> out += Part(text, style); java.util.Optional.empty<Unit>() }, Style.EMPTY)
        return out
    }

    @JvmStatic fun transform(name: Component): Component {
        if (!isEnabled) return name
        val parts = parts(name)
        var normal = 0
        var master = 0
        for (p in parts) for (ch in p.text) {
            if (ch == STAR) normal++
            else MASTER.indexOf(ch).let { if (it >= 0) master = it + 1 }
        }
        if (normal == 0 && master == 0) return name
        // Always the same star count as a normal item; each master star turns one of them (from the left) into the master color.
        val total = normal
        val m = master.coerceAtMost(total)
        // The stars keep the name's own style (so no stray italics), only the color changes.
        val base = parts.first { p -> p.text.any { it == STAR } }.style
        val stars = listOf(
            Component.literal(STAR.toString().repeat(m)).withStyle(base.withColor(color(masterColor.value))),
            Component.literal(STAR.toString().repeat(total - m)).withStyle(base.withColor(color(normalColor.value))),
        )
        val result = Component.empty()
        var placed = false
        for (p in parts) {
            val rest = p.text.filter { it != STAR && MASTER.indexOf(it) < 0 }
            if (rest.isNotEmpty()) result.append(Component.literal(rest).withStyle(p.style))
            if (rest.length != p.text.length && !placed) {
                placed = true
                stars.filter { it.string.isNotEmpty() }.forEach { result.append(it) }
            }
        }
        return result
    }

    /** Text for the stack-size spot, or null to leave vanilla's. */
    @JvmStatic fun stackText(stack: ItemStack, current: String?): String? {
        if (!isEnabled || !asStack.value) return null
        if (current != null && !stackOverride.value) return null
        var total = 0
        var masters = 0
        val masterRgb = color(masterColor.value)?.value
        stack.hoverName.visit({ style, text ->
            val n = text.count { it == STAR }
            total += n
            if (style.color?.value == masterRgb) masters += n
            java.util.Optional.empty<Unit>()
        }, Style.EMPTY)
        // Each master star turned one of the five into the master color, so it counts as a sixth, seventh...
        if (color(normalColor.value)?.value != masterRgb) total += masters
        if (total == 0) return null
        return colors[stackColor.value]!!.toString() + total
    }
}
