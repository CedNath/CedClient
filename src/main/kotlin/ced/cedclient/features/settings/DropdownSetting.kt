package ced.cedclient.features.settings

import ced.cedclient.ui.clickgui.Panel
import ced.cedclient.ui.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import net.minecraft.client.input.MouseButtonEvent

/**
 * Replaces the old ModeSetting — a single value picked from a fixed list of
 * String options, persisted the same way ModeSetting was
 * (`settingsMap[name] = value.toString()` round-trip in ConfigManager).
 */
class DropdownSetting(
    name: String,
    val options: List<String>,
    override val default: String = options.first(),
    description: String = ""
) : RenderableSetting<String>(name, description), Saving {

    override var value: String = default

    var expanded = false
        private set

    private val optionHeight = Panel.HEIGHT

    override fun getHeight(): Float =
        if (expanded) Panel.HEIGHT + options.size * optionHeight else Panel.HEIGHT

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        val height = super.render(x, y, mouseX, mouseY)

        NVGRenderer.text(name, x + 6f, y + Panel.HEIGHT / 2f - 7f, 14f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
        NVGRenderer.text(
            value, x + width - 6f - NVGRenderer.textWidth(value, 14f, NVGRenderer.defaultFont),
            y + Panel.HEIGHT / 2f - 7f, 14f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont
        )

        if (expanded) {
            options.forEachIndexed { i, option ->
                val rowY = y + Panel.HEIGHT + i * optionHeight
                val rowHovered = mouseX in x..(x + width) && mouseY in rowY..(rowY + optionHeight)
                if (rowHovered) NVGRenderer.rect(x, rowY, width, optionHeight, Colors.gray38.rgba)

                val textColor = if (option == value) Colors.MINECRAFT_BLUE.rgba else Colors.MINECRAFT_GRAY.rgba
                NVGRenderer.text(option, x + 12f, rowY + optionHeight / 2f - 7f, 14f, textColor, NVGRenderer.defaultFont)
            }
        }

        return height
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        val headerHovered = mouseX in lastX..(lastX + width) && mouseY in lastY..(lastY + Panel.HEIGHT)
        if (headerHovered) {
            expanded = !expanded
            return true
        }

        if (expanded) {
            options.forEachIndexed { i, option ->
                val rowY = lastY + Panel.HEIGHT + i * optionHeight
                if (mouseX in lastX..(lastX + width) && mouseY in rowY..(rowY + optionHeight)) {
                    value = option
                    expanded = false
                    return true
                }
            }
        }

        return false
    }

    override fun read(element: JsonElement, gson: Gson) {
        val v = element.asString
        if (v in options) value = v
    }

    override fun write(gson: Gson): JsonElement = JsonPrimitive(value)
}