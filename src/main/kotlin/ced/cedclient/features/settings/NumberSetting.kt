package ced.cedclient.features.settings

import ced.cedclient.ui.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import net.minecraft.client.input.MouseButtonEvent
import kotlin.math.roundToLong

/**
 * A draggable numeric slider. Constructor shape — (name, default, min, max,
 * step) — matches the old NumberSetting so the funqol modules (Phase 5)
 * don't need call-site changes. The settable `advanced` flag it used to
 * declare itself now lives on RenderableSetting (see there) since EntityESP
 * (Phase 6) needs it on BooleanSetting/ActionSetting too, not just sliders.
 *
 * RenderableSetting's contract has no mouseDragged() hook yet (that's a
 * Panel/ClickGui-level concern, Phase 4), so dragging is self-contained here
 * the same way Panel.kt handles its own header-drag: mouseClicked() starts
 * it, render() updates `value` from the live mouseX every frame while it's
 * true, and mouseReleased() (routed through by ClickGUI same as Panel's) is
 * what ends it -- no raw GLFW button-state polling needed.
 */
class NumberSetting(
    name: String,
    override val default: Double,
    val min: Double,
    val max: Double,
    val step: Double = 1.0,
    description: String = ""
) : RenderableSetting<Double>(name, description), Saving {

    override var value: Double = default
        set(newValue) {
            val snapped = if (step > 0.0) (newValue / step).roundToLong() * step else newValue
            field = snapped.coerceIn(min, max)
        }

    private var dragging = false

    private val decimals: Int
        get() {
            val stepStr = step.toString().trimEnd('0')
            val dot = stepStr.indexOf('.')
            return if (dot < 0) 0 else (stepStr.length - dot - 1).coerceAtMost(3)
        }

    private fun format(v: Double): String =
        if (decimals == 0) v.toLong().toString() else "%.${decimals}f".format(v)

    private fun applyFromMouseX(mouseX: Float) {
        val sliderX = lastX
        val percent = ((mouseX - sliderX) / width).coerceIn(0f, 1f)
        value = min + (max - min) * percent
    }

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        val height = super.render(x, y, mouseX, mouseY)

        if (dragging) applyFromMouseX(mouseX)

        NVGRenderer.text(name, x + 6f, y + 4f, 14f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
        NVGRenderer.text(
            format(value), x + width - 6f - NVGRenderer.textWidth(format(value), 14f, NVGRenderer.defaultFont),
            y + 4f, 14f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont
        )

        val barY = y + height - 6f
        val barHeight = 3f
        NVGRenderer.rect(x + 6f, barY, width - 12f, barHeight, Colors.gray26.rgba, 1.5f)

        val percent = if (max > min) ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f) else 0f
        val fillWidth = (width - 12f) * percent
        if (fillWidth > 0f) NVGRenderer.rect(x + 6f, barY, fillWidth, barHeight, Colors.MINECRAFT_BLUE.rgba, 1.5f)

        return height
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (!isHovered) return false
        dragging = true
        applyFromMouseX(mouseX)
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent) {
        dragging = false
    }

    override fun read(element: JsonElement, gson: Gson) {
        value = element.asDouble
    }

    override fun write(gson: Gson): JsonElement = JsonPrimitive(value)
}