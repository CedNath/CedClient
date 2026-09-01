package ced.cedclient.features.settings

import ced.cedclient.ui.clickgui.Panel
import ced.cedclient.ui.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.TextInputHandler
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

/**
 * A single-line editable text field. Backed by TextInputHandler -- the same
 * caret/selection/undo field SearchBar uses (see utils/ui/TextInputHandler.kt)
 * -- rather than a bespoke input loop, so click-drag select, double/triple
 * click, and ctrl+C/V/X/A/Z/Y all come for free.
 *
 * Layout is name label on its own line, editable box below, so this row is
 * taller than a normal Panel.HEIGHT row (see getHeight()).
 */
class TextSetting(
    name: String,
    override val default: String,
    description: String = "",
    private val maxLength: Int = 32
) : RenderableSetting<String>(name, description), Saving {

    override var value: String = default
        set(newValue) {
            field = newValue.take(maxLength)
        }

    private val handler = TextInputHandler(
        textProvider = { value },
        textSetter = { value = it }
    )

    override fun getHeight(): Float = Panel.HEIGHT + 20f

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        val height = super.render(x, y, mouseX, mouseY)

        NVGRenderer.text(name, x + 6f, y + 4f, 12f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)

        val boxHeight = 16f
        val boxY = y + height - boxHeight - 4f
        val boxWidth = width - 12f
        NVGRenderer.rect(x + 6f, boxY, boxWidth, boxHeight, Colors.gray26.rgba, 3f)

        handler.x = x + 6f
        handler.y = boxY
        handler.width = boxWidth
        handler.height = boxHeight
        handler.draw(mouseX, mouseY)

        return height
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean =
        handler.mouseClicked(mouseX, mouseY, click)

    override fun mouseReleased(click: MouseButtonEvent) {
        handler.mouseReleased()
    }

    override fun keyPressed(input: KeyEvent): Boolean = handler.keyPressed(input)

    override fun keyTyped(input: CharacterEvent): Boolean = handler.keyTyped(input)

    override fun read(element: JsonElement, gson: Gson) {
        value = element.asString
    }

    override fun write(gson: Gson): JsonElement = JsonPrimitive(value)
}