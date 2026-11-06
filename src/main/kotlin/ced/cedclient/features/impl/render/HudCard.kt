package ced.cedclient.features.impl.render

import ced.cedclient.features.Module
import ced.cedclient.features.impl.misc.Theme
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.util.Optional

/**
 * NanoVG drawing for HUD panels, in the same rounded-card style as Time HUD / Daily Reset (background, accent
 * border, CedClient font). [draw] sets up the canvas so (0, 0) is the panel's top-left in GUI units; sizes passed
 * to the other functions are already multiplied by the panel's scale.
 */
object HudCard {
    const val BACKGROUND = 0xE8161620.toInt()
    private const val CORNER_RADIUS = 8f

    val font get() = NVGRenderer.defaultFont

    /** [behindLeap]: while the Leap Menu is open the panel is drawn inside its pass, under its dim backdrop and boxes. */
    fun draw(g: GuiGraphicsExtractor, x: Int, y: Int, behindLeap: Boolean = false, content: () -> Unit) {
        // Which HUD this is, so card() can use its style (panels are told apart by their spot).
        val label = labelAt(x, y)
        val full = {
            currentLabel = label
            val ratio = NVGRenderer.canvasWidth / g.guiWidth().toFloat()
            NVGRenderer.scale(ratio, ratio)
            NVGRenderer.translate(x.toFloat(), y.toFloat())
            content()
        }
        if (behindLeap && ced.cedclient.features.impl.funqol.LeapMenu.defer(full)) return
        NVGSpecialRenderer.draw(g, 0, 0, g.guiWidth(), g.guiHeight(), renderContent = full)
    }

    private var currentLabel: String? = null

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
     * itself and the Text Outline are chosen per HUD in the HUD editor (see [HudLook], [backgroundToggle], [outlineToggle]).
     * Run once after the modules are registered and before the config loads, so the values are saved.
     */
    fun attachBackgroundSettings() {
        for (e in HudElement.all) {
            val m = e as? Module ?: continue
            // Background / Text Outline are chosen in the HUD editor now, so the module's own toggles leave the menu (values still work and save).
            m.settings.values.filter { it is BooleanSetting && (isBackgroundName(it.name) || it.name.contains("text outline", true)) }.forEach { it.hide() }
            if (e.label == "Chat Alerts") continue // text only, no card
            val own = m.settings.values.filter { it.name.contains("background", true) }
            if (own.any { it is NumberSetting } || own.any { it.name.contains("opacity", true) || it.name.contains("color", true) }) continue
            if (own.isEmpty()) cardOffByDefault += e.label
            bgSettings[e.label] = Bg(NumberSetting("Background Opacity", 100.0, 0.0, 100.0, 5.0, "How solid the card is (percent).").also { m.registerSetting(it) })
        }
    }

    /** A card that stays off until the HUD editor turns it on (the alert cards). */
    fun offByDefault(label: String) { cardOffByDefault += label }

    /** "Background", "Show Background", "Alert Background" ... but not a colour or an opacity. */
    private fun isBackgroundName(name: String) =
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

    /** Text Outline of [e] for the editor toolbar. */
    fun outlineToggle(e: HudElement): Toggle =
        ownSetting(e) { it.contains("text outline", true) }?.let(::settingToggle) ?: Toggle({ HudLook.outline(e.label) }, { HudLook.setOutline(e.label, it) })

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

    /** The card behind a HUD, in that HUD's [HudStyle] (Classic is the original rounded card with an accent border). */
    fun card(width: Float, height: Float, scale: Float, alpha: Float = 1f, label: String? = currentLabel) {
        if (!cardOn(label)) return
        val alpha = alpha * opacityOf(label)
        val round = if (Theme.roundedHud.value) 1f else 0f
        val r = CORNER_RADIUS * scale * round
        val accent = withAlpha(Theme.hudAccentRgba, alpha)
        when (HudStyle.of(label)) {
            "Glass" -> {
                NVGRenderer.rect(0f, 0f, width, height, withAlpha(BACKGROUND, alpha * 0.62f), r)
                NVGRenderer.rect(r, 0f, width - 2 * r, 2f * scale, accent, 0f)
            }
            "Accent Top" -> {
                NVGRenderer.rect(0f, 0f, width, height, withAlpha(BACKGROUND, alpha), 3f * scale * round)
                NVGRenderer.rect(0f, 0f, width, 2.5f * scale, accent, 3f * scale * round)
            }
            "Rail" -> {
                NVGRenderer.rect(0f, 0f, width, height, withAlpha(BACKGROUND, alpha * 0.4f), 0f)
                NVGRenderer.rect(0f, 0f, 2.5f * scale, height, accent, 0f)
            }
            "Flat" -> NVGRenderer.rect(0f, 0f, width, height, withAlpha(BACKGROUND, alpha), 4f * scale * round)
            else -> {
                NVGRenderer.rect(0f, 0f, width, height, withAlpha(BACKGROUND, alpha), r)
                NVGRenderer.hollowRect(0f, 0f, width, height, scale, withAlpha(Theme.hudBorderRgba, alpha), r)
            }
        }
    }

    /**
     * Measured in the space [draw] draws in (canvas scaled to GUI units). Callers measure before [draw] runs, where the
     * pixel-snapped glyph size differs, so the columns came out too narrow and the text overlapped.
     */
    fun width(text: String, size: Float): Float {
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

    /**
     * HUD text. Colours too dark to read on a game scene (Hypixel's dark red and dark purple) are lifted by [legible], and
     * a HUD with its Background off gets a soft drop shadow so it also reads on bright scenes. Pure black is left alone:
     * HUDs draw it on purpose as an outline or shadow.
     */
    fun text(text: String, x: Float, y: Float, size: Float, color: Int) {
        val deliberateBlack = luminance(color and 0xFFFFFF) < 0.002
        if (!deliberateBlack && HudLook.outline(currentLabel)) {
            val o = size * 0.07f
            val black = withAlpha(0xFF000000.toInt(), (color ushr 24) / 255f)
            for (dx in -1..1) for (dy in -1..1) if (dx != 0 || dy != 0) NVGRenderer.text(text, x + dx * o, y + dy * o, size, black, font)
        }
        if (!deliberateBlack && !cardOn(currentLabel)) {
            val shadow = withAlpha(0xFF000000.toInt(), 0.7f * ((color ushr 24) / 255f))
            NVGRenderer.text(text, x + size * 0.08f, y + size * 0.08f, size, shadow, font)
        }
        NVGRenderer.text(text, x, y, size, if (deliberateBlack) color else legible(color), font)
    }

    private fun linear(channel: Int): Double {
        val v = channel / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun luminance(rgb: Int) = 0.2126 * linear((rgb shr 16) and 255) + 0.7152 * linear((rgb shr 8) and 255) + 0.0722 * linear(rgb and 255)

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
            text(segment, cx, y, size, withAlpha(0xFF000000.toInt() or rgb, alpha))
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
