package ced.cedclient.features.settings

import ced.cedclient.ui.clickgui.Panel
import ced.cedclient.render.nvg.Gradient
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Color
import ced.cedclient.utils.Colors
import com.google.gson.Gson
import com.google.gson.JsonElement
import ced.cedclient.utils.ui.TextInputHandler
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW

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

    // A copy, like the initial value: editing the colour afterwards must not change the default itself.
    override fun reset() {
        value = default.copy()
    }

    /** True while the inline picker below this row is open. */
    var picking = false
//#if CHEAT
        // Public setter: FilterPopup opens one straight away for a per-entry ESP colour.
//#else
//$        private set
//#endif

    private var draggingSatBri = false
    private var draggingHue = false
    private var draggingAlpha = false

    // Hex box under the strips: follows the picker until clicked, then typing a valid #RRGGBB / #RRGGBBAA applies
    // it live; Esc puts back the colour from before typing.
    private var hexText = ""
    private var beforeEdit: Color? = null
    private val hexInput = TextInputHandler({ hexText }) { typed ->
        hexText = typed.uppercase().filter { it == '#' || it in "0123456789ABCDEF" }.take(9)
        parseHex(hexText)?.let { value = it }
    }

    override fun getHeight(): Float =
        if (picking) Panel.HEIGHT + PICKER_HEIGHT else Panel.HEIGHT

    // --- layout helpers, all relative to the last render()'s x/y (lastX/lastY from RenderableSetting) ---
    private val satBriX get() = lastX + MARGIN
    private val satBriY get() = lastY + Panel.HEIGHT + MARGIN
    private val satBriWidth get() = width - MARGIN * 2

    private val hueY get() = satBriY + SAT_BRI_HEIGHT + MARGIN
    private val alphaY get() = hueY + STRIP_HEIGHT + MARGIN
    private val hexY get() = alphaY + STRIP_HEIGHT + MARGIN

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        val height = super.render(x, y, mouseX, mouseY)

        val font = NVGRenderer.defaultFont
        NVGRenderer.text(name, x + 6f, y + Panel.HEIGHT / 2f - 7f, 14f, Colors.WHITE.rgba, font)

        // Colour pill (checker under it so see-through colours read as such) + hex code beside it.
        val pillW = 30f
        val pillH = 16f
        val pillX = x + width - pillW - 8f
        val pillY = y + Panel.HEIGHT / 2f - pillH / 2f
        if (value.alphaFloat < 1f) drawChecker(pillX, pillY, pillW, pillH, pillH / 2f)
        NVGRenderer.rect(pillX, pillY, pillW, pillH, value.rgba, pillH / 2f)
        NVGRenderer.hollowRect(pillX, pillY, pillW, pillH, 1f, (if (isHovered || picking) Colors.WHITE else Colors.INPUT_BORDER).rgba, pillH / 2f)
        val hex = "#%06X".format(value.rgba and 0xFFFFFF)
        NVGRenderer.text(hex, pillX - 8f - NVGRenderer.textWidth(hex, 12f, font), y + Panel.HEIGHT / 2f - 6f, 12f, Colors.MINECRAFT_GRAY.rgba, font)

        if (picking) {
            // Rounded backing for the picker, like the menu's other boxes.
            NVGRenderer.rect(x + 3f, y + Panel.HEIGHT + 1f, width - 6f, PICKER_HEIGHT - 2f, Colors.INPUT_BG.rgba, 6f)
            if (draggingSatBri) applySatBriFromMouse(mouseX, mouseY)
            if (draggingHue) applyHueFromMouse(mouseX)
            if (draggingAlpha) applyAlphaFromMouse(mouseX)

            drawSatBriSquare()
            drawHueStrip()
            drawAlphaStrip()
            drawHexInput(mouseX, mouseY)
        }

        return height
    }

    private fun drawHexInput(mouseX: Float, mouseY: Float) {
        val editing = hexInput.isActive
        if (!editing) {
            hexText = if (value.alpha == 255) "#%06X".format(value.rgba and 0xFFFFFF)
            else "#%06X%02X".format(value.rgba and 0xFFFFFF, value.alpha)
            beforeEdit = null
        } else if (beforeEdit == null) beforeEdit = value.copy()
        val valid = parseHex(hexText) != null
        val border = when {
            editing && !valid -> Colors.MINECRAFT_RED
            editing -> Colors.WHITE
            else -> Colors.INPUT_BORDER
        }
        NVGRenderer.rect(satBriX, hexY, satBriWidth, HEX_HEIGHT, Colors.SETTING_BG.rgba, 4f)
        NVGRenderer.hollowRect(satBriX, hexY, satBriWidth, HEX_HEIGHT, 1f, border.rgba, 4f)
        // Swatch at the right end, so the typed colour is visible while typing.
        val sw = HEX_HEIGHT - 6f
        NVGRenderer.rect(satBriX + satBriWidth - sw - 3f, hexY + 3f, sw, sw, value.rgba or 0xFF000000.toInt(), 3f)
        hexInput.x = satBriX + 2f
        hexInput.y = hexY + 1f
        hexInput.width = satBriWidth - sw - 10f
        hexInput.height = HEX_HEIGHT - 2f
        hexInput.draw(mouseX, mouseY)
    }

    /** "#RRGGBB" / "RRGGBB" (opaque) or with "AA" alpha on the end, else null. */
    private fun parseHex(text: String): Color? {
        val hex = text.removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        val n = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) Color((n.toInt() and 0xFFFFFF) or 0xFF000000.toInt())
        else Color(((n and 0xFF) shl 24 or (n shr 8)).toInt())
    }

    private fun drawSatBriSquare() {
        val hueColor = Color(value.hue, 1f, 1f, 1f).rgba

        // white (0% sat) -> full hue (100% sat) left to right
        NVGRenderer.gradientRect(satBriX, satBriY, satBriWidth, SAT_BRI_HEIGHT, Colors.WHITE.rgba, hueColor, Gradient.LeftToRight, 4f)
        // transparent (100% bri) -> black (0% bri) top to bottom, layered on top
        NVGRenderer.gradientRect(satBriX, satBriY, satBriWidth, SAT_BRI_HEIGHT, Colors.TRANSPARENT.rgba, Colors.BLACK.rgba, Gradient.TopToBottom, 4f)

        val indicatorX = satBriX + value.saturation * satBriWidth
        val indicatorY = satBriY + (1f - value.brightness) * SAT_BRI_HEIGHT
        knob(indicatorX, indicatorY, Color(value.hue, value.saturation, value.brightness, 1f).rgba)
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

        knob(satBriX + value.hue * satBriWidth, hueY + STRIP_HEIGHT / 2f, Color(value.hue, 1f, 1f, 1f).rgba)
    }

    private fun drawAlphaStrip() {
        drawChecker(satBriX, alphaY, satBriWidth, STRIP_HEIGHT, 0f)

        val transparent = Color(value.hue, value.saturation, value.brightness, 0f).rgba
        val opaque = Color(value.hue, value.saturation, value.brightness, 1f).rgba
        NVGRenderer.gradientRect(satBriX, alphaY, satBriWidth, STRIP_HEIGHT, transparent, opaque, Gradient.LeftToRight, 3f)

        knob(satBriX + value.alphaFloat * satBriWidth, alphaY + STRIP_HEIGHT / 2f, value.rgba)
    }

    /** Round handle: white ring with the colour it picks inside. */
    private fun knob(cx: Float, cy: Float, fill: Int) {
        NVGRenderer.circle(cx, cy, 6.5f, 0x66000000)
        NVGRenderer.circle(cx, cy, 5.5f, Colors.WHITE.rgba)
        NVGRenderer.circle(cx, cy, 3.8f, fill or 0xFF000000.toInt())
    }

    /** Small grey checkerboard, so transparency is visible under a colour. */
    private fun drawChecker(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        NVGRenderer.rect(x, y, w, h, Colors.gray38.rgba, radius)
        val cell = h / 2f
        NVGRenderer.pushScissor(x + radius / 2f, y, w - radius, h)
        var cx = x
        var col = 0
        while (cx < x + w) {
            NVGRenderer.rect(cx, y + if (col % 2 == 0) 0f else cell, minOf(cell, x + w - cx), cell, Colors.gray26.rgba)
            cx += cell
            col++
        }
        NVGRenderer.popScissor()
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
        // Any click: into the hex box starts typing, anywhere else stops it (keeping what was typed).
        if (picking && hexInput.mouseClicked(mouseX, mouseY, click)) return true

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
        hexInput.mouseReleased()
    }

    override fun keyTyped(input: CharacterEvent): Boolean = hexInput.keyTyped(input)

    override fun keyPressed(input: KeyEvent): Boolean {
        if (!hexInput.isActive) return false
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) beforeEdit?.let { value = it.copy() }
        hexInput.keyPressed(input)
        return true // typing owns the keyboard until Enter / Esc
    }

    /** An earlier default (0xRRGGBB): a saved colour still equal to it is moved to the current default on load. */
    var oldDefault: Int? = null

    override fun read(element: JsonElement, gson: Gson) {
        value = gson.fromJson(element, Color::class.java)
        val old = oldDefault ?: return
        val rgb = value.rgba and 0xFFFFFF
        // ±1 per channel: the colour goes through HSB and back when saved / loaded.
        if ((0..16 step 8).all { s -> kotlin.math.abs((rgb shr s and 0xFF) - (old shr s and 0xFF)) <= 1 }) value = default.copy()
    }

    override fun write(gson: Gson): JsonElement = gson.toJsonTree(value)

    companion object {
        private const val MARGIN = 6f
        private const val SAT_BRI_HEIGHT = 70f
        private const val STRIP_HEIGHT = 12f
        private const val HEX_HEIGHT = 18f
        private const val PICKER_HEIGHT = MARGIN + SAT_BRI_HEIGHT + MARGIN + STRIP_HEIGHT + MARGIN + STRIP_HEIGHT + MARGIN + HEX_HEIGHT + MARGIN

        // red -> yellow -> green -> cyan -> blue -> magenta (wraps back to red in drawHueStrip)
        private val HUE_STOPS = floatArrayOf(0f, 1f / 6f, 2f / 6f, 3f / 6f, 4f / 6f, 5f / 6f)
    }
}
