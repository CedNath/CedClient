package ced.cedclient.features.impl.funqol

import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.utils.Color.Companion.withAlpha
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.clickGuiScale
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.sign

/** /cc room time list: every room of the run with its secrets and the time of each step (see [RoomTimes]). */
class RoomTimesScreen : Screen(Component.literal("Room Times")) {
    private var scroll = 0f
    private var contentH = 0f
    private var viewH = 0f
    private val font get() = NVGRenderer.defaultFont

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        NVGSpecialRenderer.draw(context, 0, 0, context.guiWidth(), context.guiHeight(), renderContent = {
            NVGRenderer.scale(clickGuiScale, clickGuiScale)
            val cw = NVGRenderer.canvasWidth / clickGuiScale
            val ch = NVGRenderer.canvasHeight / clickGuiScale
            NVGRenderer.rect(0f, 0f, cw, ch, Colors.BLACK.withAlpha(0.35f).rgba)
            draw(cw, ch)
        })
        super.extractRenderState(context, mouseX, mouseY, deltaTicks)
    }

    private fun draw(cw: Float, ch: Float) {
        val w = 520f
        val h = minOf(480f, ch - 20f)
        val x = (cw - w) / 2f
        val y = (ch - h) / 2f
        NVGRenderer.dropShadow(x, y, w, h, 10f, 3f, 5f)
        NVGRenderer.rect(x, y, w, h, Colors.gray26.rgba, 5f)
        NVGRenderer.hollowRect(x, y, w, h, 1.5f, ClickGUI.clickGUIColor.rgba, 5f)
        val title = "Room Times"
        NVGRenderer.text(title, x + w / 2f - NVGRenderer.textWidth(title, 18f, font) / 2f, y + 10f, 18f, Colors.WHITE.rgba, font)

        val rooms = RoomTimes.rooms.values.toList()
        val top = y + 40f
        viewH = h - 52f
        scroll = scroll.coerceIn(-maxOf(0f, contentH - viewH), 0f)
        if (rooms.isEmpty()) {
            val msg = "No rooms timed yet - step into a room with secrets"
            NVGRenderer.text(msg, x + w / 2f - NVGRenderer.textWidth(msg, 12f, font) / 2f, top + 20f, 12f, 0xFFAAAAAA.toInt(), font)
            return
        }
        NVGRenderer.pushScissor(x, top, w, viewH)
        var cy = top + scroll
        val left = x + 16f
        val right = x + w - 16f
        for (room in rooms) {
            NVGRenderer.text(room.name, left, cy, 13f, if (room.done) 0xFF55FF55.toInt() else -1, font)
            val status = "${room.found.size}/${room.total}  " + if (room.shared) "shared" else (room.totalTime?.let { RoomTimes.fmt(it) } ?: "-")
            NVGRenderer.text(status, right - NVGRenderer.textWidth(status, 13f, font), cy, 13f, 0xFFFFFF55.toInt(), font)
            cy += 18f
            val chips = if (room.shared) emptyList() else room.splits.mapIndexed { i, s -> RoomTimes.splitLabel(i) to RoomTimes.fmt(s) } +
                (if (RoomTimes.showFirstToLast && room.found.size >= 2) listOf("1st -> last" to RoomTimes.fmt(room.found.last() - room.found.first())) else emptyList())
            var cx = left
            for ((label, time) in chips) {
                val cwid = NVGRenderer.textWidth("$label  $time", 10f, font) + 14f
                if (cx + cwid > right) { cx = left; cy += 14f }
                NVGRenderer.text(label, cx, cy, 10f, 0xFF999999.toInt(), font)
                NVGRenderer.text(time, cx + NVGRenderer.textWidth("$label  ", 10f, font), cy, 10f, 0xFFFFFFFF.toInt(), font)
                cx += cwid
            }
            if (chips.isEmpty()) NVGRenderer.text(if (room.shared) "a teammate was in this room - not timed" else "no secrets yet", left, cy, 10f, 0xFF777777.toInt(), font)
            cy += 22f
            NVGRenderer.line(left, cy - 8f, right, cy - 8f, 1f, Colors.gray38.rgba)
        }
        NVGRenderer.popScissor()
        contentH = cy - scroll - top
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        scroll += (verticalAmount.sign * 24).toFloat()
        return true
    }

    override fun isPauseScreen(): Boolean = false

    /** No vanilla blur behind this screen; the dim above is drawn instead (same as the other NanoVG screens). */
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {}
}
