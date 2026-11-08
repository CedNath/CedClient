package ced.cedclient.features.impl.render

import ced.cedclient.features.Module
import ced.cedclient.features.impl.misc.Theme
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.ui.Palette
import ced.cedclient.utils.Debug
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.util.Optional

/**
 * NanoVG drawing for HUD panels: one rounded dark card with a faint border, or see-through text with a soft halo
 * (docs/superpowers/specs/2026-11-08-premium-look-design.md). [draw] sets up the canvas so (0, 0) is the panel's
 * top-left in GUI units; sizes passed to the other functions are already multiplied by the panel's scale.
 */
object HudCard {
    const val BACKGROUND = Palette.SURFACE
    private const val CORNER_RADIUS = 8f

    /** Line step of see-through (compact) HUD text, as a multiple of the font size. */
    const val LINE_HEIGHT = 1.18f

    /**
     * HUDs that keep the full card (trackers you glance at between fights). Every other HUD is compact: see-through
     * text with a halo unless its card is turned on in the HUD editor. Dungeon Map draws its own frame.
     */
    val FULL_CARD: Set<String> = setOf(
        "Blaze Slayer Macro", "Slayer", "Run Profit", "Daily Tasks",
        "Critter Progress", "Critter Trades", "Critter Missing", "Music", "FarmHelper",
    )

    /** Inner padding of [label]'s panel: room for the card when it's on, almost none for see-through text. */
    fun pad(label: String, scale: Float): Float = if (cardOn(label)) 6f * scale else 1f * scale

    val font get() = NVGRenderer.defaultFont

    /** [behindLeap]: while the Leap Menu is open the panel is drawn inside its pass, under its dim backdrop and boxes. */
    fun draw(g: GuiGraphicsExtractor, x: Int, y: Int, behindLeap: Boolean = false, content: () -> Unit) {
        // Which HUD this is, so card() can use its style (panels are told apart by their spot).
        val label = labelAt(x, y)
        val full = {
            currentLabel = label
            cardDrawn = false
            val ratio = NVGRenderer.canvasWidth / g.guiWidth().toFloat()
            NVGRenderer.scale(ratio, ratio)
            NVGRenderer.translate(x.toFloat(), y.toFloat())
            content()
            // Text drawn outside a HUD panel (chest overlays, menus) must not inherit this HUD's halo or colours.
            currentLabel = null
        }
        if (behindLeap && ced.cedclient.features.impl.funqol.LeapMenu.defer(full, keep = label == "Dungeon Map")) return
        NVGSpecialRenderer.draw(g, 0, 0, g.guiWidth(), g.guiHeight(), renderContent = full)
    }

    private var currentLabel: String? = null

    /** The HUD being drawn put its card down ([card]); its text then needs no halo. Own Background toggles skip card() too. */
    private var cardDrawn = false

    /**
     * Which HUD sits at (x, y). HudElement.all builds a new list of every panel on each call and every card asks once per frame,
     * so the answer is kept for a second (positions only change while you drag a panel in the editor).
     */
    private val labelCache = HashMap<Long, String>()
    private var labelCacheAt = 0L

    private fun labelAt(x: Int, y: Int): String? {
        val now = System.currentTimeMillis()
        if (now - labelCacheAt > 1000) { labelCache.clear(); labelCacheAt = now }
        val key = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
        return labelCache.getOrPut(key) { HudElement.all.firstOrNull { it.panelX == x && it.panelY == y }?.label ?: "" }.ifEmpty { null }
    }

    private class Bg(val opacity: NumberSetting)
    private val bgSettings = HashMap<String, Bg>()
    /** HUDs whose card is off until the HUD editor turns it on (modules that had no Background setting of their own). */
    private val cardOffByDefault = HashSet<String>()

