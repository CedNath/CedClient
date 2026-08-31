package ced.cedclient.features.impl.render

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Opened by /cc hud or /cedclient hud (deliberately no keybind). Shows every
 * registered HudElement at once, each independently draggable/scalable --
 * whichever panel is under the mouse when you click/scroll is the one that
 * moves/resizes. Add a new HUD panel to `elements` below and it shows up
 * here automatically, no other changes needed.
 */
class MasterHudEditScreen : Screen(Component.literal("Edit HUD")) {

    private val elements: List<HudElement> = listOf(EntityESPHud, TimeHud)

    private var dragging: HudElement? = null
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)

        context.fill(0, 0, this.width, this.height, 0x88000000.toInt())
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
            target.panelX = event.x().toInt() - dragOffsetX
            target.panelY = event.y().toInt() - dragOffsetY
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        // save as soon as a drag finishes, not just when the screen closes,
        // so a crash/force-quit before pressing Escape doesn't lose the edit
        dragging?.save()
        dragging = null
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        val hit = elementAt(x.toInt(), y.toInt())
        if (hit != null) {
            hit.adjustScale(scrollY.toFloat() * 0.1f)
            hit.save()
            return true
        }
        return super.mouseScrolled(x, y, scrollX, scrollY)
    }

    override fun onClose() {
        elements.forEach { it.save() }
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false
}