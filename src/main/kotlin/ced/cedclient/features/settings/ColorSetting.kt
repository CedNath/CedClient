package ced.cedclient.features.settings

import ced.cedclient.ui.nvg.NVGRenderer
import ced.cedclient.utils.Color
import ced.cedclient.utils.Colors
import com.google.gson.Gson
import com.google.gson.JsonElement
import net.minecraft.client.input.MouseButtonEvent

/**
 * Not in the original roadmap — added as a stub ahead of Phase 6's
 * ColorAnimation.kt/color-wheel popup. For now this only draws the swatch;
 * clicking just marks it hovered/selected (`picking`) as a hook for the real
 * picker popup to attach to later. Persistence rides on Color's own
 * @JsonAdapter (writes/reads "#RRGGBBAA"), so read()/write() just delegate
 * to gson instead of hand-rolling the hex conversion again.
 */
class ColorSetting(
    name: String,
    override val default: Color,
    description: String = ""
) : RenderableSetting<Color>(name, description), Saving {

    override var value: Color = default.copy()

    /** True while this setting's picker popup should be open — Phase 6 wires the actual popup to this. */
    var picking = false
        private set

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        val height = super.render(x, y, mouseX, mouseY)

        NVGRenderer.text(name, x + 6f, y + height / 2f - 6f, 12f, Colors.WHITE.rgba, NVGRenderer.defaultFont)

        val swatchSize = 13f
        val swatchX = x + width - swatchSize - 6f
        val swatchY = y + height / 2f - swatchSize / 2f
        NVGRenderer.rect(swatchX, swatchY, swatchSize, swatchSize, value.rgba, 2f)

        return height
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (!isHovered) return false
        picking = !picking
        return true
    }

    override fun read(element: JsonElement, gson: Gson) {
        value = gson.fromJson(element, Color::class.java)
    }

    override fun write(gson: Gson): JsonElement = gson.toJsonTree(value)
}