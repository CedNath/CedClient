package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.RenderableSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.render.nvg.Gradient
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.SettingsPopup
import ced.cedclient.utils.Color
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import org.joml.Vector2i
import org.joml.Vector2ic
import java.util.Optional

/**
 * Our own tooltip frame for every tooltip (items, buttons, Hypixel's styled ones), drawn with NanoVG. TooltipBlitMixin
 * swaps the vanilla / pack sprites for [draw]; TooltipScrollMixin places the tooltip through [position].
 *
 * Everything is set in the "Customize" popup: background (flat or gradient), border (accent, the item name's colour
 * or custom), header bar behind the name, shadow, glow, corner radius, slide-in, scrolling for tooltips bigger than the
 * screen and hiding lines you never want to read.
 */
object CustomTooltips : Module(
    "Custom Tooltips",
    Category.Render,
    "NanoVG tooltips you can style (colours, border, glow, header), scroll and filter"
) {
    private const val ACCENT = "Accent"
    private const val ITEM = "Item Name"
    private const val CUSTOM = "Custom"

    // Background
    private val background = ColorSetting("Background", Color(0x0E0E14, 0.94f), "Fill behind the text (top colour with Gradient on)")
    private val gradient = BooleanSetting("Gradient", true, "Fade the background into a second colour towards the bottom")
    private val gradientBottom = ColorSetting("Gradient Bottom", Color(0x22025B, 0.94f), "Bottom colour of the gradient")
        .withDependency { gradient.value }
    private val radius = NumberSetting("Corner Radius", 3.0, 0.0, 8.0, 1.0, "Roundness of the corners, in GUI pixels")
    private val header = BooleanSetting("Header Bar", false, "Tinted bar behind the first line (the item name), in the border colour")
    private val headerOpacity = NumberSetting("Header Opacity", 0.3, 0.05, 1.0, 0.05, "How strong the header tint is")
        .withDependency { header.value }

    // Border
    private val borderMode = DropdownSetting("Border Colour", listOf(ACCENT, ITEM, CUSTOM), ACCENT,
        "Accent = ClickGUI colour, Item Name = colour of the item's name (rarity on SkyBlock), Custom = colour below")
    private val border = ColorSetting("Custom Border", Color(0x5A5A6E, 1f), "Border colour when Border Colour is Custom")
        .withDependency { borderMode.value == CUSTOM }
    private val borderWidth = NumberSetting("Border Width", 1.0, 0.0, 3.0, 0.5, "0 = no border")

    // Shadow & glow
    private val shadow = BooleanSetting("Shadow", true, "Dark shadow around the tooltip")
    private val shadowSize = NumberSetting("Shadow Size", 6.0, 1.0, 12.0, 1.0, "How far the shadow spreads")
        .withDependency { shadow.value }
    private val shadowOpacity = NumberSetting("Shadow Opacity", 0.5, 0.05, 1.0, 0.05, "How dark the shadow is")
        .withDependency { shadow.value }
    private val glow = BooleanSetting("Glow", false, "Soft glow in the border colour around the tooltip")
    private val glowSize = NumberSetting("Glow Size", 5.0, 1.0, 12.0, 1.0, "How far the glow spreads")
        .withDependency { glow.value }
    private val glowOpacity = NumberSetting("Glow Opacity", 0.45, 0.05, 1.0, 0.05, "How bright the glow is")
        .withDependency { glow.value }

    // Behaviour
    private val slideIn = BooleanSetting("Slide In", true, "New tooltips slide up a few pixels as they appear")
    private val hideLines = TextSetting("Hide Lines", "", "Item tooltip lines containing any of these are removed, comma separated (e.g. reforged, Gear Score)", maxLength = 300)
    private val collapseBlank = BooleanSetting("Collapse Blank Lines", false, "Several empty lines in a row become one")

    private val styleSettings = listOf<RenderableSetting<*>>(background, gradient, gradientBottom, radius, header, headerOpacity)
    private val borderSettings = listOf<RenderableSetting<*>>(borderMode, border, borderWidth)
    private val effectSettings = listOf<RenderableSetting<*>>(shadow, shadowSize, shadowOpacity, glow, glowSize, glowOpacity)
    private val behaviourSettings = listOf<RenderableSetting<*>>(slideIn, hideLines, collapseBlank)

    private val customize = ActionSetting("Customize", "Colours, border, header, shadow, glow, slide-in and hidden lines.") {
        (Minecraft.getInstance().gui.screen() as? ClickGUI)?.openPopup(SettingsPopup("Custom Tooltips", listOf(
            listOf(SettingsPopup.Section("Background", styleSettings), SettingsPopup.Section("Border", borderSettings)),
            listOf(SettingsPopup.Section("Shadow & Glow", effectSettings), SettingsPopup.Section("Behaviour", behaviourSettings)),
        )))
    }

    private val mc = Minecraft.getInstance()
    private const val SLIDE_MS = 120f
    private const val SLIDE_PX = 6f

    // Same "which tooltip" key as Scrollable Tooltips, so Shift changing the lines doesn't replay the slide.
    private var lastKey: Any? = null
    private var shownAt = 0L

    // Colour of the last item tooltip's name, for Border Colour "Item Name" (only trusted for a moment: buttons have none).
    private var nameColor = 0
    private var nameColorAt = 0L

    init {
        val inPopup = styleSettings + borderSettings + effectSettings + behaviourSettings
        inPopup.forEach { it.inPopup = true }
        addSettings(customize, *inPopup.toTypedArray())

        ItemTooltipCallback.EVENT.register { _, _, _, lines ->
            if (!isEnabled || lines.isEmpty()) return@register
            nameColor = firstColor(lines[0])
            nameColorAt = System.currentTimeMillis()
            filter(lines)
        }
    }

    private fun firstColor(line: Component): Int =
        line.visit({ style: Style, text: String ->
            val c = style.color
            if (text.isNotBlank() && c != null) Optional.of(c.value) else Optional.empty()
        }, Style.EMPTY).orElse(0)

    private fun filter(lines: MutableList<Component>) {
        val words = hideLines.value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (words.isNotEmpty()) {
            // Never the name line.
            val kept = lines.drop(1).filterNot { l -> l.string.lowercase().let { s -> words.any { it in s } } }
            lines.subList(1, lines.size).clear()
            lines.addAll(kept)
        }
        if (collapseBlank.value) {
            var i = lines.size - 1
            while (i > 0) {
                if (lines[i].string.isBlank() && lines[i - 1].string.isBlank()) lines.removeAt(i)
                i--
            }
        }
    }

    private fun borderColor(): Int = when (borderMode.value) {
        CUSTOM -> border.value.rgba
        ITEM -> if (nameColor != 0 && nameColor != 0xFFFFFF && System.currentTimeMillis() - nameColorAt < 200) nameColor or 0xFF000000.toInt()
            else ClickGUI.clickGUIColor.rgba
        else -> ClickGUI.clickGUIColor.rgba
    }

    private fun withAlpha(color: Int, alpha: Double): Int = (color and 0x00FFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)

    /** Box around the text at (x, y, width, height), same 3 px padding as vanilla. */
    @JvmStatic
    fun draw(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
        // Room around the box for the shadow / glow, so they aren't cut off by the picture-in-picture area.
        val margin = maxOf(if (shadow.value) shadowSize.value else 0.0, if (glow.value) glowSize.value else 0.0).toInt() + 3
        val pad = 3 + margin
        val w = width + pad * 2
        val h = height + pad * 2
        // Read everything now: the content runs later, during the GUI render.
        val top = background.value.rgba
        val bottom = if (gradient.value) gradientBottom.value.rgba else top
        val edge = borderColor()
        val r = radius.value.toFloat()
        val bw = borderWidth.value.toFloat()
        val shadowColor = if (shadow.value) withAlpha(0, shadowOpacity.value) else 0
        val shadowBlur = shadowSize.value.toFloat()
        val glowColor = if (glow.value) withAlpha(edge, glowOpacity.value) else 0
        val glowBlur = glowSize.value.toFloat()
        val headerColor = if (header.value) withAlpha(edge, headerOpacity.value) else 0

        NVGSpecialRenderer.draw(g, x - pad, y - pad, w, h, tooltip = true, renderContent = {
            // Canvas is in real pixels; scale so the drawing below is in GUI pixels.
            val s = NVGRenderer.canvasWidth / w
            NVGRenderer.scale(s, s)
            val bx = margin.toFloat()
            val boxW = (width + 6).toFloat()
            val boxH = (height + 6).toFloat()
            if (shadowColor != 0) NVGRenderer.shadow(bx, bx + 1f, boxW, boxH, shadowBlur, 0f, r, shadowColor)
            if (glowColor != 0) NVGRenderer.shadow(bx, bx, boxW, boxH, glowBlur, 0f, r, glowColor)
            if (top == bottom) NVGRenderer.rect(bx, bx, boxW, boxH, top, r)
            else NVGRenderer.gradientRect(bx, bx, boxW, boxH, top, bottom, Gradient.TopToBottom, r)
            // First line is 9 px + vanilla's 2 px gap below it, plus the 3 px padding above.
            if (headerColor != 0) NVGRenderer.drawHalfRoundedRect(bx, bx, boxW, 14f, headerColor, r, true)
            if (bw > 0f) NVGRenderer.hollowRect(bx, bx, boxW, boxH, bw, edge, r)
        })
    }

    /** Slide-in: a new tooltip starts a few pixels low and eases up into place (after Scrollable Tooltips placed it). */
    @JvmStatic
    fun slide(pos: Vector2ic, w: Int, h: Int): Vector2ic {
        if (!isEnabled || !slideIn.value) return pos
        val now = System.currentTimeMillis()
        val key = ScrollableTooltips.tooltipKey(w, h)
        if (key != lastKey) {
            lastKey = key
            shownAt = now
        }
        val inv = 1f - ((now - shownAt) / SLIDE_MS).coerceIn(0f, 1f)
        val dy = (inv * inv * inv * SLIDE_PX).toInt()
        return if (dy == 0) pos else Vector2i(pos.x(), pos.y() + dy)
    }
}
