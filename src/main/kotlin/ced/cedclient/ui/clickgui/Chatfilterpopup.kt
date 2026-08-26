package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.misc.ChatFilter
import ced.cedclient.features.impl.misc.ChatFilterEntry
import ced.cedclient.ui.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.TextInputHandler
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW

/**
 * Opened by ChatFilter's "Configure Filters" ActionSetting. Lists every
 * filter line (defaults + custom) with an on/off toggle box, plus a text
 * field to add new custom substring filters. Structurally close to
 * FilterPopup (same scrollbar/drag/scissor approach), but not built on it --
 * FilterPopup's Blocked/Only two-set model doesn't map onto "one list, each
 * row individually enabled/disabled", so this owns its own drawContent/
 * mouseClicked instead of trying to force that shape.
 */
class ChatFilterPopup : Popup("Chat Filters", WIDTH, HEIGHT) {

    private var scrollOffset = 0f
    private var newEntry = ""

    private var draggingScrollbar = false
    private var dragStartMouseY = 0f
    private var dragStartScrollOffset = 0f

    private val input = TextInputHandler(
        textProvider = { newEntry },
        textSetter = { newEntry = it.take(48) }
    )

    private val inputY get() = y + 34f
    private val inputX get() = x + 12f
    private val addButtonWidth = 50f
    private val inputWidth = width - 24f - addButtonWidth - 8f
    private val addButtonX get() = inputX + inputWidth + 8f

    private val listY get() = y + 64f
    private val listHeight get() = height - 74f

    private val scrollbarWidth = 5f
    private val scrollbarX get() = x + width - scrollbarWidth - 4f

    private fun submitNewEntry() {
        val trimmed = newEntry.trim()
        if (trimmed.isEmpty()) return
        ChatFilter.addCustom(trimmed)
        newEntry = ""
    }

    override fun drawContent(mouseX: Float, mouseY: Float) {
        NVGRenderer.rect(inputX, inputY, inputWidth, INPUT_HEIGHT, Colors.gray38.rgba, 4f)
        input.x = inputX
        input.y = inputY + 1f
        input.width = inputWidth
        input.height = INPUT_HEIGHT - 2f
        input.draw(mouseX, mouseY)

        val addHovered = isAreaHovered(addButtonX, inputY, addButtonWidth, INPUT_HEIGHT, mouseX, mouseY)
        NVGRenderer.rect(
            addButtonX,
            inputY,
            addButtonWidth,
            INPUT_HEIGHT,
            (if (addHovered) ClickGUI.clickGUIColor else Colors.gray38).rgba,
            4f
        )
        val addWidth = NVGRenderer.textWidth("Add", 16f, NVGRenderer.defaultFont)
        NVGRenderer.text(
            "Add",
            addButtonX + addButtonWidth / 2f - addWidth / 2f,
            inputY + 4f,
            16f,
            Colors.WHITE.rgba,
            NVGRenderer.defaultFont
        )

        val list = ChatFilter.currentEntries()
        val contentHeight = list.size * ROW_HEIGHT
        val needsScrollbar = contentHeight > listHeight

        if (draggingScrollbar) {
            val maxScroll = (-(contentHeight - listHeight)).coerceAtMost(0f)
            val thumbHeight = (listHeight * listHeight / contentHeight).coerceAtLeast(MIN_THUMB_HEIGHT)
            val maxThumbTravel = listHeight - thumbHeight
            if (maxThumbTravel > 0f) {
                val scrollPerPixel = maxScroll / maxThumbTravel
                scrollOffset = (dragStartScrollOffset + (mouseY - dragStartMouseY) * scrollPerPixel)
                    .coerceIn(maxScroll, 0f)
            }
        }

        NVGRenderer.pushScissor(x, listY, width, listHeight)
        var rowY = listY + scrollOffset
        for (entry in list) {
            drawRow(entry, rowY, mouseX, mouseY)
            rowY += ROW_HEIGHT
        }
        if (list.isEmpty()) {
            val emptyWidth = NVGRenderer.textWidth("No filters", 15f, NVGRenderer.defaultFont)
            NVGRenderer.text(
                "No filters",
                x + width / 2f - emptyWidth / 2f,
                listY + 8f,
                15f,
                Colors.MINECRAFT_GRAY.rgba,
                NVGRenderer.defaultFont
            )
        }
        NVGRenderer.popScissor()

        if (needsScrollbar) drawScrollbar(contentHeight, mouseX, mouseY)
    }

    private fun drawScrollbar(contentHeight: Float, mouseX: Float, mouseY: Float) {
        NVGRenderer.rect(scrollbarX, listY, scrollbarWidth, listHeight, Colors.gray26.rgba, scrollbarWidth / 2f)

        val thumbHeight = (listHeight * listHeight / contentHeight).coerceAtLeast(MIN_THUMB_HEIGHT)
        val maxThumbTravel = listHeight - thumbHeight
        val maxScroll = -(contentHeight - listHeight)
        val scrollPercent = if (maxScroll < 0f) (scrollOffset / maxScroll).coerceIn(0f, 1f) else 0f
        val thumbY = listY + maxThumbTravel * scrollPercent

        val hovered = isAreaHovered(scrollbarX, thumbY, scrollbarWidth, thumbHeight, mouseX, mouseY)
        val color = if (draggingScrollbar || hovered) ClickGUI.clickGUIColor else Colors.gray38
        NVGRenderer.rect(scrollbarX, thumbY, scrollbarWidth, thumbHeight, color.rgba, scrollbarWidth / 2f)
    }

