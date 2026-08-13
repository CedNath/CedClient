package ced.cedclient.ui.clickgui

import ced.cedclient.ui.nvg.NVGRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

object TooltipManager {

    private var text: String? = null
    private var anchorX = 0f
    private var anchorY = 0f

    fun clear() {
        text = null
    }

    fun request(newText: String, x: Float, y: Float) {
       println("Tooltip requested: $newText")

        text = newText
        anchorX = x
        anchorY = y
    }
    /**
     * Draw ONLY the NanoVG part of the tooltip.
     *
     * This is called while the NVG PIP is rendering.
     * Do not put GuiGraphicsExtractor text calls in here.
     */
    fun drawBackground() {
        val t = text ?: return

        val fontSize = 12f
        val padding = 6f
        val maxWidth = 220f
        val lineHeight = 1.15f

        val bounds = NVGRenderer.wrappedTextBounds(
            t,
            maxWidth,
            fontSize,
            NVGRenderer.defaultFont,
            lineHeight
        )

        val textW = bounds[2]
        val textH = bounds[3]

        val boxW = textW + padding * 2f
        val boxH = textH + padding * 2f

        val mc = Minecraft.getInstance()
        val fbWidth = mc.window.width.toFloat()
        val fbHeight = mc.window.height.toFloat()

        var boxX = anchorX
        var boxY = anchorY

        if (boxX + boxW > fbWidth) {
            boxX = fbWidth - boxW - 4f
        }

        if (boxY + boxH > fbHeight) {
            boxY = fbHeight - boxH - 4f
        }

        if (boxX < 0f) boxX = 4f
        if (boxY < 0f) boxY = 4f

        NVGRenderer.rect(
            boxX - 1f,
            boxY - 1f,
            boxW + 2f,
            boxH + 2f,
            0xFF3A8FFF.toInt(),
            5f
        )

        NVGRenderer.rect(
            boxX,
            boxY,
            boxW,
            boxH,
            0xFF101010.toInt(),
            4f
        )
    }

    /**
     * Draw ONLY the Minecraft text portion of the tooltip.
     *
     * This is called during ClickGUI.extractRenderState(), AFTER the
     * NanoVG PIP has been queued, so the text is rendered normally.
     */
    fun drawText(g: GuiGraphicsExtractor) {
       println("drawText called, text = $text")
        val t = text ?: return
       println("Drawing tooltip: $t")

        val fontSize = 12f
        val padding = 6f
        val maxWidth = 220f
        val lineHeight = 1.15f

        val bounds = NVGRenderer.wrappedTextBounds(
            t,
            maxWidth,
            fontSize,
            NVGRenderer.defaultFont,
            lineHeight
        )

        val textW = bounds[2]
        val textH = bounds[3]

        val boxW = textW + padding * 2f
        val boxH = textH + padding * 2f

        val mc = Minecraft.getInstance()
        val fbWidth = mc.window.width.toFloat()
        val fbHeight = mc.window.height.toFloat()

        var boxX = anchorX
        var boxY = anchorY

        if (boxX + boxW > fbWidth) {
            boxX = fbWidth - boxW - 4f
        }

        if (boxY + boxH > fbHeight) {
            boxY = fbHeight - boxH - 4f
        }

        if (boxX < 0f) boxX = 4f
        if (boxY < 0f) boxY = 4f

        NVGRenderer.drawWrappedString(
            g,
            t,
            boxX + padding,
            boxY + padding,
            maxWidth,
            fontSize,
            0xFFFFFFFF.toInt(),
            NVGRenderer.defaultFont,
            lineHeight
        )
    }

}