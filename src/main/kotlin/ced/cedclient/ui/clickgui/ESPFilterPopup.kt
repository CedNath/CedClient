package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.render.EntityESP
import ced.cedclient.ui.inventory.editor.Popup
import ced.cedclient.ui.inventory.editor.mouseClicked
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component

class ESPFilterPopup : Popup {

    var x: Int = 200
    var y: Int = 100
    val width: Int = 260
    val height: Int = 280

    private val mc = Minecraft.getInstance()
    private val font: Font = mc.font

    init {
        x = mc.window.guiScaledWidth - width - 20
        y = 60
    }

    private val nameField = EditBox(font, 0, 0, 180, 14, Component.empty())

    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    private val maxRowsPerList = 5

    override fun render(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        // background + header bar
        g.fill(x, y, x + width, y + height, 0xEE1A1A1A.toInt())
        g.fill(x, y, x + width, y + 16, 0xFF3A8FFF.toInt())
        g.text(font, "ESP Filter", x + 10, y + 6, 0xFFFFFFFF.toInt())

        // close button
        val closeHover = mouseX in (x + width - 16)..(x + width - 4) && mouseY in (y + 4)..(y + 14)
        g.text(font, "x", x + width - 12, y + 5, if (closeHover) 0xFFFF5555.toInt() else 0xFFCCCCCC.toInt())

        // input field
        g.text(font, "Entity or player name:", x + 10, y + 22, 0xFFAAAAAA.toInt())
        nameField.x = x + 10
        nameField.y = y + 34
        nameField.extractRenderState(g, mouseX, mouseY, delta)

        // Block / Only buttons
        drawButton(g, "Block", x + 10, y + 52, mouseX, mouseY)
        drawButton(g, "Only", x + 130, y + 52, mouseX, mouseY)

        var listY = y + 78
        g.text(font, "Blocked (${EntityESP.blockedNames.size}):", x + 10, listY, 0xFFFF8888.toInt())
        listY += 12

        val blockedList = EntityESP.blockedNames.toList()
        for (name in blockedList.take(maxRowsPerList)) {
            drawListRow(g, name, x + 10, listY, mouseX, mouseY) { EntityESP.unblockName(name) }
            listY += 12
        }
        if (blockedList.size > maxRowsPerList) {
            g.text(font, "+${blockedList.size - maxRowsPerList} more", x + 14, listY, 0xFF888888.toInt())
            listY += 12
        }

        listY += 8
        g.text(font, "Only Showing (${EntityESP.onlyNames.size}):", x + 10, listY, 0xFF88FFAA.toInt())
        listY += 12

        val onlyList = EntityESP.onlyNames.toList()
        for (name in onlyList.take(maxRowsPerList)) {
            drawListRow(g, name, x + 10, listY, mouseX, mouseY) { EntityESP.unOnlyName(name) }
            listY += 12
        }
        if (onlyList.size > maxRowsPerList) {
            g.text(font, "+${onlyList.size - maxRowsPerList} more", x + 14, listY, 0xFF888888.toInt())
        }
    }

    private fun drawListRow(
        g: GuiGraphicsExtractor,
        name: String,
        rowX: Int,
        rowY: Int,
        mouseX: Int,
        mouseY: Int,
        onRemove: () -> Unit
    ) {
        g.text(font, name, rowX + 4, rowY, 0xFFDDDDDD.toInt())

        val removeHover = mouseX in (x + width - 20)..(x + width - 8) && mouseY in rowY..(rowY + 10)
        g.text(font, "x", x + width - 18, rowY, if (removeHover) 0xFFFF5555.toInt() else 0xFF888888.toInt())
    }

    private fun drawButton(g: GuiGraphicsExtractor, text: String, bx: Int, by: Int, mx: Int, my: Int) {
        val bw = 100
        val bh = 16
        val hovered = mx in bx..(bx + bw) && my in by..(by + bh)
        val bg = if (hovered) 0xFF3A3A3A.toInt() else 0xFF2A2A2A.toInt()

        g.fill(bx, by, bx + bw, by + bh, bg)
        g.text(font, text, bx + 6, by + 4, 0xFFFFFFFF.toInt())
    }

    override fun mouseClicked(mx: Int, my: Int, button: Int): Boolean {
        if (!isInside(mx, my)) return false

        // drag on header
        if (my in y..(y + 16) && mx < x + width - 16) {
            dragging = true
            dragOffsetX = mx - x
            dragOffsetY = my - y
            return true
        }

        // close
        if (mx in (x + width - 16)..(x + width - 4) && my in (y + 4)..(y + 14)) {
            ced.cedclient.ui.clickgui.ClickGUIPopupHost.close()
            return true
        }

        if (nameField.mouseClicked(mx, my, button)) {
            nameField.setFocused(true)
            return true
        }

        // Block button
        if (mx in (x + 10)..(x + 110) && my in (y + 52)..(y + 68)) {
            val name = nameField.value.trim()
            if (name.isNotEmpty()) {
                EntityESP.blockName(name)
                nameField.value = ""
            }
            return true
        }

        // Only button
        if (mx in (x + 130)..(x + 230) && my in (y + 52)..(y + 68)) {
            val name = nameField.value.trim()
            if (name.isNotEmpty()) {
                EntityESP.onlyName(name)
                nameField.value = ""
            }
            return true
        }

        // remove clicks on blocked list rows
        var listY = y + 90
        val blockedList = EntityESP.blockedNames.toList()
        for (name in blockedList.take(maxRowsPerList)) {
            if (mx in (x + width - 20)..(x + width - 8) && my in listY..(listY + 10)) {
                EntityESP.unblockName(name)
                return true
            }
            listY += 12
        }
        if (blockedList.size > maxRowsPerList) listY += 12
        listY += 8 + 12

        val onlyList = EntityESP.onlyNames.toList()
        for (name in onlyList.take(maxRowsPerList)) {
            if (mx in (x + width - 20)..(x + width - 8) && my in listY..(listY + 10)) {
                EntityESP.unOnlyName(name)
                return true
            }
            listY += 12
        }

        return true
    }

    override fun mouseDragged(mx: Int, my: Int, button: Int, dx: Double, dy: Double): Boolean {
        if (dragging) {
            x = mx - dragOffsetX
            y = my - dragOffsetY
            return true
        }
        return false
    }

    override fun mouseReleased(mx: Int, my: Int, button: Int): Boolean {
        dragging = false
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (nameField.isFocused) return nameField.keyPressed(event)
        return false
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (nameField.isFocused) return nameField.charTyped(event)
        return false
    }

    override fun isInside(mx: Int, my: Int): Boolean {
        return mx in x..(x + width) && my in y..(y + height)
    }
}




