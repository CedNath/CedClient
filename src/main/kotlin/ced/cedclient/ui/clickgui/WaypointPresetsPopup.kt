package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.funqol.DungeonWaypoints
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import ced.cedclient.utils.Keys
import ced.cedclient.utils.ui.TextInputHandler
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

/**
 * Dungeon Waypoints > Presets: switch between whole waypoint sets. The list holds the default one and every code you saved
 * (yours, or one other people made: paste it, give it a name). Load replaces your waypoints with that set. Codes are kept as
 * files in config/cedclient/waypoint_presets, and CedClient's and Odin's codes are both accepted.
 */
class WaypointPresetsPopup : Popup("Waypoint Presets", 420f, 440f) {

    private var name = ""
    private val input = TextInputHandler(textProvider = { name }, textSetter = { name = it.take(32) })
    private var scroll = 0f
    private val counts = HashMap<String, Int>()

    init { input.focus() }

    private val rowH = 26f
    private val inputY get() = y + 40f
    private val buttonsY get() = y + 72f
    private val listTop get() = y + 108f
    private val listHeight get() = height - 118f

    private fun names() = DungeonWaypoints.presetNames()
    private fun countOf(preset: String) = counts.getOrPut(preset) { DungeonWaypoints.presetCount(preset) }

    private fun halfWidth() = (width - 36f) / 2f
    private fun addX() = x + 12f
    private fun saveX() = x + 24f + halfWidth()
    private fun loadX() = x + width - 12f - 22f - 6f - 56f
    private fun deleteX() = x + width - 12f - 22f

    private fun button(bx: Float, by: Float, bw: Float, bh: Float, label: String, hovered: Boolean, color: Int) {
        NVGRenderer.rect(bx, by, bw, bh, if (hovered) ClickGUI.hoverFill.rgba else Colors.gray38.rgba, 4f)
        NVGRenderer.hollowRect(bx, by, bw, bh, 1f, ced.cedclient.ui.Palette.EDGE, 4f)
        val font = NVGRenderer.defaultFont
        NVGRenderer.text(label, bx + bw / 2f - NVGRenderer.textWidth(label, 13f, font) / 2f, by + bh / 2f - 7f, 13f, Colors.WHITE.rgba, font)
    }

    override fun drawContent(mouseX: Float, mouseY: Float) {
        val font = NVGRenderer.defaultFont
        NVGRenderer.rect(x + 12f, inputY, width - 24f, 24f, Colors.INPUT_BG.rgba, 4f)
        NVGRenderer.hollowRect(x + 12f, inputY, width - 24f, 24f, 1f, Colors.INPUT_BORDER.rgba, 4f)
        if (name.isEmpty()) NVGRenderer.text("Name for a new preset (optional)", x + 18f, inputY + 5f, 14f, Colors.MINECRAFT_GRAY.rgba, font)
        input.x = x + 12f; input.y = inputY + 1f; input.width = width - 24f; input.height = 22f
        input.draw(mouseX, mouseY)

        val accent = ClickGUI.clickGUIColor.rgba
        button(addX(), buttonsY, halfWidth(), 24f, "Add From Clipboard", isAreaHovered(addX(), buttonsY, halfWidth(), 24f, mouseX, mouseY), accent)
        button(saveX(), buttonsY, halfWidth(), 24f, "Save My Waypoints", isAreaHovered(saveX(), buttonsY, halfWidth(), 24f, mouseX, mouseY), accent)

        val list = names()
        NVGRenderer.pushScissor(x, listTop, width, listHeight)
        list.forEachIndexed { i, preset ->
            val rowY = listTop + scroll + i * rowH
            if (rowY < listTop - rowH || rowY > listTop + listHeight) return@forEachIndexed
            if (isAreaHovered(x + 8f, rowY, width - 16f, rowH - 2f, mouseX, mouseY)) NVGRenderer.rect(x + 8f, rowY, width - 16f, rowH - 2f, Colors.gray38.rgba, 4f)
            NVGRenderer.text(preset, x + 16f, rowY + 5f, 15f, Colors.WHITE.rgba, font)
            val count = "${countOf(preset)} waypoints"
            NVGRenderer.text(count, loadX() - 12f - NVGRenderer.textWidth(count, 13f, font), rowY + 6f, 13f, Colors.MINECRAFT_GRAY.rgba, font)
            button(loadX(), rowY + 1f, 56f, rowH - 4f, "Load", isAreaHovered(loadX(), rowY + 1f, 56f, rowH - 4f, mouseX, mouseY), accent)
            if (preset != "Default") button(deleteX(), rowY + 1f, 22f, rowH - 4f, "x", isAreaHovered(deleteX(), rowY + 1f, 22f, rowH - 4f, mouseX, mouseY), Colors.MINECRAFT_RED.rgba)
        }
        NVGRenderer.popScissor()
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (input.mouseClicked(mouseX, mouseY, click)) return true
        if (Keys.mouse(click) != 0) return false
        if (isAreaHovered(addX(), buttonsY, halfWidth(), 24f, mouseX, mouseY)) {
            DungeonWaypoints.savePreset(name, DungeonWaypoints.clipboardText())?.let { counts.remove(it); name = "" }
            return true
        }
        if (isAreaHovered(saveX(), buttonsY, halfWidth(), 24f, mouseX, mouseY)) {
            DungeonWaypoints.savePreset(name, null)?.let { counts.remove(it); name = "" }
            return true
        }
        if (mouseY !in listTop..(listTop + listHeight)) return false
        val i = ((mouseY - listTop - scroll) / rowH).toInt()
        val preset = names().getOrNull(i) ?: return false
        val rowY = listTop + scroll + i * rowH
        if (isAreaHovered(loadX(), rowY + 1f, 56f, rowH - 4f, mouseX, mouseY)) DungeonWaypoints.applyPreset(preset)
        else if (preset != "Default" && isAreaHovered(deleteX(), rowY + 1f, 22f, rowH - 4f, mouseX, mouseY)) { DungeonWaypoints.deletePreset(preset); counts.remove(preset) }
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean = input.key != Keys.ESCAPE && this.input.keyPressed(input)
    override fun keyTyped(input: CharacterEvent): Boolean = this.input.keyTyped(input)

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Int): Boolean {
        scroll = (scroll + amount * rowH * 2).coerceIn(minOf(0f, listHeight - names().size * rowH), 0f)
        return true
    }
}