    private fun drawRow(entry: ChatFilterEntry, rowY: Float, mouseX: Float, mouseY: Float) {
        if (rowY + ROW_HEIGHT < listY || rowY > listY + listHeight) return

        val removeSize = if (entry.custom) 14f else 0f
        val removeGap = if (entry.custom) 6f else 0f
        val rowWidth = width - 24f

        val hovered = isAreaHovered(x + 12f, rowY, rowWidth, ROW_HEIGHT - 2f, mouseX, mouseY)
        if (hovered) NVGRenderer.rect(x + 12f, rowY, rowWidth, ROW_HEIGHT - 2f, Colors.gray38.rgba, 3f)

        val boxSize = 12f
        val boxColor = if (entry.enabled) ClickGUI.clickGUIColor else Colors.gray38
        NVGRenderer.rect(x + 16f, rowY + (ROW_HEIGHT - 2f) / 2f - boxSize / 2f, boxSize, boxSize, boxColor.rgba, 3f)

        val labelMaxWidth = rowWidth - boxSize - 16f - removeSize - removeGap - 8f
        val label = NVGRenderer.truncate(entry.pattern, labelMaxWidth, 15f, NVGRenderer.defaultFont)
        NVGRenderer.text(
            label,
            x + 16f + boxSize + 8f,
            rowY + (ROW_HEIGHT - 2f) / 2f - 7f,
            15f,
            Colors.WHITE.rgba,
            NVGRenderer.defaultFont
        )

        if (entry.custom) {
            val removeX = x + width - 12f - removeSize
            val removeY = rowY + (ROW_HEIGHT - 2f) / 2f - removeSize / 2f
            val removeHovered = isAreaHovered(removeX, removeY, removeSize, removeSize, mouseX, mouseY)
            val rWidth = NVGRenderer.textWidth("x", 12f, NVGRenderer.defaultFont)
            NVGRenderer.text(
                "x",
                removeX + removeSize / 2f - rWidth / 2f,
                removeY - 1f,
                12f,
                (if (removeHovered) Colors.MINECRAFT_RED else Colors.MINECRAFT_GRAY).rgba,
                NVGRenderer.defaultFont
            )
        }
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (click.button() != 0) return false

        if (isAreaHovered(addButtonX, inputY, addButtonWidth, INPUT_HEIGHT, mouseX, mouseY)) {
            submitNewEntry()
            return true
        }
        if (input.mouseClicked(mouseX, mouseY, click)) return true

        val list = ChatFilter.currentEntries()
        val contentHeight = list.size * ROW_HEIGHT
        if (contentHeight > listHeight && isAreaHovered(scrollbarX, listY, scrollbarWidth, listHeight, mouseX, mouseY)) {
            draggingScrollbar = true
            dragStartMouseY = mouseY
            dragStartScrollOffset = scrollOffset
            return true
        }

        if (mouseY in listY..(listY + listHeight)) {
            val index = ((mouseY - listY - scrollOffset) / ROW_HEIGHT).toInt()
            val entry = list.getOrNull(index) ?: return true

            val removeSize = 14f
            val rowTop = listY + scrollOffset + index * ROW_HEIGHT
            if (entry.custom) {
                val removeX = x + width - 12f - removeSize
                val removeY = rowTop + (ROW_HEIGHT - 2f) / 2f - removeSize / 2f
                if (isAreaHovered(removeX, removeY, removeSize, removeSize, mouseX, mouseY)) {
                    ChatFilter.removeCustom(entry.id)
                    return true
                }
            }

            ChatFilter.setEnabled(entry.id, !entry.enabled)
            return true
        }
        return true // swallow clicks anywhere else inside the popup
    }

    override fun mouseReleased(click: MouseButtonEvent) {
        input.mouseReleased()
        draggingScrollbar = false
    }

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Int): Boolean {
        if (mouseY !in listY..(listY + listHeight)) return false
        val contentHeight = ChatFilter.currentEntries().size * ROW_HEIGHT
        scrollOffset = (scrollOffset + amount).coerceIn((-contentHeight + listHeight).coerceAtMost(0f), 0f)
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        val wasEnter = input.key == GLFW.GLFW_KEY_ENTER
        val handled = this.input.keyPressed(input)
        if (wasEnter) submitNewEntry()
        return handled || wasEnter
    }

    override fun keyTyped(input: CharacterEvent): Boolean = this.input.keyTyped(input)

    companion object {
        private const val WIDTH = 280f
        private const val HEIGHT = 320f
        private const val INPUT_HEIGHT = 22f
        private const val ROW_HEIGHT = 24f
        private const val MIN_THUMB_HEIGHT = 20f
    }
}