package ced.cedclient.features.impl.render

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

class HudEditScreen : Screen(Component.literal("Edit HUD")) {

    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)

        context.fill(0, 0, this.width, this.height, 0x88000000.toInt())
        context.text(
            this.font,
            "Drag the panel below, scroll to resize, then press Escape to save and exit",
            10,
            10,
            0xFFFFFFFF.toInt()
        )

        val scaledWidth = (EntityESPHud.lastWidth * EntityESPHud.panelScale).toInt()
        val scaledHeight = (EntityESPHud.lastHeight * EntityESPHud.panelScale).toInt()

        context.outline(
            EntityESPHud.panelX - 2,
            EntityESPHud.panelY - 2,
            scaledWidth + 4,
            scaledHeight + 4,
            if (dragging) 0xFF55FF55.toInt() else 0xFFFFAA00.toInt()
        )

        EntityESPHud.renderInternal(context)
    }

    private fun isOverPanel(mx: Int, my: Int): Boolean {
        val scaledWidth = (EntityESPHud.lastWidth * EntityESPHud.panelScale).toInt()
        val scaledHeight = (EntityESPHud.lastHeight * EntityESPHud.panelScale).toInt()
        return mx in EntityESPHud.panelX..(EntityESPHud.panelX + scaledWidth) &&
                my in EntityESPHud.panelY..(EntityESPHud.panelY + scaledHeight)
    }

    override fun mouseClicked(event: MouseButtonEvent, focused: Boolean): Boolean {
        val mx = event.x().toInt()
        val my = event.y().toInt()

        if (isOverPanel(mx, my)) {
            dragging = true
            dragOffsetX = mx - EntityESPHud.panelX
            dragOffsetY = my - EntityESPHud.panelY
            return true
        }

        return super.mouseClicked(event, focused)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (dragging) {
            EntityESPHud.panelX = event.x().toInt() - dragOffsetX
            EntityESPHud.panelY = event.y().toInt() - dragOffsetY
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (dragging) {
            // save as soon as a drag finishes, not just when the screen closes,
            // so a crash/force-quit before pressing Escape doesn't lose the edit
            EntityESPHud.save()
        }
        dragging = false
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        if (isOverPanel(x.toInt(), y.toInt())) {
            EntityESPHud.adjustScale(scrollY.toFloat() * 0.1f)
            EntityESPHud.save()
            return true
        }
        return super.mouseScrolled(x, y, scrollX, scrollY)
    }

    override fun onClose() {
        EntityESPHud.save()
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false
}