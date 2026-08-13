package ced.cedclient.ui.inventory.editor

import ced.cedclient.features.impl.funqol.InventoryButtons
import ced.cedclient.features.impl.funqol.InventoryButtons.editorMode
import ced.cedclient.ui.inventory.InvButton
import ced.cedclient.ui.inventory.InventoryButtonManager
import ced.cedclient.ui.inventory.InventoryButtonRenderer
import ced.cedclient.utils.Debug
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import kotlin.math.roundToInt

object InventoryButtonEditorOverlay {

    var lastLeft = 0
        private set
    var lastTop = 0
        private set

    fun setLastPos(left: Int, top: Int) {
        lastLeft = left
        lastTop = top
    }

    var currentPopup: Popup? = null
        private set

    fun openPopup(p: Popup) {
        currentPopup = p
    }

    fun closePopup() {
        currentPopup = null
    }

    private var dragging: InvButton? = null
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    private const val PANEL_X = 10
    private const val PANEL_Y = 10
    private const val PANEL_WIDTH = 170
    private const val PANEL_HEIGHT = 80

    private const val GRID_SIZE = 8

    // ───────────────────────── RENDER ─────────────────────────
    fun render(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        //println("OVERLAY RENDER CALLED, editorMode=$editorMode, enabled=${InventoryButtons.enabled}")
        if (!InventoryButtons.enabled) return

        // Buttons (always visible when enabled)
        for (btn in InventoryButtonManager.buttons) {
            InventoryButtonRenderer.draw(
                g,
                btn,
                lastLeft,
                lastTop,
                mouseX,
                mouseY
            )
        }

        // Editor UI + popups only in editor mode
        if (InventoryButtons.editorMode) {
            //println("STEP 5: drawing top-left panel now")
            drawTopLeftPanel(g, mouseX, mouseY)
            currentPopup?.render(g, mouseX, mouseY, delta)
        }
    }

    // ───────────────────────── PANEL ─────────────────────────

    private fun drawTopLeftPanel(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val font: Font = Minecraft.getInstance().font

        val x0 = PANEL_X
        val y0 = PANEL_Y
        val x1 = x0 + PANEL_WIDTH
        val y1 = y0 + PANEL_HEIGHT

        g.fill(x0, y0, x1, y1, 0xCC101010.toInt())
        g.text(font, "Inventory Editor", x0 + 8, y0 + 6, 0xFFFFFFFF.toInt())

        val addX = x0 + 8
        val addY = y0 + 24
        val addHover = mouseX in addX..(addX + 90) && mouseY in addY..(addY + 12)
        val addColor = if (addHover) 0x88A8FFFF.toInt() else 0x55A8FFFF.toInt()
        g.text(font, "[Add Button]", addX, addY, addColor)

        val snapX = x0 + 8
        val snapY = y0 + 40
        val snapText = if (InventoryButtons.snapEnabled) "[Snap: ON]" else "[Snap: OFF]"
        val snapHover = mouseX in snapX..(snapX + 90) && mouseY in snapY..(snapY + 12)
        val snapColor = if (snapHover) 0x88A8FFFF.toInt() else 0x55A8FFFF.toInt()
        g.text(font, snapText, snapX, snapY, snapColor)
    }

    // ───────────────────────── INPUT: MOUSE ─────────────────────────

