package ced.cedclient.features.settings

import ced.cedclient.ui.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW

/**
 * Not in the original roadmap — added as a stub. Stores a GLFW key code
 * (-1 = unbound). Click to start listening (reuses RenderableSetting's
 * `listening` field), next keyPressed() captures and binds it, Escape clears
 * it.
 *
 * NOTE: `input.key()` below assumes KeyEvent exposes its key code the same
 * way MouseButtonEvent exposes x()/y() elsewhere in this codebase. I don't
 * have the Minecraft/Fabric jars in this environment to confirm that against
 * the real 1.21.9+ KeyEvent API — check it compiles against the actual
 * mappings before relying on it.
 */
class KeybindSetting(
    name: String,
    override val default: Int = GLFW.GLFW_KEY_UNKNOWN,
    description: String = ""
) : RenderableSetting<Int>(name, description), Saving {

    override var value: Int = default

    private fun keyName(): String {
        if (value == GLFW.GLFW_KEY_UNKNOWN || value < 0) return "None"
        return GLFW.glfwGetKeyName(value, 0) ?: "Key $value"
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

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (!isHovered) return false
        listening = true
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (!listening) return false

        val key = input.key()
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            value = GLFW.GLFW_KEY_UNKNOWN
        } else {
            value = key
        }
        listening = false
        return true
    }

    override fun keyTyped(input: CharacterEvent): Boolean = listening

    override fun read(element: JsonElement, gson: Gson) {
        value = element.asInt
    }

    override fun write(gson: Gson): JsonElement = JsonPrimitive(value)
}