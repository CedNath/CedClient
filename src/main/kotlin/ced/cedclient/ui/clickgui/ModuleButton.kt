package ced.cedclient.ui.clickgui

import ced.cedclient.utils.Keys
import ced.cedclient.features.Module
import ced.cedclient.features.settings.RenderableSetting
import ced.cedclient.ui.animations.ColorAnimation
import ced.cedclient.ui.animations.EaseInOutAnimation
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.ui.Palette
import ced.cedclient.utils.Color
import ced.cedclient.utils.Color.Companion.brighter
import ced.cedclient.utils.Color.Companion.darker
import ced.cedclient.utils.Color.Companion.withAlpha
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.clickGuiScale
import ced.cedclient.utils.ui.mouseX
import ced.cedclient.utils.ui.mouseY
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import kotlin.math.floor

private val camelBoundary = Regex("(?<=[a-z0-9])(?=[A-Z])")

/** A module name for display: CamelCase names without spaces ("StarredMobHighlight") get spaces; names that have them are left alone. */
fun prettyModuleName(name: String): String = when {
    name == "Mod Settings" -> "Click GUI" // display only: the saved config key stays "Mod Settings"
    ' ' in name -> name
    else -> name.replace(camelBoundary, " ")
}

/**
 * Renders one module's row inside a Panel, and (when right-clicked expanded)
 * its settings stack below it. Ported from Odin's clickgui/settings/ModuleButton.kt.
 *
 * @see RenderableSetting
 */
class ModuleButton(val module: Module, val panel: Panel) {

    val representableSettings = module.settings.values.filterIsInstance<RenderableSetting<*>>().filter { !it.inPopup }
    private val colorAnim = ColorAnimation(150)

    /** Name colour: white when on (or just pressed), faint grey when off. */
    private val nameColor: Color
        get() = colorAnim.get(ON_TEXT, OFF_TEXT, module.enabled || pressed())

    /** Action modules (one-shot buttons) light up briefly after a click instead of staying on. */
    private var pressedAt = 0L
    private fun pressed() = module.isAction && System.currentTimeMillis() - pressedAt < 150L

    /** What the menu shows: "FarmHelper" reads "Farm Helper". Display only; saved configs and search still use [Module.name]. */
    private val shownName = prettyModuleName(module.name)
    /** The Click GUI row itself: it is where the mod's look is set up, so it stands out from the plain module rows. */
    private val isLookRow = module.name == "Mod Settings"
    private val hoverHandler = HoverHandler(750)
    private val extendAnim = EaseInOutAnimation(250)
    private val switchAnim = EaseInOutAnimation(150)
    private val hover = HoverHandler(250)
    var extended = false

    fun draw(x: Float, y: Float, lastModule: Boolean = false): Float {
        hoverHandler.handle(x, y, Panel.WIDTH, Panel.HEIGHT - 1, true)
        hover.handle(x, y, Panel.WIDTH, Panel.HEIGHT - 1, true)

        if (hoverHandler.percent() >= 100 && y >= panel.panelSetting.y + Panel.HEIGHT)
            ClickGUI.setDescription(module.description, x + Panel.WIDTH + 10f, y, hoverHandler)

        // No row fill: the panel card shows through. Hover is a faint white wash; enabled modules get the accent dot.
        val hoverAlpha = 0.04f * hover.percent() / 100f
        if (isLookRow) NVGRenderer.rect(x, y, Panel.WIDTH, Panel.HEIGHT, ClickGUI.clickGUIColor.withAlpha(0.14f).rgba)
        if (hoverAlpha > 0f) NVGRenderer.rect(x, y, Panel.WIDTH, Panel.HEIGHT, Colors.WHITE.withAlpha(hoverAlpha).rgba)
        NVGRenderer.text(
            shownName,
            x + Panel.WIDTH / 2 - NVGRenderer.textWidth(shownName, 21f, NVGRenderer.defaultFont) / 2, // centered; measured now, the font can change
            y + Panel.HEIGHT / 2 - 10.5f,
            21f,
            if (isLookRow) ClickGUI.clickGUIColor.rgba else nameColor.rgba,
            NVGRenderer.defaultFont
        )
        if (module.enabled && !module.alwaysEnabled)
            NVGRenderer.rect(x + Panel.WIDTH - 24f, y + Panel.HEIGHT / 2f - 4f, 8f, 8f, ClickGUI.clickGUIColor.rgba, 4f)

        if (representableSettings.isEmpty()) return Panel.HEIGHT

        val totalHeight = Panel.HEIGHT + floor(extendAnim.get(0f, getSettingHeight(), !extended))
        var drawY = Panel.HEIGHT

        if (extendAnim.isAnimating()) NVGRenderer.pushScissor(x, y, Panel.WIDTH, totalHeight)

        if (extendAnim.isAnimating() || extended) {
            // See-through backing (like Odin): the module rows stay solid, so where the settings end is clear.
            NVGRenderer.rect(x, y + Panel.HEIGHT, Panel.WIDTH, totalHeight - Panel.HEIGHT, Colors.WHITE.withAlpha(0.03f).rgba)
            for (setting in representableSettings) {
                if (setting.isVisible) drawY += setting.render(x, y + drawY, mouseX / clickGuiScale, mouseY / clickGuiScale)
            }
        }

        if (extendAnim.isAnimating()) NVGRenderer.popScissor()
        return totalHeight
    }

    fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (hover.isHovered) {
            if (Keys.mouse(click) == 0 && !module.alwaysEnabled) {
                colorAnim.start()
                switchAnim.start()
                if (module.isAction) pressedAt = System.currentTimeMillis()
                module.toggle()
                return true
            } else if (Keys.mouse(click) == 1 || module.alwaysEnabled) {
                if (ClickGUI.compact) {
                    if (representableSettings.isNotEmpty()) CompactLayout.selectModule(this)
                } else if (module.settings.isNotEmpty()) {
                    // Opening one closes the one that was open in this panel (only one settings menu at a time).
                    if (!extended) panel.moduleButtons.forEach { if (it !== this && it.extended) { it.extendAnim.start(); it.extended = false } }
                    extendAnim.start()
                    extended = !extended
                }
                return true
            }
        } else if (extended) {
            for (setting in representableSettings) {
                if (setting.isVisible && setting.mouseClicked(mouseX, mouseY, click)) return true
            }
        }
        return false
    }

    fun mouseReleased(click: MouseButtonEvent) {
        if (!extended) return
        for (setting in representableSettings) {
            if (setting.isVisible) setting.mouseReleased(click)
        }
    }

    fun keyTyped(input: CharacterEvent): Boolean {
        if (!extended) return false
        for (setting in representableSettings) {
            if (setting.isVisible && setting.keyTyped(input)) return true
        }
        return false
    }

    fun keyPressed(input: KeyEvent): Boolean {
        if (!extended) return false
        for (setting in representableSettings) {
            if (setting.isVisible && setting.keyPressed(input)) return true
        }
        return false
    }

    /** Compact layout: this module as a card, name on the left and an ON / OFF pill on the right. */
    fun drawCard(x: Float, y: Float, w: Float, h: Float) {
        hoverHandler.handle(x, y, w, h, true)
        hover.handle(x, y, w, h, true)
        if (hoverHandler.percent() >= 100) ClickGUI.setDescription(module.description, x + w + 10f, y, hoverHandler)

        NVGRenderer.rect(x, y, w, h, CompactLayout.tint(if (hover.isHovered) CompactLayout.CARD_HOVER else CompactLayout.CARD), 8f)
        // The module whose settings are open gets a light outline (the pill already shows on / off).
        if (extended) NVGRenderer.hollowRect(x, y, w, h, 1f, Colors.WHITE.withAlpha(0.22f).rgba, 8f)
        val on = module.enabled || pressed()
        NVGRenderer.text(shownName, x + 20f, y + h / 2f - 10.5f, 21f, if (on) Palette.TEXT else Palette.FAINT, NVGRenderer.defaultFont)

        val track = colorAnim.get(ClickGUI.clickGUIColor, CompactLayout.PILL_OFF, on).rgba
        if (module.isAction) {
            val pw = 58f
            val ph = 26f
            val px = x + w - pw - 14f
            val py = y + (h - ph) / 2f
            NVGRenderer.rect(px, py, pw, ph, track, 8f)
            val lw = NVGRenderer.textWidth("RUN", 15f, NVGRenderer.defaultFont)
            NVGRenderer.text("RUN", px + pw / 2f - lw / 2f, py + ph / 2f - 7.5f, 15f, if (on) ClickGUI.onAccentRgba else CompactLayout.MUTED.rgba, NVGRenderer.defaultFont)
        } else {
            // A sliding switch: track in the accent when on, knob moving right.
            val tw = 44f
            val th = 22f
            val tx = x + w - tw - 16f
            val ty = y + (h - th) / 2f
            NVGRenderer.rect(tx, ty, tw, th, track, th / 2f)
            val knob = th - 6f
            val kx = tx + 3f + switchAnim.get(0f, tw - 6f - knob, !on)
            NVGRenderer.rect(kx, ty + 3f, knob, knob, if (on) ClickGUI.onAccentRgba else CompactLayout.MUTED.rgba, knob / 2f)
        }
    }

    /** Compact layout: draws the settings stack at ([x], [y]) in ClickGUI's settings panel; returns its height. */
    fun drawSettings(x: Float, y: Float): Float {
        var drawY = 0f
        var lastSection: String? = null
        for (setting in representableSettings) {
            if (!setting.isVisible) continue
            val section = setting.section
            if (section != null && section != lastSection) {
                val tw = NVGRenderer.textWidth(section.uppercase(), 13f, NVGRenderer.defaultFont)
                NVGRenderer.text(section.uppercase(), x + 14f, y + drawY + 8f, 13f, Palette.MUTED, NVGRenderer.defaultFont)
                NVGRenderer.rect(x + 14f + tw + 8f, y + drawY + 14f, CARD_WIDTH - 28f - tw - 8f, 1f, CompactLayout.EDGE.rgba)
                drawY += SECTION_HEIGHT
            }
            lastSection = section ?: lastSection
            // Each setting sits in its own faint card with a thin light outline, with the widget itself inset
            // from the card's edges so labels and switches don't touch the border.
            val h = setting.getHeight()
            val cardH = h + 2 * CARD_PAD_Y
            NVGRenderer.rect(x, y + drawY, CARD_WIDTH, cardH, Colors.WHITE.withAlpha(0.04f).rgba, 6f)
            setting.render(x + CARD_PAD_X, y + drawY + CARD_PAD_Y, mouseX / clickGuiScale, mouseY / clickGuiScale)
            NVGRenderer.hollowRect(x, y + drawY, CARD_WIDTH, cardH, 1f, Palette.EDGE, 6f)
            drawY += cardH + CARD_GAP
        }
        return drawY
    }

    fun clickSettings(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean =
        representableSettings.any { it.isVisible && it.mouseClicked(mouseX, mouseY, click) }

    /** [getSettingHeight] plus the section headings drawSettings adds. */
    fun compactHeight(): Float {
        var last: String? = null
        var h = 0f
        for (s in representableSettings) {
            if (!s.isVisible) continue
            if (s.section != null && s.section != last) h += SECTION_HEIGHT
            last = s.section ?: last
            h += s.getHeight() + 2 * CARD_PAD_Y + CARD_GAP
        }
        return h
    }

    private companion object {
        /** Compact settings cards: the widget (Panel.WIDTH wide) sits this far in from the card edge; cards are CARD_GAP apart. */
        const val CARD_PAD_X = 8f
        const val CARD_PAD_Y = 5f
        const val CARD_GAP = 6f
        const val CARD_WIDTH = Panel.WIDTH + 2 * CARD_PAD_X
        const val SECTION_HEIGHT = 28f
        val ON_TEXT = Color(Palette.TEXT)
        val OFF_TEXT = Color(Palette.FAINT)
    }

    fun getSettingHeight(): Float =
        representableSettings.filter { it.isVisible }.sumOf { it.getHeight().toDouble() }.toFloat()
}