    /**
     * Gives every HUD module a Background Opacity slider, unless it already has its own Background settings. The card
     * itself is chosen per HUD in the HUD editor (see [HudLook], [backgroundToggle]).
     * Run once after the modules are registered and before the config loads, so the values are saved.
     */
    fun attachBackgroundSettings() {
        for (e in HudElement.all) {
            // Compact (in-run) HUDs are see-through unless the player turns their card on. HUDs with their own Background
            // toggle already default to off and are switched through it, so they stay out of the set.
            val ownToggle = (e as? Module)?.settings?.values?.any { it is BooleanSetting && isBackgroundName(it.name) } == true
            if (e.label !in FULL_CARD && e.label != "Dungeon Map" && !ownToggle) cardOffByDefault += e.label
            val m = e as? Module ?: continue
            // Background is chosen in the HUD editor now, so the module's own toggle leaves the menu (its value still works and saves).
            m.settings.values.filter { it is BooleanSetting && isBackgroundName(it.name) }.forEach { it.hide() }
            if (e.label == "Chat Alerts") continue // text only, no card
            val own = m.settings.values.filter { it.name.contains("background", true) }
            if (own.any { it is NumberSetting } || own.any { it.name.contains("opacity", true) || it.name.contains("color", true) }) continue
            if (own.isEmpty() && e.label !in FULL_CARD) cardOffByDefault += e.label
            bgSettings[e.label] = Bg(NumberSetting("Background Opacity", 100.0, 0.0, 100.0, 5.0, "How solid the card is (percent).").also { m.registerSetting(it) })
        }
    }

    /** A card that stays off until the HUD editor turns it on (the alert cards). */
    fun offByDefault(label: String) { cardOffByDefault += label }

    /** "Background", "Show Background", "Alert Background" ... but not a colour or an opacity. */
    internal fun isBackgroundName(name: String) =
        name.contains("background", true) && !name.contains("color", true) && !name.contains("opacity", true) && !name.contains("message", true)

    /** Is [label]'s card drawn: the editor's choice, else on unless it is a module that never had a card of its own. */
    private fun cardOn(label: String?): Boolean = HudLook.background(label) ?: (label !in cardOffByDefault)

    /** A yes/no the HUD editor's toolbar flips: a module's own setting where it has one, else a per-HUD choice in [HudLook]. */
    class Toggle(private val read: () -> Boolean, private val write: (Boolean) -> Unit) {
        var value: Boolean
            get() = read()
            set(v) = write(v)
    }

    private fun ownSetting(e: HudElement, match: (String) -> Boolean): BooleanSetting? =
        (e as? Module)?.settings?.values?.firstOrNull { it is BooleanSetting && match(it.name) } as? BooleanSetting

    private fun settingToggle(s: BooleanSetting) = Toggle({ s.value }, { s.value = it; ced.cedclient.config.ConfigManager.saveModulesOnly() })

    /** Background of [e] for the editor toolbar; null for a HUD that is text only. */
    fun backgroundToggle(e: HudElement): Toggle? {
        if (e.label == "Chat Alerts") return null
        return ownSetting(e) { isBackgroundName(it) }?.let(::settingToggle) ?: Toggle({ cardOn(e.label) }, { HudLook.setBackground(e.label, it) })
    }

    /** 0..1 opacity chosen for [label]'s card (1 when it has no slider). */
    fun opacityOf(label: String?): Float = bgSettings[label]?.opacity?.value?.toFloat()?.div(100f) ?: 1f

    private class Steady(var w: Float, var scale: Float, var seen: Long)
    private val steadyWidths = HashMap<String, Steady>()

    /**
     * A HUD's width that doesn't jump around as its numbers change ("9.9s" -> "10.0s"): the widest it has been, so the
     * card only ever grows while it's in use. Starts over when the scale changes or the HUD hasn't been drawn for 30 s.
     * Theme > Fixed HUD Size turns it off. Text meant for the right edge follows the card, the left stays put.
     */
    fun steady(key: String, width: Float, scale: Float): Float {
        if (!Theme.fixedHudSize.value) return width
        val now = System.currentTimeMillis()
        val s = steadyWidths.getOrPut(key) { Steady(width, scale, now) }
        if (s.scale != scale || now - s.seen > 30_000) { s.w = width; s.scale = scale } else if (width > s.w) s.w = width
        s.seen = now
        return s.w
    }

    /** The card behind a HUD: dark see-through fill, 1 px faint border, rounded corners. Nothing when its card is off. */
    fun card(width: Float, height: Float, scale: Float, alpha: Float = 1f, label: String? = currentLabel) {
        if (!cardOn(label)) return
        val alpha = alpha * opacityOf(label)
        cardDrawn = true
        val r = CORNER_RADIUS * scale
        NVGRenderer.rect(0f, 0f, width, height, withAlpha(Palette.SURFACE, alpha), r)
        NVGRenderer.hollowRect(0f, 0f, width, height, scale, withAlpha(Palette.EDGE, alpha), r)
    }

