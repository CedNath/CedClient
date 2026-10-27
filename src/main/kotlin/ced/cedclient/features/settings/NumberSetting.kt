package ced.cedclient.features.settings

import ced.cedclient.utils.Keys
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import net.minecraft.client.Minecraft
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import ced.cedclient.utils.ui.TextInputHandler
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
    description: String = "",
    /** Typed and arrow-key values may go past [max] (the slider still spans min..max). */
    val uncapped: Boolean = false
) : RenderableSetting<Double>(name, description), Saving {

    override var value: Double = default
        set(newValue) {
            val snapped = if (step > 0.0) (newValue / step).roundToLong() * step else newValue
            field = if (uncapped) snapped.coerceAtLeast(min) else snapped.coerceIn(min, max)
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
        val shown = format(value)
        valueX = x + width - 6f - NVGRenderer.textWidth(shown, 14f, NVGRenderer.defaultFont)
        if (input.isActive) {
            // A real text box over the value while typing: caret, selection, Ctrl+A / C / V / Z.
            val boxW = maxOf(NVGRenderer.textWidth(editText, 14f, NVGRenderer.defaultFont) + 14f, 56f)
            val boxX = x + width - 4f - boxW
            NVGRenderer.rect(boxX, y + 2f, boxW, 18f, Colors.INPUT_BG.rgba, 4f)
            NVGRenderer.hollowRect(boxX, y + 2f, boxW, 18f, 1f, Colors.WHITE.rgba, 4f)
            input.x = boxX + 1f
            input.y = y + 3f
            input.width = boxW - 2f
            input.height = 16f
            input.draw(mouseX, mouseY)
        } else {
            NVGRenderer.text(shown, valueX, y + 4f, 14f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)
        }

        val barY = y + height - 6f
        val barHeight = 3f
        NVGRenderer.rect(x + 6f, barY, width - 12f, barHeight, Colors.INPUT_BORDER.rgba, 1.5f)

        val percent = if (max > min) ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f) else 0f
        val fillWidth = (width - 12f) * percent
        if (fillWidth > 0f) NVGRenderer.rect(x + 6f, barY, fillWidth, barHeight, Colors.MINECRAFT_BLUE.rgba, 1.5f)

        return height
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (input.isActive) {
            if (input.mouseClicked(mouseX, mouseY, click)) return true
            commitEdit() // clicked elsewhere: keep what was typed
        }
        if (!isHovered) return false
        // Click the number itself to type a value: it starts selected, so typing replaces it.
        if (mouseX >= valueX - 6f && mouseY < lastY + 20f) {
            editText = format(value)
            input.focus(selectAll = true)
            return true
        }
        dragging = true
        applyFromMouseX(mouseX)
        return true
    }

    private var editText = ""
    private val input = TextInputHandler({ editText }) { editText = it.filter { c -> c in "0123456789.-" }.take(16) }
    private var valueX = 0f

    private fun commitEdit() {
        editText.toDoubleOrNull()?.let { value = it }
        input.deactivate()
    }

    override fun keyTyped(input: CharacterEvent): Boolean = this.input.keyTyped(input)

    override fun mouseReleased(click: MouseButtonEvent) {
        dragging = false
        input.mouseReleased()
    }

    /** Hover + arrow keys: one step per press (x10 with Shift), for values a drag can't hit exactly. */
    override fun keyPressed(input: KeyEvent): Boolean {
        if (this.input.isActive) {
            when (input.key()) {
                Keys.ENTER, Keys.KP_ENTER -> commitEdit()
                Keys.ESCAPE -> this.input.deactivate()
                else -> this.input.keyPressed(input)
            }
            return true // typing owns the keyboard until Enter / Esc
        }
        if (!isHovered) return false
        val dir = when (input.key()) {
            Keys.LEFT, Keys.DOWN -> -1
            Keys.RIGHT, Keys.UP -> 1
            else -> return false
        }
        value += dir * step * (if (Minecraft.getInstance().hasShiftDown()) 10 else 1)
        return true
    }

    override fun read(element: JsonElement, gson: Gson) {
        value = element.asDouble
    }

    override fun write(gson: Gson): JsonElement = JsonPrimitive(value)
}