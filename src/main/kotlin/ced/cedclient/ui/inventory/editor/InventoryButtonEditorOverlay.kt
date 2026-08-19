package ced.cedclient.ui.inventory.editor

import ced.cedclient.features.impl.misc.InventoryButtons
import ced.cedclient.ui.inventory.InventoryButtonManager
import ced.cedclient.ui.inventory.InventoryButtonRenderer
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Draws the saved inventory buttons on top of the live InventoryScreen.
 *
 * This used to also own a whole popup-based editor (add/edit popups, a
 * top-left panel, drag handling, keyboard routing). All of that now lives in
 * InvButtonEditorScreen, a self-contained Screen opened directly via
 * InventoryButtons' "Open Editor" action -- so this object's only remaining
 * job is rendering the buttons wherever the live container currently sits,
 * and letting ContainerEventHandlerMixin know where that is so it can figure
 * out which button (if any) a click landed on.
 */
object InventoryButtonEditorOverlay {

    var lastLeft = 0
        private set
    var lastTop = 0
        private set

    fun setLastPos(left: Int, top: Int) {
        lastLeft = left
        lastTop = top
    }

    fun render(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (!InventoryButtons.enabled) return

        InventoryButtonRenderer.draw(g, InventoryButtonManager.buttons, lastLeft, lastTop, mouseX, mouseY)
    }
}