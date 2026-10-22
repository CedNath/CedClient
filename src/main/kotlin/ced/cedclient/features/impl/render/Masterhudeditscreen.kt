package ced.cedclient.features.impl.render

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt
import net.minecraft.network.chat.Component
import ced.cedclient.features.impl.render.ChatChannelHud
import ced.cedclient.features.impl.misc.DailyReset
import ced.cedclient.features.impl.funqol.DungeonSplits

/**
 * Opened by /cc hud or /cedclient hud (deliberately no keybind). Shows every
 * registered HudElement at once, each independently draggable/scalable --
 * whichever panel is under the mouse when you click/scroll is the one that
 * moves/resizes. Add a new HUD panel to `elements` below and it shows up
 * here automatically, no other changes needed.
 */
class MasterHudEditScreen : Screen(Component.literal("Edit HUD")) {

    private val elements: List<HudElement> = HudElement.all
    private var dragging: HudElement? = null
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    // A guide grid (button or G): lines every 10 px, stronger every 50, the screen centre marked, and dragging snaps to it.
    private val gridButtonX = 10
    private val gridButtonY = 24
    private val gridButtonW = 80
    private val gridButtonH = 14
    private fun snap(v: Int) = if (grid) (v / GRID.toFloat()).roundToInt() * GRID else v

    private fun drawGrid(context: GuiGraphicsExtractor) {
        for (x in 0..this.width step GRID) context.fill(x, 0, x + 1, this.height, if (x % 50 == 0) 0x44FFFFFF else 0x18FFFFFF)
        for (y in 0..this.height step GRID) context.fill(0, y, this.width, y + 1, if (y % 50 == 0) 0x44FFFFFF else 0x18FFFFFF)
        context.fill(this.width / 2, 0, this.width / 2 + 1, this.height, 0x8855FFFF.toInt())
        context.fill(0, this.height / 2, this.width, this.height / 2 + 1, 0x8855FFFF.toInt())
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)

        context.fill(0, 0, this.width, this.height, 0x88000000.toInt())
        if (grid) drawGrid(context)
        val overGrid = mouseX in gridButtonX..(gridButtonX + gridButtonW) && mouseY in gridButtonY..(gridButtonY + gridButtonH)
        context.fill(gridButtonX, gridButtonY, gridButtonX + gridButtonW, gridButtonY + gridButtonH, if (overGrid) 0xFF3A3A3A.toInt() else 0xFF2A2A2A.toInt())
        context.text(this.font, "Grid: " + (if (grid) "ON" else "OFF") + " (G)", gridButtonX + 5, gridButtonY + 3, if (grid) 0xFF55FF55.toInt() else 0xFFFFFFFF.toInt())
        context.text(
            this.font,
            "Drag any panel, scroll over it to resize, then press Escape to save and exit",
            10,
            10,
            0xFFFFFFFF.toInt()
        )

        for (element in elements) {
            val scaledWidth = (element.lastWidth * element.panelScale).toInt()
            val scaledHeight = (element.lastHeight * element.panelScale).toInt()

            context.outline(
                element.panelX - 2,
                element.panelY - 2,
                scaledWidth + 4,
                scaledHeight + 4,
                if (dragging === element) 0xFF55FF55.toInt() else 0xFFFFAA00.toInt()
            )
            context.text(
                this.font,
                element.label,
                element.panelX - 2,
                element.panelY - 12,
                0xFFFFAA00.toInt()
            )

            element.renderInternal(context)
        }
    }

    private fun elementAt(mx: Int, my: Int): HudElement? {
        // topmost-drawn (last in the list) wins if panels overlap
        return elements.lastOrNull { element ->
            val scaledWidth = (element.lastWidth * element.panelScale).toInt()
            val scaledHeight = (element.lastHeight * element.panelScale).toInt()
            mx in element.panelX..(element.panelX + scaledWidth) &&
                    my in element.panelY..(element.panelY + scaledHeight)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, focused: Boolean): Boolean {
        val mx = event.x().toInt()
        val my = event.y().toInt()

        if (mx in gridButtonX..(gridButtonX + gridButtonW) && my in gridButtonY..(gridButtonY + gridButtonH)) { grid = !grid; return true }

        val hit = elementAt(mx, my)
        if (hit != null) {
            dragging = hit
            dragOffsetX = mx - hit.panelX
            dragOffsetY = my - hit.panelY
            return true
        }

        return super.mouseClicked(event, focused)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val target = dragging
        if (target != null) {
            target.panelX = snap(event.x().toInt() - dragOffsetX)
            target.panelY = snap(event.y().toInt() - dragOffsetY)
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        // save as soon as a drag finishes, not just when the screen closes,
        // so a crash/force-quit before pressing Escape doesn't lose the edit
        dragging?.save()
        HudElement.layoutEdited()
        dragging = null
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        val hit = elementAt(x.toInt(), y.toInt())
        if (hit != null) {
            hit.adjustScale(scrollY.toFloat() * 0.1f)
            hit.save()
            HudElement.layoutEdited()
            return true
        }
        return super.mouseScrolled(x, y, scrollX, scrollY)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == GLFW.GLFW_KEY_G) { grid = !grid; return true }
        return super.keyPressed(event)
    }

    override fun onClose() {
        elements.forEach { it.save() }
        HudElement.layoutEdited()
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        private const val GRID = 10
        /** Kept for the session, so reopening the editor keeps your choice. */
        private var grid = false
    }
}