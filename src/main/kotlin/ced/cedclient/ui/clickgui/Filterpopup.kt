package ced.cedclient.ui.clickgui

import ced.cedclient.ui.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.TextInputHandler
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW

/**
 * Shared chrome for EntityESP's three "Select ___" popups: a Blocked/Only
 * tab toggle, a text field to type a name pattern in directly, and a
 * scrollable list combining [candidateNames] (one-click toggle) with
 * whatever's already in the active list (so a manually-typed entry that
 * isn't a live candidate still shows up and can be removed the same way).
 *
 * Talks to EntityESP purely through the constructor callbacks rather than
 * importing EntityESP itself, so this stays reusable if CedClient ever
 * grows a second name-filterable module.
 */
abstract class FilterPopup(
    title: String,
    private val blockedNames: () -> Set<String>,
    private val onlyNames: () -> Set<String>,
    private val addBlocked: (String) -> Unit,
    private val removeBlocked: (String) -> Unit,
    private val addOnly: (String) -> Unit,
    private val removeOnly: (String) -> Unit
) : Popup(title, WIDTH, HEIGHT) {

    /** Names to offer as one-click toggles, e.g. currently-scanned entity names. Empty = free-text only. */
    protected open fun candidateNames(): List<String> = emptyList()

    private var mode = FilterMode.ONLY
    private var scrollOffset = 0f
    private var newEntry = ""

    // Drag-to-scroll on the scrollbar thumb -- same self-contained pattern
    // NumberSetting/Panel use for dragging: mouseClicked starts it and
    // records where we started, drawContent updates scrollOffset every
    // frame from the live mouseY while it's true (there's no
    // mouseDragged hook wired up to popups), mouseReleased ends it.
    // Wheel-scrolling (mouseScrolled below) still works independently --
    // this is on top of that, not a replacement.
    private var draggingScrollbar = false
    private var dragStartMouseY = 0f
    private var dragStartScrollOffset = 0f

    private val input = TextInputHandler(
        textProvider = { newEntry },
        textSetter = { newEntry = it.take(32) }
    )

    private enum class FilterMode(val label: String) {
        BLOCKED("Blocked"),
        ONLY("Only")
    }

    private val tabY get() = y + 34f
    private val blockedTabX get() = x + 12f
    private val onlyTabX get() = x + width / 2f + 2f
    private val tabWidth = width / 2f - 14f

    private val inputY get() = y + 64f
    private val inputX get() = x + 12f
    private val addButtonWidth = 50f
    private val inputWidth = width - 24f - addButtonWidth - 8f
    private val addButtonX get() = inputX + inputWidth + 8f

    private val listY get() = y + 94f
    private val listHeight get() = height - 104f

    private val scrollbarWidth = 5f
    private val scrollbarX get() = x + width - scrollbarWidth - 4f

    private fun entries(): List<String> =
        (candidateNames() + blockedNames() + onlyNames()).distinct().sorted()

    private fun activeSet(): Set<String> = if (mode == FilterMode.BLOCKED) blockedNames() else onlyNames()

    private fun toggle(name: String) {
        val active = mode == FilterMode.BLOCKED
        val inList = if (active) blockedNames().contains(name) else onlyNames().contains(name)
        when {
            active && inList -> removeBlocked(name)
            active -> addBlocked(name)
            inList -> removeOnly(name)
            else -> addOnly(name)
        }
    }

    private fun submitNewEntry() {
        val trimmed = newEntry.trim()
        if (trimmed.isEmpty()) return
        if (mode == FilterMode.BLOCKED) addBlocked(trimmed) else addOnly(trimmed)
        newEntry = ""
    }

    override fun drawContent(mouseX: Float, mouseY: Float) {
        drawTab(FilterMode.BLOCKED, blockedTabX, mouseX, mouseY)
        drawTab(FilterMode.ONLY, onlyTabX, mouseX, mouseY)

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

        val list = entries()
        val contentHeight = list.size * ROW_HEIGHT
        val needsScrollbar = contentHeight > listHeight

        if (draggingScrollbar) {
            val maxScroll = (-(contentHeight - listHeight)).coerceAtMost(0f)
            val thumbHeight = (listHeight * listHeight / contentHeight).coerceAtLeast(MIN_THUMB_HEIGHT)
            val maxThumbTravel = listHeight - thumbHeight
            if (maxThumbTravel > 0f) {
                // Dragging the thumb down by maxThumbTravel px should move scrollOffset
                // all the way from 0 to maxScroll -- this converts pixels-of-thumb-travel
                // into pixels-of-content-scroll so the drag feels 1:1 with the thumb.
                val scrollPerPixel = maxScroll / maxThumbTravel
                scrollOffset = (dragStartScrollOffset + (mouseY - dragStartMouseY) * scrollPerPixel)
                    .coerceIn(maxScroll, 0f)
            }
        }

        NVGRenderer.pushScissor(x, listY, width, listHeight)
        var rowY = listY + scrollOffset
        val active = activeSet()
        for (name in list) {
            drawRow(name, name in active, rowY, mouseX, mouseY)
            rowY += ROW_HEIGHT
        }
        if (list.isEmpty()) {
            val emptyWidth = NVGRenderer.textWidth("No entries yet", 15f, NVGRenderer.defaultFont)
            NVGRenderer.text(
                "No entries yet",
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

    private fun drawTab(tab: FilterMode, tabX: Float, mouseX: Float, mouseY: Float) {
        val selected = mode == tab
        val hovered = isAreaHovered(tabX, tabY, tabWidth, TAB_HEIGHT, mouseX, mouseY)
        val color = when {
            selected -> ClickGUI.clickGUIColor
            hovered -> Colors.gray38
            else -> Colors.gray26
        }
        NVGRenderer.rect(tabX, tabY, tabWidth, TAB_HEIGHT, color.rgba, 4f)
        val labelWidth = NVGRenderer.textWidth(tab.label, 15f, NVGRenderer.defaultFont)
        NVGRenderer.text(
            tab.label,
            tabX + tabWidth / 2f - labelWidth / 2f,
            tabY + TAB_HEIGHT / 2f - 8f,
            15f,
            Colors.WHITE.rgba,
            NVGRenderer.defaultFont
        )
    }

    private fun drawRow(name: String, active: Boolean, rowY: Float, mouseX: Float, mouseY: Float) {
        if (rowY + ROW_HEIGHT < listY || rowY > listY + listHeight) return

        val hovered = isAreaHovered(x + 12f, rowY, width - 24f, ROW_HEIGHT - 2f, mouseX, mouseY)
        if (hovered) NVGRenderer.rect(x + 12f, rowY, width - 24f, ROW_HEIGHT - 2f, Colors.gray38.rgba, 3f)

        val boxSize = 12f
        val boxColor = if (active) ClickGUI.clickGUIColor else Colors.gray38
        NVGRenderer.rect(x + 16f, rowY + (ROW_HEIGHT - 2f) / 2f - boxSize / 2f, boxSize, boxSize, boxColor.rgba, 3f)

        val label = NVGRenderer.truncate(name, width - 24f - boxSize - 16f, 15f, NVGRenderer.defaultFont)
        NVGRenderer.text(
            label,
            x + 16f + boxSize + 8f,
            rowY + (ROW_HEIGHT - 2f) / 2f - 7f,
            15f,
            Colors.WHITE.rgba,
            NVGRenderer.defaultFont
        )
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (click.button() != 0) return false

        if (isAreaHovered(blockedTabX, tabY, tabWidth, TAB_HEIGHT, mouseX, mouseY)) {
            mode = FilterMode.BLOCKED
            return true
        }
        if (isAreaHovered(onlyTabX, tabY, tabWidth, TAB_HEIGHT, mouseX, mouseY)) {
            mode = FilterMode.ONLY
            return true
        }
        if (isAreaHovered(addButtonX, inputY, addButtonWidth, INPUT_HEIGHT, mouseX, mouseY)) {
            submitNewEntry()
            return true
        }
        if (input.mouseClicked(mouseX, mouseY, click)) return true

        val list = entries()
        val contentHeight = list.size * ROW_HEIGHT
        if (contentHeight > listHeight && isAreaHovered(scrollbarX, listY, scrollbarWidth, listHeight, mouseX, mouseY)) {
            draggingScrollbar = true
            dragStartMouseY = mouseY
            dragStartScrollOffset = scrollOffset
            return true
        }

        if (mouseY in listY..(listY + listHeight)) {
            val index = ((mouseY - listY - scrollOffset) / ROW_HEIGHT).toInt()
            list.getOrNull(index)?.let { name ->
                toggle(name)
                return true
            }
        }
        return true // swallow clicks anywhere else inside the popup so they don't fall through to the panel below
    }

    override fun mouseReleased(click: MouseButtonEvent) {
        input.mouseReleased()
        draggingScrollbar = false
    }

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Int): Boolean {
        if (mouseY !in listY..(listY + listHeight)) return false
        val contentHeight = entries().size * ROW_HEIGHT
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
        private const val TAB_HEIGHT = 24f
        private const val INPUT_HEIGHT = 22f
        private const val ROW_HEIGHT = 24f
        private const val MIN_THUMB_HEIGHT = 20f
    }
}