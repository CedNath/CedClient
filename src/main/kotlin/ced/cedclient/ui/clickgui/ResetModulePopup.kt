package ced.cedclient.ui.clickgui

import ced.cedclient.config.ConfigManager
import ced.cedclient.features.Module
import ced.cedclient.features.ModuleManager
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.TextInputHandler
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW

/**
 * Client Settings > "Reset a Module": every module with settings, grouped by panel. Clicking one arms it
 * ("click again to reset"), a second click within 3 s puts all of its settings (popup ones too) back to default.
 */
class ResetModulePopup : Popup("Reset a Module", WIDTH, HEIGHT) {

    private val allModules = ModuleManager.modules.filter { m -> m.settings.values.any { it !is ActionSetting } }
        .sortedWith(compareBy<Module> { it.category.ordinal }.thenBy { it.name })
    private var search = ""
    private val input = TextInputHandler(textProvider = { search }, textSetter = { search = it.take(40); scroll = 0f })
    private val modules get() = allModules.filter { it.name.contains(search, ignoreCase = true) }

    init { input.focus() }
    private var scroll = 0f
    private var armed: Module? = null
    private var armedAt = 0L

    private val inputY get() = y + 40f
    private val listTop get() = y + 76f
    private val listHeight get() = height - 86f
    private val rowH = 26f

    private fun isArmed(m: Module) = armed == m && System.currentTimeMillis() - armedAt < 3000L

    override fun drawContent(mouseX: Float, mouseY: Float) {
        val font = NVGRenderer.defaultFont
        NVGRenderer.rect(x + 12f, inputY, width - 24f, 24f, Colors.INPUT_BG.rgba, 4f)
        NVGRenderer.hollowRect(x + 12f, inputY, width - 24f, 24f, 1f, Colors.INPUT_BORDER.rgba, 4f)
        if (search.isEmpty()) NVGRenderer.text("Search modules...", x + 18f, inputY + 5f, 14f, Colors.MINECRAFT_GRAY.rgba, font)
        input.x = x + 12f; input.y = inputY + 1f; input.width = width - 24f; input.height = 22f
        input.draw(mouseX, mouseY)
        val modules = modules
        NVGRenderer.pushScissor(x, listTop, width, listHeight)
        modules.forEachIndexed { i, m ->
            val rowY = listTop + scroll + i * rowH
            if (rowY < listTop - rowH || rowY > listTop + listHeight) return@forEachIndexed
            val hovered = isAreaHovered(x + 8f, rowY, width - 16f, rowH - 2f, mouseX, mouseY)
            if (hovered) NVGRenderer.rect(x + 8f, rowY, width - 16f, rowH - 2f, Colors.gray38.rgba, 4f)
            val (text, color) = if (isArmed(m)) "Click again to reset ${m.name}" to Colors.MINECRAFT_RED.rgba
                else m.name to Colors.WHITE.rgba
            NVGRenderer.text(text, x + 16f, rowY + 5f, 15f, color, font)
            val panel = m.category.displayName
            NVGRenderer.text(panel, x + width - 16f - NVGRenderer.textWidth(panel, 13f, font), rowY + 6f, 13f, Colors.MINECRAFT_GRAY.rgba, font)
        }
        NVGRenderer.popScissor()
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (input.mouseClicked(mouseX, mouseY, click)) return true
        if (click.button() != 0 || mouseY !in listTop..(listTop + listHeight)) return false
        val m = modules.getOrNull(((mouseY - listTop - scroll) / rowH).toInt()) ?: return false
        if (!isArmed(m)) {
            armed = m
            armedAt = System.currentTimeMillis()
            return true
        }
        armed = null
        m.settings.values.forEach { it.reset() }
        ConfigManager.saveModulesOnly()
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean = input.key != GLFW.GLFW_KEY_ESCAPE && this.input.keyPressed(input)
    override fun keyTyped(input: CharacterEvent): Boolean = this.input.keyTyped(input)

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Int): Boolean {
        scroll = (scroll + amount * rowH * 2).coerceIn(minOf(0f, listHeight - modules.size * rowH), 0f)
        return true
    }
}
