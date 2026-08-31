package ced.cedclient.features.settings

import ced.cedclient.ui.clickgui.Panel
import ced.cedclient.ui.nvg.Gradient
import ced.cedclient.ui.nvg.NVGRenderer
import ced.cedclient.utils.Color
import ced.cedclient.utils.Colors
import com.google.gson.Gson
import com.google.gson.JsonElement
import net.minecraft.client.input.MouseButtonEvent

/**
 * Draws the swatch plus, when clicked, an inline HSB picker below the row --
 * same expand-in-place pattern DropdownSetting uses for its option list
 * (getHeight() grows, extra content renders below y + Panel.HEIGHT), and
 * the same self-contained "no mouseDragged hook exists, so render() applies
 * the live mouse position every frame while a `dragging*` flag is true"
 * approach NumberSetting's slider uses.
 *
 * Picker has three controls, top to bottom:
 *  - a saturation/brightness square (white->hue horizontal gradient with a
 *    transparent->black vertical gradient layered on top -- the standard
 *    two-gradient trick for an HSB square, built from two real
 *    NVGRenderer.gradientRect calls, not an unsupported single 2D gradient)
 *  - a hue strip (six gradientRect segments between the six key hues,
 *    since gradientRect only interpolates between two colors at a time)
 *  - an alpha strip (checkered background so transparency is visible,
 *    with a transparent->opaque gradient of the current color over it)
 *
 * Previously this only toggled `picking` with no picker actually attached
 * to it (a stub ahead of this being built) -- this replaces that stub.
 */
