package ced.cedclient.features.impl.render

import ced.cedclient.features.impl.misc.Theme
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

    fun draw(g: GuiGraphicsExtractor, x: Int, y: Int, content: () -> Unit) {
        NVGSpecialRenderer.draw(g, 0, 0, g.guiWidth(), g.guiHeight(), renderContent = {
            val ratio = NVGRenderer.canvasWidth / g.guiWidth().toFloat()
            NVGRenderer.scale(ratio, ratio)
            NVGRenderer.translate(x.toFloat(), y.toFloat())
            content()
        })
    }

    fun card(width: Float, height: Float, scale: Float, alpha: Float = 1f) {
        NVGRenderer.rect(0f, 0f, width, height, withAlpha(BACKGROUND, alpha), CORNER_RADIUS * scale)
        NVGRenderer.hollowRect(0f, 0f, width, height, scale, withAlpha(Theme.hudBorderRgba, alpha), CORNER_RADIUS * scale)
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