    /**
     * Measured in the space [draw] draws in (canvas scaled to GUI units). Callers measure before [draw] runs, where the
     * pixel-snapped glyph size differs, so the columns came out too narrow and the text overlapped.
     */
    fun width(text: String, size: Float, bold: Boolean = false): Float {
        val font = if (bold) NVGRenderer.boldFont else font
        // Inside a frame the current transform is the caller's own (HUD content, ClickGUI, editors): measure in it as before.
        if (NVGRenderer.drawing) return NVGRenderer.textWidth(text, size, font)
        // Not canvasWidth: outside a frame that is whatever the last NanoVG pass (a screen, a tooltip) left behind.
        // The HUD pass is the whole framebuffer, so the ratio draw() will use is framebuffer / pixel ratio / GUI width.
        val window = Minecraft.getInstance().window
        val gui = window.guiScaledWidth.toFloat()
        val ratio = if (gui > 0f) window.width / NVGRenderer.devicePixelRatio() / gui else 1f
        NVGRenderer.push()
        try {
            NVGRenderer.resetTransform()
            NVGRenderer.scale(ratio, ratio)
            return NVGRenderer.textWidth(text, size, font)
        } finally { NVGRenderer.pop() }
    }

    private val haloLogged = HashSet<String>()

    /**
     * HUD text. Colours too dark to read on a game scene (Hypixel's dark red and dark purple) are lifted by [legible], and
     * a HUD with its Background off gets a soft dark halo so it reads on bright scenes too. Pure black is left alone:
     * HUDs draw it on purpose as an outline or shadow.
     */
    fun text(text: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, remap: Boolean = true) {
        val f = if (bold) NVGRenderer.boldFont else font
        val deliberateBlack = luminance(color and 0xFFFFFF) < 0.002
        val color = if (remap && !deliberateBlack) paletteOf(color) else color
        if (!deliberateBlack && currentLabel != null && !cardDrawn) {
            val label = currentLabel ?: "?"
            if (haloLogged.add(label)) Debug.line("HUD Look", "halo text for $label")
            NVGRenderer.textHalo(text, x, y, size, (color ushr 24) / 255f, f)
        }
        NVGRenderer.text(text, x, y, size, if (deliberateBlack) color else legible(color), f)
    }

    /** HUDs whose colours mean a category (class colours on the map), so [paletteOf] leaves them alone. */
    private val KEEP_COLORS = setOf("Dungeon Map")

    /**
     * The palette version of a colour a HUD picked itself (Minecraft's legacy greys, greens, reds and golds), so every
     * HUD speaks the same colours. Other colours (aqua, purple, blue ... often a category) pass through. Game text drawn
     * with [componentText] is never remapped.
     */
    fun paletteOf(argb: Int): Int {
        if (currentLabel == null || currentLabel in KEEP_COLORS) return argb
        val mapped = when (argb and 0xFFFFFF) {
            0x55FF55, 0x00AA00 -> Palette.GOOD_SEE
            0xFF5555, 0xAA0000 -> Palette.BAD_SEE
            0xFFAA00, 0xFFD700 -> Palette.GOLD
            0xFFFFFF, 0xE8E8E8, 0xDDDDDD -> Palette.TEXT
            0xBBBBBB, 0xAAAAAA -> Palette.MUTED_SEE
            0x888888, 0x777777, 0x555555 -> Palette.FAINT
            else -> return argb
        }
        return (argb and 0xFF000000.toInt()) or (mapped and 0xFFFFFF)
    }

    /** A label / value row of a full card ([rows]) or a compact HUD ([compact]). */
    data class Row(val label: String, val value: String, val color: Int = Palette.TEXT)

    /** Full card header: bold title, then a muted subtitle ("Inferno Demonlord IV · Spawning") when there is one. */
    fun headerHeight(size: Float, subtitle: Boolean): Float = size * 1.25f + if (subtitle) size * 1.15f else 0f

    fun header(title: String, subtitle: String?, x: Float, y: Float, size: Float) {
        text(title, x, y, size * 1.15f, Palette.TEXT, bold = true)
        if (subtitle != null) text(subtitle, x, y + size * 1.3f, size * 0.9f, Palette.MUTED)
    }

    /** Full card headline: one big number in the accent, a muted label after it, an optional faint note at the right edge. */
    fun headlineHeight(size: Float): Float = size * 2.3f

    fun headline(value: String, label: String, note: String?, x: Float, y: Float, w: Float, size: Float) {
        val big = size * 2.1f
        text(value, x, y, big, Theme.hudAccentRgba, bold = true, remap = false)
        text(label, x + width(value, big, bold = true) + size * 0.6f, y + big - size * 1.15f, size, Palette.MUTED)
        if (note != null) text(note, x + w - width(note, size * 0.9f), y + big - size * 1.1f, size * 0.9f, Palette.FAINT)
    }

