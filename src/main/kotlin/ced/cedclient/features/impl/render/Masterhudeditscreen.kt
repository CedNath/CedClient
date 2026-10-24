package ced.cedclient.features.impl.render

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.CharacterEvent
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
 * here automatically, no other changes needed. The left side list picks which
 * ones are shown/movable here (all at first), so a crowded screen can be cut down.
 */
class MasterHudEditScreen : Screen(Component.literal("Edit HUD")) {

    private val elements: List<HudElement> = HudElement.all
    private var dragging: HudElement? = null
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    private lateinit var search: EditBox
    private var listScroll = 0
    private val picked: MutableSet<String> = pickedLabels ?: elements.map { it.label }.toMutableSet().also { pickedLabels = it }
    private val shown get() = elements.filter { it.label in picked }
    private fun visibleRows() = elements.filter { search.value.isBlank() || it.label.contains(search.value.trim(), ignoreCase = true) }
    private fun maxScroll() = maxOf(0, visibleRows().size * ROW_H - (this.height - LIST_Y - 4))

    override fun init() {
        search = EditBox(this.font, 6, 16, PANEL_W - 12, 14, Component.literal("Search HUDs..."))
        search.setHint(Component.literal("Search HUDs..."))
        search.setResponder { listScroll = 0 }
        addRenderableWidget(search)
    }

    // A guide grid (button or G): lines every 10 px, stronger every 50, the screen centre marked, and dragging snaps to it.
    private val gridButtonX = PANEL_W + 10
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
            PANEL_W + 10,
            10,
            0xFFFFFFFF.toInt()
        )

        for (element in shown) {
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
        drawList(context, mouseX, mouseY)
    }

    private fun drawList(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        context.fill(0, 0, PANEL_W, this.height, 0xEE101418.toInt())
        context.fill(PANEL_W - 1, 0, PANEL_W, this.height, 0xFF2A9DA8.toInt())
        context.text(this.font, "Pick HUDs to move", 6, 4, 0xFFFFFFFF.toInt())
        search.extractRenderState(context, mouseX, mouseY, 0f)
        for ((i, b) in listOf("All" to 0, "None" to 1).withIndex()) {
            val x = 6 + i * (PANEL_W / 2 - 3)
            context.fill(x, 34, x + PANEL_W / 2 - 9, 46, if (mouseX in x..(x + PANEL_W / 2 - 9) && mouseY in 34..46) 0xFF3A3A3A.toInt() else 0xFF2A2A2A.toInt())
            context.text(this.font, b.first, x + 4, 36, 0xFFFFFFFF.toInt())
        }
        context.text(this.font, "${picked.size}/${elements.size} picked", 6, 48,0xFF888888.toInt())
        listScroll = listScroll.coerceIn(0, maxScroll())
        context.enableScissor(0, LIST_Y, PANEL_W - 1, this.height)
        visibleRows().forEachIndexed { i, e ->
            val y = LIST_Y + i * ROW_H - listScroll
            val on = e.label in picked
            val hover = mouseX < PANEL_W && mouseY in y..(y + ROW_H - 1) && mouseY >= LIST_Y
            if (hover) context.fill(0, y, PANEL_W - 1, y + ROW_H, 0x33FFFFFF)
            if (on) context.fill(0, y, 2, y + ROW_H, 0xFF2A9DA8.toInt())
            context.text(this.font, e.label, 8, y + 2, if (on) 0xFFFFFFFF.toInt() else 0xFF777777.toInt())
        }
        context.disableScissor()
    }

    private fun elementAt(mx: Int, my: Int): HudElement? {
        // topmost-drawn (last in the list) wins if panels overlap
        return shown.lastOrNull { element ->
            val scaledWidth = (element.lastWidth * element.panelScale).toInt()
            val scaledHeight = (element.lastHeight * element.panelScale).toInt()
            mx in element.panelX..(element.panelX + scaledWidth) &&
                    my in element.panelY..(element.panelY + scaledHeight)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, focused: Boolean): Boolean {
        val mx = event.x().toInt()
        val my = event.y().toInt()

        if (mx < PANEL_W) {
            search.mouseClicked(event, focused)
            if (my in 34..46) {
                if (mx < PANEL_W / 2) picked.addAll(elements.map { it.label }) else picked.clear()
            } else if (my >= LIST_Y) {
                visibleRows().getOrNull((my - LIST_Y + listScroll) / ROW_H)?.label?.let { if (!picked.remove(it)) picked.add(it) }
            }
            return true
        }
        search.setFocused(false)

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
        if (x < PANEL_W) {
            listScroll = (listScroll - (scrollY * ROW_H * 2).toInt()).coerceIn(0, maxScroll())
            return true
        }
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
        if (search.isFocused && event.key() != GLFW.GLFW_KEY_ESCAPE) { search.keyPressed(event); return true }
        if (event.key() == GLFW.GLFW_KEY_G) { grid = !grid; return true }
        return super.keyPressed(event)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean =
        (search.isFocused && search.charTyped(characterEvent)) || super.charTyped(characterEvent)

    override fun onClose() {
        elements.forEach { it.save() }
        HudElement.layoutEdited()
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        private const val GRID = 10
        private const val PANEL_W = 130
        private const val ROW_H = 12
        private const val LIST_Y = 60
        private var pickedLabels: MutableSet<String>? = null
        /** Kept for the session, so reopening the editor keeps your choice. */
        private var grid = false
    }
}