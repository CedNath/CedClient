package ced.cedclient.features.impl.render

import ced.cedclient.utils.Keys
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import kotlin.math.roundToInt
import net.minecraft.network.chat.Component

/**
 * Opened by /cc hud or /cedclient hud (deliberately no keybind). Shows every registered HudElement at once, each
 * independently draggable/scalable. Add a new HUD panel to HudElement.all and it shows up here automatically.
 *
 * Kept quiet on purpose: the only chrome is a small pill at the top (HUD list, grid, hint). Panels are thin outlines;
 * the one you click gets a bright outline and a toolbar (smaller, bigger, reset, card style) right above it. The HUD
 * list (which panels are shown here) is a popover under the pill, not a permanent sidebar.
 */
class MasterHudEditScreen : Screen(Component.literal("Edit HUD")) {

    // Panels of disabled modules stay out of the editor (an element that is not a module has no switch, so it stays).
    private val elements: List<HudElement> = HudElement.all.filter { it.hudActive && (it as? ced.cedclient.features.Module)?.isEnabled != false }
    private var dragging: HudElement? = null
    private var selected: HudElement? = null
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    private lateinit var search: EditBox
    private var open = false
    private var listScroll = 0
    private val picked: MutableSet<String> = pickedLabels ?: elements.map { it.label }.toMutableSet().also { pickedLabels = it }
    private val shown get() = elements.filter { it.label in picked }
    private fun visibleRows() = elements.filter { search.value.isBlank() || it.label.contains(search.value.trim(), ignoreCase = true) }
    private fun maxScroll() = maxOf(0, visibleRows().size - MAX_ROWS) * ROW_H

    /** Clickable rectangles of the last frame (pill, popover buttons, toolbar), topmost last. */
    private class Btn(val x: Int, val y: Int, val w: Int, val h: Int, val action: () -> Unit)
    private val btns = ArrayList<Btn>()
    private var popX = 0
    private var popY = 0
    private var popW = 0
    private var popH = 0

    override fun init() {
        search = EditBox(this.font, 0, 0, POP_W - 8, 14, Component.literal("Search HUDs..."))
        search.setHint(Component.literal("Search HUDs..."))
        search.setResponder { listScroll = 0 }
    }

    private fun snap(v: Int) = if (grid) (v / GRID.toFloat()).roundToInt() * GRID else v

    private fun drawGrid(context: GuiGraphicsExtractor) {
        for (x in 0..this.width step GRID) context.fill(x, 0, x + 1, this.height, if (x % 50 == 0) 0x44FFFFFF else 0x18FFFFFF)
        for (y in 0..this.height step GRID) context.fill(0, y, this.width, y + 1, if (y % 50 == 0) 0x44FFFFFF else 0x18FFFFFF)
        context.fill(this.width / 2, 0, this.width / 2 + 1, this.height, 0x8855FFFF.toInt())
        context.fill(0, this.height / 2, this.width, this.height / 2 + 1, 0x8855FFFF.toInt())
    }

    private fun button(context: GuiGraphicsExtractor, x: Int, y: Int, text: String, mx: Int, my: Int, on: Boolean = false, action: () -> Unit): Int {
        val w = this.font.width(text) + 8
        val hover = mx in x..(x + w) && my in y..(y + BTN_H)
        context.fill(x, y, x + w, y + BTN_H, if (hover) 0xFF3C3C4C.toInt() else 0xFF26262F.toInt())
        context.text(this.font, text, x + 4, y + 3, if (on) 0xFF6AA8FF.toInt() else 0xFFFFFFFF.toInt())
        btns += Btn(x, y, w, BTN_H, action)
        return w
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)
        btns.clear()

        context.fill(0, 0, this.width, this.height, 0x88000000.toInt())
        if (grid) drawGrid(context)