    fun divider(x: Float, y: Float, w: Float, scale: Float) = NVGRenderer.rect(x, y, w, scale, Palette.LINE, 0f)

    /** Widest "label  value" of [rows] at [size], for sizing a card. */
    fun rowsWidth(rows: List<Row>, size: Float): Float = rows.maxOfOrNull { width(it.label, size) + size * 2f + width(it.value, size) } ?: 0f

    /** Full card rows: label in muted on the left, value on the right edge. Returns their height. */
    fun rows(rows: List<Row>, x: Float, y: Float, w: Float, size: Float): Float {
        val step = size * 1.5f
        rows.forEachIndexed { i, r ->
            val ry = y + i * step
            text(r.label, x, ry, size, Palette.MUTED)
            text(r.value, x + w - width(r.value, size), ry, size, r.color)
        }
        return rows.size * step - (step - size)
    }

    /** Height of [rows] drawn by [rows]. */
    fun rowsHeight(count: Int, size: Float): Float = if (count == 0) 0f else count * size * 1.5f - size * 0.5f

    /** Compact (see-through) HUD lines: "label value", label muted, tight line step. An empty label draws the value alone. */
    fun compactWidth(rows: List<Row>, size: Float): Float =
        rows.maxOfOrNull { (if (it.label.isEmpty()) 0f else width(it.label, size) + width(" ", size)) + width(it.value, size) } ?: 0f

    fun compact(rows: List<Row>, x: Float, y: Float, size: Float): Float {
        val step = size * LINE_HEIGHT
        rows.forEachIndexed { i, r ->
            val ry = y + i * step
            var vx = x
            if (r.label.isNotEmpty()) {
                text(r.label, x, ry, size, Palette.MUTED_SEE)
                vx += width(r.label, size) + width(" ", size)
            }
            text(r.value, vx, ry, size, r.color)
        }
        return rows.size * step - (step - size)
    }

    private fun luminance(rgb: Int) = Palette.luminance(rgb)

    /** Below this a colour is under 4.5:1 against black, so it vanishes on a dark scene. */
    private const val MIN_LUMINANCE = 0.2

    private fun towardWhite(rgb: Int, t: Double): Int {
        fun channel(shift: Int): Int { val c = (rgb shr shift) and 255; return (c + (255 - c) * t).toInt().coerceIn(0, 255) }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** [argb] lifted toward white just far enough to read on a dark scene; light colours and its alpha stay as they are. */
    fun legible(argb: Int): Int {
        val rgb = argb and 0xFFFFFF
        if (luminance(rgb) >= MIN_LUMINANCE) return argb
        var t = 0.0
        var lifted = rgb
        while (t < 1.0 && luminance(lifted) < MIN_LUMINANCE) { t += 0.05; lifted = towardWhite(rgb, t) }
        return (argb and 0xFF000000.toInt()) or lifted
    }

    /** [component] with every colour lifted by [legible], for text drawn straight with Minecraft's font. */
    fun legibleComponent(component: Component): Component {
        val out = Component.empty()
        component.visit({ style, segment ->
            val rgb = style.color?.value
            out.append(Component.literal(segment).withStyle(if (rgb != null) style.withColor(legible(rgb)) else style))
            Optional.empty<Unit>()
        }, net.minecraft.network.chat.Style.EMPTY)
        return out
    }

    /** Width of [component] drawn with [componentText]. */
    fun width(component: Component, size: Float): Float {
        var w = 0f
        component.visit({ _, segment -> w += width(segment, size); Optional.empty<Unit>() }, net.minecraft.network.chat.Style.EMPTY)
        return w
    }

    /** [component] segment by segment in its own colours (white where it has none), faded by [alpha]. */
    fun componentText(component: Component, x: Float, y: Float, size: Float, alpha: Float = 1f) {
        var cx = x
        component.visit({ style, segment ->
            val rgb = style.color?.value ?: 0xFFFFFF
            text(segment, cx, y, size, withAlpha(0xFF000000.toInt() or rgb, alpha), remap = false)
            cx += width(segment, size)
            Optional.empty<Unit>()
        }, net.minecraft.network.chat.Style.EMPTY)
    }

    fun withAlpha(argb: Int, alpha: Float): Int {
        if (alpha >= 1f) return argb
        val a = ((argb ushr 24) * alpha.coerceIn(0f, 1f)).toInt()
        return (a shl 24) or (argb and 0xFFFFFF)
    }
}
