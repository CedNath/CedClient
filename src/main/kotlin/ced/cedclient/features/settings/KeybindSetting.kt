package ced.cedclient.features.settings

import ced.cedclient.utils.Keys
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

/**
 * Stores a key as an SDL scancode (see [Keys]; -1 = unbound). Click to start listening
 * (reuses RenderableSetting's `listening` field), next keyPressed() captures and binds it,
 * Escape / Backspace / Delete clears it. Saved as {"sdl": code}; a bare number is a pre-26.3
 * GLFW key code and is converted on load.
 */
class KeybindSetting(
    name: String,
    override val default: Int = Keys.UNKNOWN,
    description: String = ""
) : RenderableSetting<Int>(name, description), Saving {

    override var value: Int = default

    private fun keyName(): String {
        if (value == Keys.UNKNOWN || value < 0) return "None"
        return Keys.name(value)
    }

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        val height = super.render(x, y, mouseX, mouseY)

        NVGRenderer.text(name, x + 6f, y + height / 2f - 6f, 12f, Colors.WHITE.rgba, NVGRenderer.defaultFont)

        val label = if (listening) "..." else keyName()
        val labelColor = if (listening) Colors.MINECRAFT_GOLD.rgba else Colors.MINECRAFT_GRAY.rgba
        NVGRenderer.text(
            label, x + width - 6f - NVGRenderer.textWidth(label, 12f, NVGRenderer.defaultFont),
            y + height / 2f - 6f, 12f, labelColor, NVGRenderer.defaultFont
        )

        return height
    }

    /** Left-click: listen for a key. Right-click: unbind. */
    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (!isHovered) return false
        if (click.button() == Keys.MOUSE_RIGHT) {
            value = Keys.UNKNOWN
            listening = false
        } else {
            listening = true
        }
        return true
    }

    /** While listening: Escape / Backspace / Delete unbind, any other key binds. */
    override fun keyPressed(input: KeyEvent): Boolean {
        if (!listening) return false

        val key = input.key()
        if (key == Keys.ESCAPE || key == Keys.BACKSPACE || key == Keys.DELETE) {
            value = Keys.UNKNOWN
        } else {
            value = key
        }
        listening = false
        return true
    }

    override fun keyTyped(input: CharacterEvent): Boolean = listening

    override fun read(element: JsonElement, gson: Gson) {
        value = if (element.isJsonObject) element.asJsonObject.get("sdl").asInt else Keys.fromGlfw(element.asInt)
    }

    override fun write(gson: Gson): JsonElement = JsonObject().also { it.addProperty("sdl", value) }
}