        val hovered = if (open) null else elementAt(mouseX, mouseY)
        val sel = selected?.takeIf { it in shown }
        // The selected panel is drawn last (on top); the rest are dimmed while one is selected.
        for (element in shown.sortedBy { it === sel }) {
            val sw = (element.lastWidth * element.panelScale).toInt()
            val sh = (element.lastHeight * element.panelScale).toInt()
            val color = when {
                element === selected -> 0xFF6AA8FF.toInt()
                element === hovered -> 0xCCFFFFFF.toInt()
                else -> 0x55FFFFFF
            }
            element.renderInternal(context)
            if (sel != null && element !== sel) context.fill(element.panelX - 2, element.panelY - 2, element.panelX + sw + 2, element.panelY + sh + 2, 0xB0000000.toInt())
            context.outline(element.panelX - 2, element.panelY - 2, sw + 4, sh + 4, color)
            if (element === hovered && element !== selected) {
                context.text(this.font, element.label, element.panelX - 2, element.panelY - 12, 0xFFFFFFFF.toInt())
            }
        }
        selected?.takeIf { it in shown }?.let { drawToolbar(context, it, mouseX, mouseY) }
        drawPill(context, mouseX, mouseY)
        if (open) drawPopover(context, mouseX, mouseY)
    }

    private fun drawToolbar(context: GuiGraphicsExtractor, e: HudElement, mx: Int, my: Int) {
        val sh = (e.lastHeight * e.panelScale).toInt()
        val y = if (e.panelY - 2 - BTN_H - 4 >= 22) e.panelY - 2 - BTN_H - 4 else e.panelY + sh + 6
        var x = e.panelX - 2
        val style = HudStyle.of(e.label)
        context.text(this.font, e.label, x + 2, y + 3, 0xFFFFFFFF.toInt())
        x += this.font.width(e.label) + 10
        x += button(context, x, y, "-", mx, my) { e.adjustScale(-0.1f); e.save(); HudElement.layoutEdited() } + 2
        x += button(context, x, y, "+", mx, my) { e.adjustScale(0.1f); e.save(); HudElement.layoutEdited() } + 2
        x += button(context, x, y, "Reset", mx, my) { e.resetPosition(); e.save(); HudElement.layoutEdited() } + 2
        button(context, x, y, "Style: " + if (HudStyle.hasOverride(e.label)) style else "Auto ($style)", mx, my, HudStyle.hasOverride(e.label)) { HudStyle.cycle(e.label) }
    }

    private fun drawPill(context: GuiGraphicsExtractor, mx: Int, my: Int) {
        val listText = "HUDs ${picked.size}/${elements.size} " + if (open) "^" else "v"
        val gridText = "Grid: " + (if (grid) "ON" else "OFF") + " (G)"
        val hint = "Drag, scroll to resize, right-click picks the one underneath, Esc saves"
        val total = this.font.width(listText) + this.font.width(gridText) + this.font.width(hint) + 8 * 2 + 6 * 2 + 16
        val x0 = (this.width - total) / 2
        context.fill(x0, 4, x0 + total, 4 + BTN_H + 4, 0xEE101418.toInt())
        context.outline(x0, 4, total, BTN_H + 4, 0xFF3A3A4A.toInt())
        var x = x0 + 4
        popX = x
        x += button(context, x, 6, listText, mx, my, open) { open = !open; if (open) search.setFocused(false) } + 6
        x += button(context, x, 6, gridText, mx, my, grid) { grid = !grid } + 6
        context.text(this.font, hint, x + 4, 9, 0xFF8B8B9C.toInt())
    }

    private fun drawPopover(context: GuiGraphicsExtractor, mx: Int, my: Int) {
        val rows = visibleRows()
        popY = 4 + BTN_H + 8
        popW = POP_W
        val shownRows = minOf(rows.size, MAX_ROWS)
        popH = 38 + shownRows * ROW_H + 6
        context.fill(popX, popY, popX + popW, popY + popH, 0xF0101418.toInt())
        context.outline(popX, popY, popW, popH, 0xFF3A3A4A.toInt())
        search.x = popX + 4
        search.y = popY + 4
        search.extractRenderState(context, mx, my, 0f)
        var bx = popX + 4
        bx += button(context, bx, popY + 21, "All", mx, my) { picked.addAll(elements.map { it.label }) } + 4
        button(context, bx, popY + 21, "None", mx, my) { picked.clear(); selected = null }
        listScroll = listScroll.coerceIn(0, maxScroll())
        val top = popY + 38
        context.enableScissor(popX, top, popX + popW, top + shownRows * ROW_H)
        rows.forEachIndexed { i, e ->
            val y = top + i * ROW_H - listScroll
            val on = e.label in picked
            if (mx in popX..(popX + popW) && my in y..(y + ROW_H - 1) && my >= top && my < top + shownRows * ROW_H) context.fill(popX + 1, y, popX + popW - 1, y + ROW_H, 0x33FFFFFF)
            if (on) context.fill(popX + 1, y, popX + 3, y + ROW_H, 0xFF6AA8FF.toInt())
            context.text(this.font, e.label, popX + 8, y + 2, if (on) 0xFFFFFFFF.toInt() else 0xFF777777.toInt())
        }
        context.disableScissor()
    }

    private fun inPopover(mx: Int, my: Int) = open && mx in popX..(popX + popW) && my in popY..(popY + popH)

    /** Panels under the point, topmost first: the selected one (drawn last), then the rest from the end of the list. */
    private fun hitsAt(mx: Int, my: Int): List<HudElement> {
        val sel = selected
        return shown.asReversed().filter { element ->
            val scaledWidth = (element.lastWidth * element.panelScale).toInt()
            val scaledHeight = (element.lastHeight * element.panelScale).toInt()
            mx in element.panelX..(element.panelX + scaledWidth) &&
                    my in element.panelY..(element.panelY + scaledHeight)
        }.sortedBy { it !== sel }
    }

    private fun elementAt(mx: Int, my: Int): HudElement? = hitsAt(mx, my).firstOrNull()

    override fun mouseClicked(event: MouseButtonEvent, focused: Boolean): Boolean {
        val mx = event.x().toInt()
        val my = event.y().toInt()

        btns.lastOrNull { mx in it.x..(it.x + it.w) && my in it.y..(it.y + it.h) }?.let { it.action(); return true }

        if (inPopover(mx, my)) {
            if (my in popY + 4..popY + 18) { search.mouseClicked(event, focused); return true }
            search.setFocused(false)
            val top = popY + 38
            if (my >= top) visibleRows().getOrNull((my - top + listScroll) / ROW_H)?.label?.let { if (!picked.remove(it)) picked.add(it) }
            return true
        }
        if (open) { open = false; search.setFocused(false) }

        val hits = hitsAt(mx, my)
        // Right click on stacked panels selects the next one underneath (wraps around).
        val hit = if (Keys.mouse(event) == 1 && hits.size > 1) {
            val cur = hits.indexOf(selected)
            hits[(cur + 1) % hits.size].also { selected = it; return true }
        } else hits.firstOrNull()
        selected = hit
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
        if (inPopover(x.toInt(), y.toInt())) {
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
        if (search.isFocused && event.key() != Keys.ESCAPE) { search.keyPressed(event); return true }
        if (event.key() == Keys.ESCAPE && open) { open = false; search.setFocused(false); return true }
        if (event.key() == Keys.G) { grid = !grid; return true }
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
        private const val POP_W = 170
        private const val ROW_H = 12
        private const val MAX_ROWS = 14
        private const val BTN_H = 13
        private var pickedLabels: MutableSet<String>? = null
        /** Kept for the session, so reopening the editor keeps your choice. */
        private var grid = false
    }
}
