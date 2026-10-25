package ced.cedclient.features.impl.render

import ced.cedclient.features.Module
import ced.cedclient.features.impl.misc.Theme
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
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
        val label = HudElement.all.firstOrNull { it.panelX == x && it.panelY == y }?.label
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

    private class Bg(val on: BooleanSetting?, val opacity: NumberSetting)
    private val bgSettings = HashMap<String, Bg>()

    /**
     * Gives every HUD module a Background toggle (off by default) and a Background Opacity slider, unless it already
     * has its own. Run once after the modules are registered and before the config loads, so the values are saved.
     */
    fun attachBackgroundSettings() {
        for (e in HudElement.all) {
            val m = e as? Module ?: continue
            if (e.label == "Chat Alerts") continue // text only, no card
            val own = m.settings.values.filter { it.name.contains("background", true) }
            if (own.any { it is NumberSetting } || own.any { it.name.contains("opacity", true) || it.name.contains("color", true) }) continue
            val on = if (own.isEmpty()) BooleanSetting("Background", false, "The card behind the HUD.").also { m.registerSetting(it) } else null
            val opacity = NumberSetting("Background Opacity", 100.0, 0.0, 100.0, 5.0, "How solid the card is (percent).").also { m.registerSetting(it) }
            bgSettings[e.label] = Bg(on, opacity)
        }
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
        if (bgSettings[label]?.on?.value == false) return
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

    fun width(text: String, size: Float) = NVGRenderer.textWidth(text, size, font)

    fun text(text: String, x: Float, y: Float, size: Float, color: Int) = NVGRenderer.text(text, x, y, size, color, font)

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
