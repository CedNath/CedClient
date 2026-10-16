package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.misc.DailyReset
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.TextInputHandler
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW

/**
 * Daily Tasks > "Custom Tasks": your own entries on the Daily Tasks HUD (same list as /cc daily add).
 * Type a name + Enter (or Add) to add one; click a row to tick it off for today, x to delete it.
 */
class CustomDailyPopup : Popup("Custom Daily Tasks", WIDTH, HEIGHT) {

    private var newTask = ""
    private val input = TextInputHandler(textProvider = { newTask }, textSetter = { newTask = it.take(64) })
    private var scroll = 0f

    init { input.focus() }

    private val inputY get() = y + 40f
    private val addW = 64f
    private val inputW get() = width - 24f - addW - 8f
    private val listTop get() = y + 76f
    private val listHeight get() = height - 86f
    private val rowH = 26f

    private fun submit() {
        if (newTask.isNotBlank() && DailyReset.add(newTask)) newTask = ""
    }

    override fun drawContent(mouseX: Float, mouseY: Float) {
        val font = NVGRenderer.defaultFont
        NVGRenderer.rect(x + 12f, inputY, inputW, 24f, Colors.INPUT_BG.rgba, 4f)
        NVGRenderer.hollowRect(x + 12f, inputY, inputW, 24f, 1f, Colors.INPUT_BORDER.rgba, 4f)
        if (newTask.isEmpty()) NVGRenderer.text("New task name...", x + 18f, inputY + 5f, 14f, Colors.MINECRAFT_GRAY.rgba, font)
        input.x = x + 12f; input.y = inputY + 1f; input.width = inputW; input.height = 22f
        input.draw(mouseX, mouseY)
        val addX = x + 12f + inputW + 8f
        val addHovered = isAreaHovered(addX, inputY, addW, 24f, mouseX, mouseY)
        NVGRenderer.rect(addX, inputY, addW, 24f, (if (addHovered) ClickGUI.clickGUIColor else Colors.gray38).rgba, 4f)
        NVGRenderer.text("Add", addX + addW / 2f - NVGRenderer.textWidth("Add", 15f, font) / 2f, inputY + 4f, 15f, Colors.WHITE.rgba, font)

        val tasks = DailyReset.manualTasks()
        if (tasks.isEmpty()) {
            NVGRenderer.text("No custom tasks yet.", x + 16f, listTop + 6f, 14f, Colors.MINECRAFT_GRAY.rgba, font)
            return
        }
        NVGRenderer.pushScissor(x, listTop, width, listHeight)
        tasks.forEachIndexed { i, (name, done) ->
            val rowY = listTop + scroll + i * rowH
            if (isAreaHovered(x + 8f, rowY, width - 16f, rowH - 2f, mouseX, mouseY)) NVGRenderer.rect(x + 8f, rowY, width - 16f, rowH - 2f, Colors.gray38.rgba, 4f)
            NVGRenderer.text(if (done) "[x]" else "[ ]", x + 14f, rowY + 5f, 15f, (if (done) Colors.MINECRAFT_GREEN else Colors.MINECRAFT_GRAY).rgba, font)
            NVGRenderer.text(name, x + 44f, rowY + 5f, 15f, (if (done) Colors.MINECRAFT_GRAY else Colors.WHITE).rgba, font)
            NVGRenderer.text("x", x + width - 28f, rowY + 4f, 15f, Colors.MINECRAFT_RED.rgba, font)
        }
        NVGRenderer.popScissor()
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (input.mouseClicked(mouseX, mouseY, click)) return true
        if (click.button() != 0) return false
        if (isAreaHovered(x + 12f + inputW + 8f, inputY, addW, 24f, mouseX, mouseY)) { submit(); return true }
        if (mouseY !in listTop..(listTop + listHeight)) return false
        val (name, done) = DailyReset.manualTasks().getOrNull(((mouseY - listTop - scroll) / rowH).toInt()) ?: return false
        if (mouseX > x + width - 40f) DailyReset.remove(name) else DailyReset.setCompleted(name, !done)
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (input.key == GLFW.GLFW_KEY_ESCAPE) return false
        if (input.key == GLFW.GLFW_KEY_ENTER) { submit(); return true }
        return this.input.keyPressed(input)
    }

    override fun keyTyped(input: CharacterEvent): Boolean = this.input.keyTyped(input)

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Int): Boolean {
        scroll = (scroll + amount * rowH * 2).coerceIn(minOf(0f, listHeight - DailyReset.manualTasks().size * rowH), 0f)
        return true
    }
}
