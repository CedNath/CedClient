package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.utils.Color
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Crosshair shape, color and third-person crosshair (hooks: CrosshairMixin).
 * Vanilla's crosshair sprite is drawn with an inverting blend, so tinting it never shows the real color; the shapes
 * here are drawn as plain filled rectangles instead, with an optional black outline to stay visible on any background.
 */
object CrosshairTweaks : Module(
    "Crosshair",
    Category.Render,
    "Custom crosshair shape and color, and the crosshair in third person."
) {
    private const val VANILLA = "Vanilla"
    private val shape = DropdownSetting(
        "Shape", listOf(VANILLA, "Cross", "Cross + Dot", "Dot", "Circle", "Square", "X"), "Cross",
        "Crosshair shape. Vanilla keeps the game's own crosshair (its color can't be changed)."
    )
    private val color = ColorSetting("Crosshair Color", Color(255, 255, 255), "Crosshair color.").withDependency { shape.value != VANILLA }
    private val size = NumberSetting("Size", 5.0, 1.0, 20.0, 1.0, "Arm length / radius, in pixels.").withDependency { shape.value != VANILLA }
    private val thickness = NumberSetting("Thickness", 1.0, 0.1, 6.0, 0.1, "Line thickness in GUI pixels. Thin values (0.1) draw a single screen pixel, the thinnest line possible.").withDependency { shape.value != VANILLA }
    private val gap = NumberSetting("Gap", 2.0, 0.0, 10.0, 1.0, "Empty space around the center (Cross and X).")
        .withDependency { shape.value == "Cross" || shape.value == "Cross + Dot" || shape.value == "X" }
    private val outline = BooleanSetting("Outline", true, "Black outline so the crosshair stays visible on any background.").withDependency { shape.value != VANILLA }
    private val thirdPerson = BooleanSetting("Third Person", true, "Shows the crosshair in third-person view too.")

    init { addSettings(shape, color, size, thickness, gap, outline, thirdPerson) }

    @JvmStatic fun showInThirdPerson() = isEnabled && thirdPerson.value

    /** Draws the custom crosshair centered on [cx], [cy]; false means leave vanilla's crosshair alone. */
    @JvmStatic
    fun draw(g: GuiGraphicsExtractor, cx: Int, cy: Int): Boolean {
        if (!isEnabled || shape.value == VANILLA) return false
        val fill = color.value.rgba or (0xFF shl 24)
        // Drawn in screen pixels (one GUI pixel is several of them), so lines can be thinner than a GUI pixel.
        val gs = Minecraft.getInstance().window.guiScale.toFloat()
        val dcx = (cx * gs + gs / 2).toInt()
        val dcy = (cy * gs + gs / 2).toInt()
        g.pose().pushMatrix()
        g.pose().scale(1f / gs, 1f / gs)
        if (outline.value) emit(dcx, dcy, gs) { x1, y1, x2, y2 -> g.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, OUTLINE) }
        emit(dcx, dcy, gs) { x1, y1, x2, y2 -> g.fill(x1, y1, x2, y2, fill) }
        g.pose().popMatrix()
        return true
    }

    private const val OUTLINE = 0xFF000000.toInt()

    /** Calls [rect] with every filled rectangle (x1, y1, x2, y2; x2/y2 exclusive, screen pixels) of the chosen shape. */
    private inline fun emit(cx: Int, cy: Int, gs: Float, rect: (Int, Int, Int, Int) -> Unit) {
        val s = (size.value * gs).roundToInt()
        val t = max(1, (thickness.value * gs).roundToInt())
        val gp = (gap.value * gs).roundToInt()
        val a = -(t / 2) // thick lines are centered on the middle pixel
        val b = a + t
        when (shape.value) {
            "Cross", "Cross + Dot" -> {
                rect(cx + a - gp - s, cy + a, cx + a - gp, cy + b) // left
                rect(cx + b + gp, cy + a, cx + b + gp + s, cy + b) // right
                rect(cx + a, cy + a - gp - s, cx + b, cy + a - gp) // up
                rect(cx + a, cy + b + gp, cx + b, cy + b + gp + s) // down
                if (shape.value == "Cross + Dot") rect(cx + a, cy + a, cx + b, cy + b)
            }
            "Dot" -> {
                val r = max(1, s / 2)
                for (dy in -r..r) {
                    val hw = sqrt((r * r - dy * dy).toDouble() + 0.25).toInt()
                    rect(cx - hw, cy + dy, cx + hw + 1, cy + dy + 1)
                }
            }
            "Circle" -> {
                val inner = s - t
                for (dy in -s..s) {
                    val ow = sqrt((s * s - dy * dy).toDouble() + 0.25).toInt()
                    if (inner > 0 && dy > -inner && dy < inner) {
                        val iw = sqrt((inner * inner - dy * dy).toDouble() + 0.25).toInt()
                        rect(cx - ow, cy + dy, cx - iw, cy + dy + 1)
                        rect(cx + iw + 1, cy + dy, cx + ow + 1, cy + dy + 1)
                    } else rect(cx - ow, cy + dy, cx + ow + 1, cy + dy + 1)
                }
            }
            "Square" -> {
                val lo = -s
                val hi = s + 1
                rect(cx + lo, cy + lo, cx + hi, cy + lo + t) // top
                rect(cx + lo, cy + hi - t, cx + hi, cy + hi) // bottom
                rect(cx + lo, cy + lo + t, cx + lo + t, cy + hi - t) // left
                rect(cx + hi - t, cy + lo + t, cx + hi, cy + hi - t) // right
            }
            "X" -> for (i in gp..s + gp) {
                rect(cx + i + a, cy + i + a, cx + i + b, cy + i + b)
                rect(cx - i + a, cy + i + a, cx - i + b, cy + i + b)
                rect(cx + i + a, cy - i + a, cx + i + b, cy - i + b)
                rect(cx - i + a, cy - i + a, cx - i + b, cy - i + b)
            }
        }
    }
}
