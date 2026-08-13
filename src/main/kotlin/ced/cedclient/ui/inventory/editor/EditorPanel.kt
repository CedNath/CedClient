package ced.cedclient.ui.inventory.editor

import ced.cedclient.features.impl.funqol.InventoryButtons
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

class EditorPanel {

    private val mc = Minecraft.getInstance()
    private val font = mc.font

    // Panel position (draggable)
    var x = 20
    var y = 20

    private val width = 160
    private val height = 80

    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    fun render(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        graphics.fill(x, y, x + width, y + height, 0xEE1A1A1A.toInt())
        graphics.fill(x, y, x + width, y + 4, 0xFF3A8FFF.toInt())

        graphics.text(font, "Inventory Editor", x + 8, y + 10, 0xFFFFFF)
        graphics.text(font, "[Add Button]", x + 8, y + 30, 0x55A8FF)

        val snapText = if (InventoryButtons.snapEnabled) "[Snap: ON]" else "[Snap: OFF]"
        graphics.text(font, snapText, x + 8, y + 48, 0x55A8FF)
    }

    fun mouseClicked(mx: Int, my: Int, button: Int): Boolean {
        // Drag panel by top bar
        if (mx in x..(x + width) && my in y..(y + 14)) {
            dragging = true
            dragOffsetX = mx - x
            dragOffsetY = my - y
            return true
        }


        if (mx in (x + 8)..(x + 140) && my in (y + 30)..(y + 42)) {
            InventoryButtonEditorOverlay.openAddPopup(mx, my)
            return true
        }



        // Snap toggle
        if (mx in (x + 8)..(x + 140) && my in (y + 48)..(y + 60)) {
            InventoryButtons.snapEnabled = !InventoryButtons.snapEnabled
            return true
        }

        return false
    }

    fun mouseDragged(mx: Int, my: Int): Boolean {
        if (dragging) {
            x = mx - dragOffsetX
            y = my - dragOffsetY
            return true
        }
        return false
    }

    fun mouseReleased() {
        dragging = false
    }
}