class ColorSetting(
    name: String,
    override val default: Color,
    description: String = ""
) : RenderableSetting<Color>(name, description), Saving {

    override var value: Color = default.copy()

    /** True while the inline picker below this row is open. */
    var picking = false
        private set

    private var draggingSatBri = false
    private var draggingHue = false
    private var draggingAlpha = false

    override fun getHeight(): Float =
        if (picking) Panel.HEIGHT + PICKER_HEIGHT else Panel.HEIGHT

    // --- layout helpers, all relative to the last render()'s x/y (lastX/lastY from RenderableSetting) ---
    private val satBriX get() = lastX + MARGIN
    private val satBriY get() = lastY + Panel.HEIGHT + MARGIN
    private val satBriWidth get() = width - MARGIN * 2

    private val hueY get() = satBriY + SAT_BRI_HEIGHT + MARGIN
    private val alphaY get() = hueY + STRIP_HEIGHT + MARGIN

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        val height = super.render(x, y, mouseX, mouseY)

        NVGRenderer.text(name, x + 6f, y + Panel.HEIGHT / 2f - 6f, 12f, Colors.WHITE.rgba, NVGRenderer.defaultFont)

        val swatchSize = 13f
        val swatchX = x + width - swatchSize - 6f
        val swatchY = y + Panel.HEIGHT / 2f - swatchSize / 2f
        NVGRenderer.rect(swatchX, swatchY, swatchSize, swatchSize, value.rgba, 2f)

        if (picking) {
            if (draggingSatBri) applySatBriFromMouse(mouseX, mouseY)
            if (draggingHue) applyHueFromMouse(mouseX)
            if (draggingAlpha) applyAlphaFromMouse(mouseX)

            drawSatBriSquare()
            drawHueStrip()
            drawAlphaStrip()
        }

        return height
    }

    private fun drawSatBriSquare() {
        val hueColor = Color(value.hue, 1f, 1f, 1f).rgba

        // white (0% sat) -> full hue (100% sat) left to right
        NVGRenderer.gradientRect(satBriX, satBriY, satBriWidth, SAT_BRI_HEIGHT, Colors.WHITE.rgba, hueColor, Gradient.LeftToRight, 4f)
        // transparent (100% bri) -> black (0% bri) top to bottom, layered on top
        NVGRenderer.gradientRect(satBriX, satBriY, satBriWidth, SAT_BRI_HEIGHT, Colors.TRANSPARENT.rgba, Colors.BLACK.rgba, Gradient.TopToBottom, 4f)

        val indicatorX = satBriX + value.saturation * satBriWidth
        val indicatorY = satBriY + (1f - value.brightness) * SAT_BRI_HEIGHT
        NVGRenderer.circle(indicatorX, indicatorY, 5f, Colors.WHITE.rgba)
        NVGRenderer.circle(indicatorX, indicatorY, 3.5f, value.rgba)
    }

    private fun drawHueStrip() {
        val segmentWidth = satBriWidth / HUE_STOPS.size
        for (i in HUE_STOPS.indices) {
            val stopX = satBriX + segmentWidth * i
            val hue1 = HUE_STOPS[i]
            val hue2 = if (i == HUE_STOPS.size - 1) 1f else HUE_STOPS[i + 1]
            val color1 = Color(hue1, 1f, 1f, 1f).rgba
            val color2 = Color(hue2, 1f, 1f, 1f).rgba
            NVGRenderer.gradientRect(stopX, hueY, segmentWidth, STRIP_HEIGHT, color1, color2, Gradient.LeftToRight, 3f)
        }

        val indicatorX = satBriX + value.hue * satBriWidth
        NVGRenderer.rect(indicatorX - 1.5f, hueY - 2f, 3f, STRIP_HEIGHT + 4f, Colors.WHITE.rgba, 1.5f)
    }

    private fun drawAlphaStrip() {
        // small checkerboard so transparency is actually visible under the gradient
        val checkerSize = STRIP_HEIGHT / 2f
        var checkerX = satBriX
        var col = 0
        while (checkerX < satBriX + satBriWidth) {
            val cellWidth = minOf(checkerSize, satBriX + satBriWidth - checkerX)
            NVGRenderer.rect(checkerX, alphaY, cellWidth, STRIP_HEIGHT, (if (col % 2 == 0) Colors.gray38 else Colors.gray26).rgba)
            checkerX += checkerSize
            col++
        }

        val transparent = Color(value.hue, value.saturation, value.brightness, 0f).rgba
        val opaque = Color(value.hue, value.saturation, value.brightness, 1f).rgba
        NVGRenderer.gradientRect(satBriX, alphaY, satBriWidth, STRIP_HEIGHT, transparent, opaque, Gradient.LeftToRight, 3f)

        val indicatorX = satBriX + value.alphaFloat * satBriWidth
        NVGRenderer.rect(indicatorX - 1.5f, alphaY - 2f, 3f, STRIP_HEIGHT + 4f, Colors.WHITE.rgba, 1.5f)
    }

    private fun applySatBriFromMouse(mouseX: Float, mouseY: Float) {
        value.saturation = ((mouseX - satBriX) / satBriWidth).coerceIn(0f, 1f)
        value.brightness = 1f - ((mouseY - satBriY) / SAT_BRI_HEIGHT).coerceIn(0f, 1f)
    }

    private fun applyHueFromMouse(mouseX: Float) {
        value.hue = ((mouseX - satBriX) / satBriWidth).coerceIn(0f, 1f)
    }

    private fun applyAlphaFromMouse(mouseX: Float) {
        value.alphaFloat = ((mouseX - satBriX) / satBriWidth).coerceIn(0f, 1f)
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        val headerHovered = mouseX in lastX..(lastX + width) && mouseY in lastY..(lastY + Panel.HEIGHT)
        if (headerHovered) {
            picking = !picking
            return true
        }

        if (picking) {
            if (mouseX in satBriX..(satBriX + satBriWidth) && mouseY in satBriY..(satBriY + SAT_BRI_HEIGHT)) {
                draggingSatBri = true
                applySatBriFromMouse(mouseX, mouseY)
                return true
            }
            if (mouseX in satBriX..(satBriX + satBriWidth) && mouseY in hueY..(hueY + STRIP_HEIGHT)) {
                draggingHue = true
                applyHueFromMouse(mouseX)
                return true
            }
            if (mouseX in satBriX..(satBriX + satBriWidth) && mouseY in alphaY..(alphaY + STRIP_HEIGHT)) {
                draggingAlpha = true
                applyAlphaFromMouse(mouseX)
                return true
            }
        }

        return false
    }

    override fun mouseReleased(click: MouseButtonEvent) {
        draggingSatBri = false
        draggingHue = false
        draggingAlpha = false
    }

    override fun read(element: JsonElement, gson: Gson) {
        value = gson.fromJson(element, Color::class.java)
    }

    override fun write(gson: Gson): JsonElement = gson.toJsonTree(value)

    companion object {
        private const val MARGIN = 6f
        private const val SAT_BRI_HEIGHT = 70f
        private const val STRIP_HEIGHT = 12f
        private const val PICKER_HEIGHT = MARGIN + SAT_BRI_HEIGHT + MARGIN + STRIP_HEIGHT + MARGIN + STRIP_HEIGHT + MARGIN

        // red -> yellow -> green -> cyan -> blue -> magenta (wraps back to red in drawHueStrip)
        private val HUE_STOPS = floatArrayOf(0f, 1f / 6f, 2f / 6f, 3f / 6f, 4f / 6f, 5f / 6f)
    }
}