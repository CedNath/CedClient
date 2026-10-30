package ced.cedclient.ui.clickgui

import ced.cedclient.utils.Keys
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.TextInputHandler
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block

/**
 * Searchable, scrollable list of every vanilla block. Click a row to pick it ([onPick] runs after the popup
 * closes, so it may open another popup). [buttons] are extra actions drawn in a row under the title
 * (e.g. "Any block", "Looking at: Stone"); a button decides for itself whether to close or replace the popup.
 */
class BlockPickerPopup(
    title: String,
    private val buttons: List<Pair<String, () -> Unit>> = emptyList(),
    private val onPick: (Block) -> Unit,
) : Popup(title, WIDTH, HEIGHT) {

    private class Entry(val block: Block, val label: String, val id: String) {
        /** The block's item for its icon, or null for blocks without one (water, fire, piston head, ...). */
        val icon: ItemStack? by lazy { block.asItem().takeIf { it != Items.AIR }?.let(::ItemStack) }
    }

    /** Row under the mouse last frame (for the large preview). */
    private var hoveredIndex = -1

    private var query = ""
    private var lastQuery = ""
    private var filtered: List<Entry> = ALL
    private var scrollOffset = 0f

    // Dragging the scrollbar thumb (same pattern as FilterPopup: drawContent follows the live mouse while it's true).
    private var draggingScrollbar = false
    private var dragStartMouseY = 0f
    private var dragStartScrollOffset = 0f

    private val input = TextInputHandler(
        textProvider = { query },
        textSetter = { query = it.take(32) }
    )

    // Type straight away, no click on the search box needed.
    init { input.focus() }

    private val buttonsY get() = y + 36f
    private val inputY get() = y + 64f
    private val listY get() = y + 98f
    private val listHeight get() = height - 108f

    override fun drawContent(mouseX: Float, mouseY: Float) {
        if (query != lastQuery) {
            lastQuery = query
            val q = query.trim().lowercase().replace(' ', '_')
            filtered = if (q.isEmpty()) ALL else ALL.filter { it.id.contains(q) }
            scrollOffset = 0f
        }

        // Action buttons
        if (buttons.isNotEmpty()) {
            val bw = buttonWidth()
            buttons.forEachIndexed { i, (text, _) ->
                val bx = x + 12f + i * (bw + BUTTON_GAP)
                val hovered = isAreaHovered(bx, buttonsY, bw, BUTTON_HEIGHT, mouseX, mouseY)
                NVGRenderer.rect(bx, buttonsY, bw, BUTTON_HEIGHT, (if (hovered) ClickGUI.clickGUIColor else Colors.gray38).rgba, 4f)
                val label = NVGRenderer.truncate(text, bw - 8f, 14f, NVGRenderer.defaultFont)
                val tw = NVGRenderer.textWidth(label, 14f, NVGRenderer.defaultFont)
                NVGRenderer.text(label, bx + bw / 2f - tw / 2f, buttonsY + 5f, 14f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
            }
        }

        // Search box (+ a slot on its right for the hovered block's large preview)
        val fieldW = width - 24f - PREVIEW_SLOT
        NVGRenderer.rect(x + 12f, inputY, fieldW, INPUT_HEIGHT, Colors.gray38.rgba, 4f)
        if (query.isEmpty()) {
            NVGRenderer.text("Search blocks...", x + 18f, inputY + 5f, 15f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)
        }
        input.x = x + 12f
        input.y = inputY + 1f
        input.width = fieldW
        input.height = INPUT_HEIGHT - 2f
        input.draw(mouseX, mouseY)
        NVGRenderer.rect(previewX, previewY, PREVIEW_SIZE, PREVIEW_SIZE, Colors.gray38.rgba, 4f)

        // List (only visible rows are drawn)
        val contentHeight = filtered.size * ROW_HEIGHT
        if (draggingScrollbar) {
            val travel = listHeight - thumbHeight(contentHeight)
            if (travel > 0f) {
                // Thumb pixels -> content pixels, so the thumb stays under the mouse.
                scrollOffset = dragStartScrollOffset + (mouseY - dragStartMouseY) * (-(contentHeight - listHeight) / travel)
            }
        }
        scrollOffset = scrollOffset.coerceIn((-contentHeight + listHeight).coerceAtMost(0f), 0f)

        NVGRenderer.pushScissor(x, listY, width, listHeight)
        val first = (-scrollOffset / ROW_HEIGHT).toInt().coerceAtLeast(0)
        val last = (first + (listHeight / ROW_HEIGHT).toInt() + 2).coerceAtMost(filtered.size)
        hoveredIndex = -1
        for (i in first until last) {
            val rowY = listY + scrollOffset + i * ROW_HEIGHT
            val hovered = mouseY in listY..(listY + listHeight) && isAreaHovered(x + 8f, rowY, width - 24f, ROW_HEIGHT - 2f, mouseX, mouseY)
            if (hovered) {
                hoveredIndex = i
                NVGRenderer.rect(x + 8f, rowY, width - 24f, ROW_HEIGHT - 2f, Colors.gray38.rgba, 4f)
            }
            // Icons are drawn by extractItems; the text leaves room for them.
            NVGRenderer.text(filtered[i].label, x + TEXT_X, rowY + 4f, 15f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
        }
        if (filtered.isEmpty()) {
            NVGRenderer.text("No blocks found", x + 16f, listY + 8f, 15f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)
        }
        NVGRenderer.popScissor()

        // Scrollbar (wheel or drag)
        if (contentHeight > listHeight) {
            val sx = x + width - 9f
            NVGRenderer.rect(sx, listY, 5f, listHeight, Colors.gray26.rgba, 2.5f)
            val thumb = thumbHeight(contentHeight)
            val ty = thumbY(contentHeight)
            val hovered = isAreaHovered(scrollbarHitX, ty, SCROLLBAR_HIT_W, thumb, mouseX, mouseY)
            val color = if (draggingScrollbar || hovered) ClickGUI.clickGUIColor else Colors.gray38
            NVGRenderer.rect(sx, ty, 5f, thumb, color.rgba, 2.5f)
        }
    }

    private fun thumbHeight(contentHeight: Float) = (listHeight * listHeight / contentHeight).coerceAtLeast(20f)

    private fun thumbY(contentHeight: Float): Float {
        val maxScroll = -(contentHeight - listHeight)
        val pct = if (maxScroll < 0f) (scrollOffset / maxScroll).coerceIn(0f, 1f) else 0f
        return listY + (listHeight - thumbHeight(contentHeight)) * pct
    }

    /** The 5px bar is hard to hit, so clicks a few pixels either side of it count too. */
    private val scrollbarHitX get() = x + width - 12f

    private val previewX get() = x + width - 12f - PREVIEW_SIZE
    private val previewY get() = inputY + INPUT_HEIGHT / 2f - PREVIEW_SIZE / 2f

    override fun extractItems(g: GuiGraphicsExtractor, toGui: Float) {
        // Row icons, clipped to the list
        g.enableScissor(
            (x * toGui).toInt(), (listY * toGui).toInt(),
            ((x + width) * toGui).toInt(), ((listY + listHeight) * toGui).toInt()
        )
        val first = (-scrollOffset / ROW_HEIGHT).toInt().coerceAtLeast(0)
        val last = (first + (listHeight / ROW_HEIGHT).toInt() + 2).coerceAtMost(filtered.size)
        for (i in first until last) {
            val stack = filtered[i].icon ?: continue
            val rowY = listY + scrollOffset + i * ROW_HEIGHT
            icon(g, stack, x + 14f, rowY + (ROW_HEIGHT - 2f - ICON_SIZE) / 2f, ICON_SIZE, toGui)
        }
        g.disableScissor()

        // Large preview of the hovered block
        filtered.getOrNull(hoveredIndex)?.icon?.let { stack ->
            val pad = 3f
            icon(g, stack, previewX + pad, previewY + pad, PREVIEW_SIZE - pad * 2, toGui)
        }
    }

    /** Draws [stack] as a [size]-unit square at popup position ([px], [py]). */
    private fun icon(g: GuiGraphicsExtractor, stack: ItemStack, px: Float, py: Float, size: Float, toGui: Float) {
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(px * toGui, py * toGui)
        val s = size * toGui / 16f
        pose.scale(s, s)
        g.fakeItem(stack, 0, 0)
        pose.popMatrix()
    }

    private fun buttonWidth() =(width - 24f - BUTTON_GAP * (buttons.size - 1)) / buttons.size

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (Keys.mouse(click) != 0) return false

        if (buttons.isNotEmpty()) {
            val bw = buttonWidth()
            buttons.forEachIndexed { i, (_, action) ->
                val bx = x + 12f + i * (bw + BUTTON_GAP)
                if (isAreaHovered(bx, buttonsY, bw, BUTTON_HEIGHT, mouseX, mouseY)) {
                    action()
                    return true
                }
            }
        }
        if (input.mouseClicked(mouseX, mouseY, click)) return true

        val contentHeight = filtered.size * ROW_HEIGHT
        if (contentHeight > listHeight && isAreaHovered(scrollbarHitX, listY, SCROLLBAR_HIT_W, listHeight, mouseX, mouseY)) {
            // Clicked the track off the thumb: jump so the thumb centres on the mouse, then drag from there.
            val thumb = thumbHeight(contentHeight)
            val ty = thumbY(contentHeight)
            if (mouseY !in ty..(ty + thumb)) {
                val pct = ((mouseY - listY - thumb / 2f) / (listHeight - thumb)).coerceIn(0f, 1f)
                scrollOffset = -(contentHeight - listHeight) * pct
            }
            draggingScrollbar = true
            dragStartMouseY = mouseY
            dragStartScrollOffset = scrollOffset
            return true
        }

        if (mouseY in listY..(listY + listHeight)) {
            val index = ((mouseY - listY - scrollOffset) / ROW_HEIGHT).toInt()
            val entry = filtered.getOrNull(index) ?: return true
            (net.minecraft.client.Minecraft.getInstance().gui.screen() as? ClickGUI)?.closePopup()
            onPick(entry.block)
            return true
        }
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent) {
        input.mouseReleased()
        draggingScrollbar = false
    }

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Int): Boolean {
        if (mouseY !in listY..(listY + listHeight)) return false
        scrollOffset = (scrollOffset + amount).coerceIn((-filtered.size * ROW_HEIGHT + listHeight).coerceAtMost(0f), 0f)
        return true
    }

    // Escape closes the popup (ClickGUI) instead of just leaving the always-focused search box.
    override fun keyPressed(input: KeyEvent): Boolean =
        input.key != Keys.ESCAPE && this.input.keyPressed(input)
    override fun keyTyped(input: CharacterEvent): Boolean = this.input.keyTyped(input)

    companion object {
        private const val INPUT_HEIGHT = 24f
        private const val BUTTON_HEIGHT = 22f
        private const val BUTTON_GAP = 6f
        private const val ROW_HEIGHT = 22f
        private const val ICON_SIZE = 16f
        private const val TEXT_X = 38f
        private const val PREVIEW_SIZE = 34f
        private const val PREVIEW_SLOT = PREVIEW_SIZE + 6f
        private const val SCROLLBAR_HIT_W = 11f

        /** Every vanilla block, sorted by id. Built on first use (registries are ready by then). */
        private val ALL: List<Entry> by lazy {
            BuiltInRegistries.BLOCK
                .mapNotNull { b ->
                    val key = BuiltInRegistries.BLOCK.getKey(b)
                    if (key.namespace != "minecraft") null
                    else Entry(b, key.path.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }, key.path)
                }
                .sortedBy { it.id }
        }
    }
}