    fun mouseClicked(mx: Int, my: Int, button: Int): Boolean {
        val mc = Minecraft.getInstance()

        Debug.log("[CLICK] START mx=$mx my=$my button=$button dragging=$dragging screen=${mc.screen}")

        if (!InventoryButtons.enabled) {
            Debug.log("[CLICK] ABORT: InventoryButtons disabled")
            return false
        }

        Debug.log("[CLICK] lastLeft=$lastLeft lastTop=$lastTop")

        // POPUP FIRST
        currentPopup?.let { popup ->
            Debug.log("[CLICK] Popup active: $popup")

            val handled = popup.mouseClicked(mx, my, button)
            Debug.log("[CLICK] Popup mouseClicked handled=$handled")

            if (handled) return true

            if (popup.isInside(mx, my)) {
                Debug.log("[CLICK] Click inside popup but not handled — blocking")
                return true
            }

            Debug.log("[CLICK] Closing popup")
            currentPopup = null
            return true
        }

        // NORMAL BUTTON CLICKING (only in editor mode here; normal mode handled in mixin)
        val btn = InventoryButtonManager.getButtonAt(mx, my, lastLeft, lastTop)
        Debug.log("[CLICK] getButtonAt -> $btn")

        if (btn != null) {
            Debug.log("[CLICK] Clicked button: $btn")

            // RIGHT CLICK → open edit popup
            if (button == 1 && InventoryButtons.editorMode) {
                Debug.log("[CLICK] Right-click -> openEditPopup")
                openEditPopup(btn, mx, my)
                return true
            }

            // LEFT CLICK → drag (editor) or normal click (fallback)
            if (button == 0) {
                if (InventoryButtons.editorMode) {
                    Debug.log("[CLICK] LEFT CLICK -> START DRAG")
                    Debug.log("[CLICK] dragOffsetX=${mx - (lastLeft + btn.xOff)} dragOffsetY=${my - (lastTop + btn.yOff)}")

                    dragging = btn
                    dragOffsetX = mx - (lastLeft + btn.xOff)
                    dragOffsetY = my - (lastTop + btn.yOff)

                    Debug.log("[CLICK] dragging set to $dragging")
                } else {
                    Debug.log("[CLICK] LEFT CLICK -> normal onClick() (should be handled by mixin in normal mode)")
                    btn.onClick()
                }
                return true
            }
        }

        if (!InventoryButtons.editorMode) {
            Debug.log("[CLICK] Not editor mode -> returning false")
            return false
        }

        // Add button
        if (mx in (PANEL_X + 8)..(PANEL_X + 98) &&
            my in (PANEL_Y + 24)..(PANEL_Y + 36)
        ) {
            Debug.log("[CLICK] Add button area clicked -> openAddPopup")
            openAddPopup(mx, my)
            return true
        }

        // Snap toggle
        if (mx in (PANEL_X + 8)..(PANEL_X + 98) &&
            my in (PANEL_Y + 40)..(PANEL_Y + 52)
        ) {
            InventoryButtons.snapEnabled = !InventoryButtons.snapEnabled
            Debug.log("[CLICK] Snap toggled -> ${InventoryButtons.snapEnabled}")
            return true
        }

        Debug.log("[CLICK] Nothing handled -> return false")
        return false
    }

    fun mouseReleased(mx: Int, my: Int, button: Int): Boolean {
        if (!InventoryButtons.enabled) return false

        Debug.log("[RELEASE] mx=$mx my=$my button=$button draggingBefore=$dragging")

        // 1) POPUP RELEASE
        currentPopup?.let { popup ->
            if (popup.mouseReleased(mx, my, button)) {
                Debug.log("[RELEASE] handled by popup")
                return true
            }
        }

        // 2) BUTTON RELEASE
        dragging?.let { btn ->
            if (InventoryButtons.snapEnabled) {
                btn.xOff = snap(btn.xOff)
                btn.yOff = snap(btn.yOff)
            }

            InventoryButtonManager.save()
            dragging = null

            Debug.log("[RELEASE] draggingAfter=$dragging")
            return true
        }

        Debug.log("[RELEASE] nothing to release")
        return false
    }

    fun mouseDragged(mx: Int, my: Int, button: Int, dx: Double, dy: Double): Boolean {
        if (!InventoryButtons.enabled) return false

        // 1) POPUP DRAG
        currentPopup?.let { popup ->
            if (popup.mouseDragged(mx, my, button, dx, dy)) {
                Debug.log("[DRAG] handled by popup")
                return true
            }
        }

        // 2) BUTTON DRAG
        val btn = dragging ?: return false

        Debug.log("[DRAG] mx=$mx my=$my dx=$dx dy=$dy dragging=$dragging")

        if (dx == 0.0 && dy == 0.0) return false

        btn.xOff = mx - dragOffsetX - lastLeft
        btn.yOff = my - dragOffsetY - lastTop

        if (InventoryButtons.snapEnabled) {
            btn.xOff = snap(btn.xOff)
            btn.yOff = snap(btn.yOff)
        }

        InventoryButtonManager.save()
        return true
    }





    private fun snap(v: Int): Int =
        (v / GRID_SIZE.toFloat()).roundToInt() * GRID_SIZE

    // ───────────────────────── INPUT: KEYBOARD ─────────────────────────

    fun keyPressed(event: KeyEvent): Boolean {
        return currentPopup?.keyPressed(event) ?: false
    }

    fun charTyped(event: CharacterEvent): Boolean {
        return currentPopup?.charTyped(event) ?: false
    }

    // ───────────────────────── POPUP CONTROL ─────────────────────────

    fun openAddPopup(mx: Int, my: Int) {
        val popup = AddButtonPopup()
        popup.x = mx - popup.width / 2
        popup.y = my - 10
        openPopup(popup)
    }

    fun openEditPopup(button: InvButton, mx: Int, my: Int) {
        val popup = EditButtonPopup(button)
        popup.x = mx - popup.width / 2
        popup.y = my - 10
        openPopup(popup)
    }
}
