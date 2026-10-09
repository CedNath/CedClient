package ced.cedclient.features.settings

import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.ui.animations.EaseInOutAnimation
import ced.cedclient.utils.Colors
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import net.minecraft.client.input.MouseButtonEvent

/**
 * On/off toggle. `value` and `default` swap places compared to old
 * BooleanSetting(name, default) usage in the funqol modules — that
 * constructor shape is kept as-is so Phase 5 doesn't need to touch call sites.
 */
class BooleanSetting(
    name: String,
    override val default: Boolean,
    description: String = ""
) : RenderableSetting<Boolean>(name, description), Saving {

    override var value: Boolean = default

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        val height = super.render(x, y, mouseX, mouseY)

        NVGRenderer.text(name, x + 6f, y + height / 2f - 7f, 14f, Colors.WHITE.rgba, NVGRenderer.defaultFont)

        // Slide switch: grey with the knob left = off, green with the knob right = on.
        val trackW = 30f
        val trackH = 16f
        val trackX = x + width - trackW - 6f
        val trackY = y + height / 2f - trackH / 2f
        val t = toggleAnim.get(0f, 1f, !value)
        NVGRenderer.rect(trackX, trackY, trackW, trackH, (if (value) Colors.MINECRAFT_DARK_GREEN else Colors.MINECRAFT_DARK_GRAY).rgba, trackH / 2f)
        if (isHovered) NVGRenderer.hollowRect(trackX, trackY, trackW, trackH, 1f, Colors.WHITE.rgba, trackH / 2f)
        val knob = trackH - 4f
        val knobX = trackX + 2f + (trackW - knob - 4f) * t
        NVGRenderer.rect(knobX, trackY + 2f, knob, knob, (if (value) Colors.WHITE else Colors.MINECRAFT_GRAY).rgba, knob / 2f)

        return height
    }

    private val toggleAnim = EaseInOutAnimation(150)

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (!isHovered) return false
        value = !value
        toggleAnim.start()
        return true
    }

    override fun read(element: JsonElement, gson: Gson) {
        value = element.asBoolean
    }

    override fun write(gson: Gson): JsonElement = JsonPrimitive(value)
}