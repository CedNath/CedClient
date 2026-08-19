package ced.cedclient.features.settings

import ced.cedclient.ui.nvg.NVGRenderer
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

        val boxSize = 15f
        val boxX = x + width - boxSize - 6f
        val boxY = y + height / 2f - boxSize / 2f
        val boxColor = if (value) Colors.MINECRAFT_GREEN.rgba else Colors.gray26.rgba
        NVGRenderer.rect(boxX, boxY, boxSize, boxSize, boxColor, 2f)

        return height
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (!isHovered) return false
        value = !value
        return true
    }

    override fun read(element: JsonElement, gson: Gson) {
        value = element.asBoolean
    }

    override fun write(gson: Gson): JsonElement = JsonPrimitive(value)
}