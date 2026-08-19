package ced.cedclient.ui.inventory

import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.nvg.NVGRenderer
import ced.cedclient.ui.nvg.NVGSpecialRenderer
import ced.cedclient.utils.Color
import ced.cedclient.utils.Colors
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack

/**
 * Draws saved inventory buttons — both the live ones overlaid on the real
 * InventoryScreen (InventoryButtonEditorOverlay) and the draggable ones shown
 * inside InvButtonEditorScreen. Used to be flat `g.fill()` rectangles with a
 * 1px vanilla border, which looked out of place next to the rest of the GUI's
 * NanoVG-rendered, rounded, custom-font look — this now goes through
 * NVGRenderer the same way ClickGUI/Panel/ModuleButton do.
 *
 * [draw] takes the WHOLE button list rather than one button at a time. That's
 * required for correct layering, not just convenience: item icons can only be
 * drawn through the vanilla GuiGraphicsExtractor pipeline (NanoVG has no
 * concept of a Minecraft ItemStack), so backgrounds/borders/labels (NVG) and
 * icons (vanilla) have to be interleaved in three passes rather than drawn
 * per-button top-to-bottom like before:
 *
 *   1. NVG     — rounded background + border for every button
 *   2. vanilla — item/texture icons, on top of those backgrounds
 *   3. NVG     — name labels, on top of the icons
 *
 * Each NVGSpecialRenderer.draw() call just appends a picture-in-picture
 * render state to the context in call order (same trick ClickGUI uses,
 * registering its NVG batch before `super.extractRenderState()` draws
 * vanilla widgets on top) — so this three-pass split is what keeps icons
 * above backgrounds and labels above icons without any manual z-ordering.
 */
object InventoryButtonRenderer {

    fun draw(
        g: GuiGraphicsExtractor,
        buttons: List<InvButton>,
        left: Int,
        top: Int,
        mouseX: Int,
        mouseY: Int,
        selected: InvButton? = null
    ) {
        if (buttons.isEmpty()) return

        // Same GUI-space vs NVG-canvas-space mismatch fixed in InvButtonEditorScreen:
        // left/top/btn.xOff/btn.yOff/mouseX/mouseY here are all GuiGraphicsExtractor
        // guiWidth()/guiHeight() space, but NVG draws in canvasWidth/canvasHeight
        // space. Without this scale, the background/border/label (NVG) land at a
        // different spot than the icon (drawn vanilla, so it's unaffected) whenever
        // GUI Scale isn't 1 -- the "button ghosts to another place" symptom.
        NVGSpecialRenderer.draw(g, 0, 0, g.guiWidth(), g.guiHeight(), renderContent = {
            val guiScaleRatio = NVGRenderer.canvasWidth / g.guiWidth()
            NVGRenderer.scale(guiScaleRatio, guiScaleRatio)
            for (btn in buttons) drawBackground(btn, left, top, mouseX, mouseY, btn === selected)
        })

        for (btn in buttons) drawIcon(g, btn, left, top)

        NVGSpecialRenderer.draw(g, 0, 0, g.guiWidth(), g.guiHeight(), renderContent = {
            val guiScaleRatio = NVGRenderer.canvasWidth / g.guiWidth()
            NVGRenderer.scale(guiScaleRatio, guiScaleRatio)
            for (btn in buttons) drawLabel(btn, left, top, mouseX, mouseY)
        })
    }

    private fun isHovered(btn: InvButton, left: Int, top: Int, mouseX: Int, mouseY: Int): Boolean {
        val bx = left + btn.xOff
        val by = top + btn.yOff
        return mouseX in bx..(bx + btn.width) && mouseY in by..(by + btn.height)
    }

    private fun drawBackground(btn: InvButton, left: Int, top: Int, mouseX: Int, mouseY: Int, isSelected: Boolean) {
        val bx = (left + btn.xOff).toFloat()
        val by = (top + btn.yOff).toFloat()
        val w = btn.width.toFloat()
        val h = btn.height.toFloat()

        val hovered = isHovered(btn, left, top, mouseX, mouseY)
        val bg = Color(if (hovered) btn.bgHoverColor else btn.bgColor)

        NVGRenderer.rect(bx, by, w, h, bg.rgba, 3f)

        // Selected buttons (in the editor) get the same purple accent as the
        // rest of the GUI instead of a hard white line; unselected buttons
        // keep their own borderColor, just rounded and softer than 1px flat.
        val borderColor = if (isSelected) ClickGUI.clickGUIColor.rgba else Color(btn.borderColor).rgba
        NVGRenderer.hollowRect(bx, by, w, h, if (isSelected) 1.5f else 1f, borderColor, 3f)
    }

    private fun drawIcon(g: GuiGraphicsExtractor, btn: InvButton, left: Int, top: Int) {
        val bx = left + btn.xOff
        val by = top + btn.yOff

        if (btn.iconIsItem) {
            val iconId = btn.icon ?: return
            val item = BuiltInRegistries.ITEM.getValue(iconId)
            g.item(ItemStack(item), bx + 2, by + 2)
        } else if (btn.icon != null) {
            val iconW = btn.width - 4
            val iconH = btn.height - 4
            g.blit(
                RenderPipelines.GUI_TEXTURED,
                btn.icon!!,
                bx + 2,
                by + 2,
                0f,
                0f,
                iconW,
                iconH,
                iconW,
                iconH
            )
        }
    }

    private fun drawLabel(btn: InvButton, left: Int, top: Int, mouseX: Int, mouseY: Int) {
        if (btn.name.isBlank()) return

        val hovered = isHovered(btn, left, top, mouseX, mouseY)
        val showText = (!btn.hideBlankName || btn.name.isNotBlank()) && (!btn.showTextOnHover || hovered)
        if (!showText) return

        val bx = (left + btn.xOff).toFloat()
        val by = (top + btn.yOff).toFloat()

        // 8f roughly matches vanilla's font size at 1:1 GUI scale; the custom
        // font's metrics may read a little larger/smaller in-game than the
        // vanilla font did, so this is worth a visual once-over and a tweak
        // to taste if it looks off.
        NVGRenderer.text(btn.name, bx + 4f, by + 4f, 8f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
    }
}