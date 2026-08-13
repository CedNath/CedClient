package ced.cedclient.ui.inventory

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack

object InventoryButtonRenderer {

    fun draw(g: GuiGraphicsExtractor, btn: InvButton, left: Int, top: Int, mouseX: Int, mouseY: Int) {
        val font = Minecraft.getInstance().font

        val bx = left + btn.xOff
        val by = top + btn.yOff
        val w = btn.width
        val h = btn.height

        val hovered = mouseX in bx..(bx + w) && mouseY in by..(by + h)

        // ⭐ Use per-button colors
        val baseColor = if (hovered) btn.bgHoverColor else btn.bgColor
        val borderColor = btn.borderColor

        // Background
        g.fill(bx, by, bx + w, by + h, baseColor)

        // Border
        g.fill(bx, by, bx + w, by + 1, borderColor)
        g.fill(bx, by + h - 1, bx + w, by + h, borderColor)
        g.fill(bx, by, bx + 1, by + h, borderColor)
        g.fill(bx + w - 1, by, bx + w, by + h, borderColor)

        // ───────────────────────── ICON RENDERING ─────────────────────────
        if (btn.iconIsItem) {
            val item = BuiltInRegistries.ITEM.getValue(btn.icon)
            val stack = ItemStack(item)

            // No scaling possible in this pipeline
            g.item(stack, bx + 2, by + 2)

        } else if (btn.icon != null) {
            val iconX = bx + 2
            val iconY = by + 2
            val iconW = w - 4
            val iconH = h - 4

            g.blit(
                RenderPipelines.GUI_TEXTURED,
                btn.icon!!,
                iconX,
                iconY,
                0f,
                0f,
                iconW,
                iconH,
                iconW,
                iconH
            )
        }

        // ───────────────────────── TEXT RENDERING ─────────────────────────
        val showText =
            (!btn.hideBlankName || btn.name.isNotBlank()) &&
                    (!btn.showTextOnHover || hovered)

        if (showText && btn.name.isNotBlank()) {
            g.text(font, btn.name, bx + 4, by + 4, 0xFFFFFFFF.toInt())
        }
    }
}
