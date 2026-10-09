package ced.cedclient.ui.clickgui

import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.TextInputHandler
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.registries.BuiltInRegistries
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

    private class Entry(val block: Block, val label: String, val id: String)

    private var query = ""
    private var lastQuery = ""
    private var filtered: List<Entry> = ALL
    private var scrollOffset = 0f

    private val input = TextInputHandler(
        textProvider = { query },
        textSetter = { query = it.take(32) }
    )

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

        // Search box
        NVGRenderer.rect(x + 12f, inputY, width - 24f, INPUT_HEIGHT, Colors.gray38.rgba, 4f)
        if (query.isEmpty()) {
            NVGRenderer.text("Search blocks...", x + 18f, inputY + 5f, 15f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)
        }
        input.x = x + 12f
        input.y = inputY + 1f
        input.width = width - 24f
        input.height = INPUT_HEIGHT - 2f
        input.draw(mouseX, mouseY)

        // List (only visible rows are drawn)
        val contentHeight = filtered.size * ROW_HEIGHT
        scrollOffset = scrollOffset.coerceIn((-contentHeight + listHeight).coerceAtMost(0f), 0f)

        NVGRenderer.pushScissor(x, listY, width, listHeight)
        val first = (-scrollOffset / ROW_HEIGHT).toInt().coerceAtLeast(0)
        val last = (first + (listHeight / ROW_HEIGHT).toInt() + 2).coerceAtMost(filtered.size)
        for (i in first until last) {
            val rowY = listY + scrollOffset + i * ROW_HEIGHT
            val hovered = mouseY in listY..(listY + listHeight) && isAreaHovered(x + 8f, rowY, width - 24f, ROW_HEIGHT - 2f, mouseX, mouseY)
            if (hovered) NVGRenderer.rect(x + 8f, rowY, width - 24f, ROW_HEIGHT - 2f, Colors.gray38.rgba, 4f)
            NVGRenderer.text(filtered[i].label, x + 16f, rowY + 4f, 15f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
        }
        if (filtered.isEmpty()) {
            NVGRenderer.text("No blocks found", x + 16f, listY + 8f, 15f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)
        }
        NVGRenderer.popScissor()

        // Scroll thumb (wheel-driven)
        if (contentHeight > listHeight) {
            val sx = x + width - 9f
            NVGRenderer.rect(sx, listY, 5f, listHeight, Colors.gray26.rgba, 2.5f)
            val thumb = (listHeight * listHeight / contentHeight).coerceAtLeast(20f)
            val maxScroll = -(contentHeight - listHeight)
            val pct = (scrollOffset / maxScroll).coerceIn(0f, 1f)
            NVGRenderer.rect(sx, listY + (listHeight - thumb) * pct, 5f, thumb, Colors.gray38.rgba, 2.5f)
        }
    }

    private fun buttonWidth() = (width - 24f - BUTTON_GAP * (buttons.size - 1)) / buttons.size

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (click.button() != 0) return false

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

        if (mouseY in listY..(listY + listHeight)) {
            val index = ((mouseY - listY - scrollOffset) / ROW_HEIGHT).toInt()
            val entry = filtered.getOrNull(index) ?: return true
            (net.minecraft.client.Minecraft.getInstance().screen as? ClickGUI)?.closePopup()
            onPick(entry.block)
            return true
        }
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent) = input.mouseReleased()

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Int): Boolean {
        if (mouseY !in listY..(listY + listHeight)) return false
        scrollOffset += amount
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean = this.input.keyPressed(input)
    override fun keyTyped(input: CharacterEvent): Boolean = this.input.keyTyped(input)

    companion object {
        private const val WIDTH = 360f
        private const val HEIGHT = 440f
        private const val INPUT_HEIGHT = 24f
        private const val BUTTON_HEIGHT = 22f
        private const val BUTTON_GAP = 6f
        private const val ROW_HEIGHT = 22f

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