package ced.cedclient.ui.clickgui

//#if CHEAT
import ced.cedclient.features.impl.render.EspStyle
import ced.cedclient.features.settings.ColorSetting
//#endif
import ced.cedclient.render.nvg.NVGRenderer
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
//#if CHEAT
 * Active rows in the Only tab also get Box / Tracer / Label chips when [styleOf] returns a style for them
 * (EntityESP / BlockESP): what to draw for that entry. [onStyleChanged] persists a click.
 *
//#endif
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
//#if CHEAT
    private val removeOnly: (String) -> Unit,
    private val styleOf: (String) -> EspStyle? = { null },
    private val onStyleChanged: () -> Unit = {},
//#else
//$    private val removeOnly: (String) -> Unit
//#endif
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

    // Type straight away, no click on the search box needed.
    init { input.focus() }

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
//#if CHEAT
    private val listHeight get() = height - 104f - editorHeight

    /** Colour picker for one entry, docked under the list while open. */
    private var colorEntry: String? = null
    private var colorEditor: ColorSetting? = null
    private val editorHeight get() = colorEditor?.let { it.getHeight() + 10f } ?: 0f

    private fun openColorEditor(name: String, style: EspStyle) {
        val color = style.color ?: Colors.WHITE.copy().also { style.color = it }
        // value is the style's own Color object, so dragging in the picker recolours the ESP live.
        colorEditor = ColorSetting("Colour: $name", color).apply { value = color; picking = true }
        colorEntry = name
        onStyleChanged()
    }

    private fun closeColorEditor() {
        colorEditor = null
        colorEntry = null
    }
//#else
//$    private val listHeight get() = height - 104f
//#endif

    private val scrollbarWidth = 5f
    private val scrollbarX get() = x + width - scrollbarWidth - 4f

    private fun entries(): List<String> {
        val all = (candidateNames() + blockedNames() + onlyNames()).distinct()
        val filter = newEntry.trim()
        val filtered = if (filter.isEmpty()) all else all.filter { it.contains(filter, ignoreCase = true) }

        // Enabled-in-the-current-tab entries float to the top (alphabetical
        // within each group) so it's easy to see/disable what's active
        // without scrolling through the full alphabet to find it.
        val active = activeSet()
        return filtered.sortedWith(compareByDescending<String> { it in active }.thenBy { it })
    }

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

        NVGRenderer.rect(inputX, inputY, inputWidth, INPUT_HEIGHT, Colors.INPUT_BG.rgba, 4f)
        NVGRenderer.hollowRect(inputX, inputY, inputWidth, INPUT_HEIGHT, 1f, Colors.INPUT_BORDER.rgba, 4f)
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
//#if CHEAT
            drawRow(name, name in active, rowY, mouseX, mouseY, rowStyle(name, name in active))
//#else
//$            drawRow(name, name in active, rowY, mouseX, mouseY)
//#endif
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
//#if CHEAT

        val editor = colorEditor
        val entry = colorEntry
        if (editor != null && entry != null) {
            if (rowStyle(entry, entry in onlyNames()) == null) {
                closeColorEditor() // entry removed, or switched to the Blocked tab
            } else {
                val ey = y + height - editor.getHeight() - 8f
                NVGRenderer.rect(x + 8f, ey - 2f, width - 16f, editor.getHeight() + 4f, Colors.gray26.rgba, 4f)
                editor.render(x + (width - Panel.WIDTH) / 2f, ey, mouseX, mouseY)
            }
        }
//#endif
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

//#if CHEAT
    /** Style chips only make sense for entries that are on in the Only tab. */
    private fun rowStyle(name: String, active: Boolean): EspStyle? =
        if (mode == FilterMode.ONLY && active) styleOf(name) else null

    private val chipLabels = listOf("Box", "Tracer", "Label")

    /** Left edge of chip [i] (0 = Box, 1 = Tracer, 2 = Label), right-aligned in the row. */
    private fun chipX(i: Int) = x + width - 16f - (3 - i) * (CHIP_WIDTH + 4f) + 4f

    private val swatchX get() = chipX(0) - 4f - SWATCH

    private fun chipOn(style: EspStyle, i: Int) = when (i) { 0 -> style.box; 1 -> style.tracer; else -> style.label }

    private fun drawRow(name: String, active: Boolean, rowY: Float, mouseX: Float, mouseY: Float, style: EspStyle?) {
//#else
//$    private fun drawRow(name: String, active: Boolean, rowY: Float, mouseX: Float, mouseY: Float) {
//#endif
        if (rowY + ROW_HEIGHT < listY || rowY > listY + listHeight) return

        val hovered = isAreaHovered(x + 12f, rowY, width - 24f, ROW_HEIGHT - 2f, mouseX, mouseY)
        if (hovered) NVGRenderer.rect(x + 12f, rowY, width - 24f, ROW_HEIGHT - 2f, Colors.gray38.rgba, 3f)

        val boxSize = 12f
        val boxColor = if (active) ClickGUI.clickGUIColor else Colors.gray38
        NVGRenderer.rect(x + 16f, rowY + (ROW_HEIGHT - 2f) / 2f - boxSize / 2f, boxSize, boxSize, boxColor.rgba, 3f)

//#if CHEAT
        val chipsWidth = if (style != null) 3 * (CHIP_WIDTH + 4f) + SWATCH + 4f else 0f
        val label = NVGRenderer.truncate(name, width - 24f - boxSize - 16f - chipsWidth, 15f, NVGRenderer.defaultFont)
//#else
//$        val label = NVGRenderer.truncate(name, width - 24f - boxSize - 16f, 15f, NVGRenderer.defaultFont)
//#endif
        NVGRenderer.text(
            label,
            x + 16f + boxSize + 8f,
            rowY + (ROW_HEIGHT - 2f) / 2f - 7f,
            15f,
            Colors.WHITE.rgba,
            NVGRenderer.defaultFont
        )
//#if CHEAT

        if (style == null) return
        val chipY = rowY + (ROW_HEIGHT - 2f) / 2f - CHIP_HEIGHT / 2f

        // Colour swatch: the entry's own colour, or an empty grey box while it uses the module's default.
        val sy = rowY + (ROW_HEIGHT - 2f) / 2f - SWATCH / 2f
        NVGRenderer.rect(swatchX, sy, SWATCH, SWATCH, (style.color ?: Colors.gray26).rgba, 3f)
        val swatchOutline = if (colorEntry == name) ClickGUI.clickGUIColor else Colors.gray38
        NVGRenderer.hollowRect(swatchX, sy, SWATCH, SWATCH, 1.5f, swatchOutline.rgba, 3f)
        for (i in 0..2) {
            val cx = chipX(i)
            val on = chipOn(style, i)
            val chipHovered = isAreaHovered(cx, chipY, CHIP_WIDTH, CHIP_HEIGHT, mouseX, mouseY)
            val color = when {
                on -> ClickGUI.clickGUIColor
                chipHovered -> Colors.gray38
                else -> Colors.gray26
            }
            NVGRenderer.rect(cx, chipY, CHIP_WIDTH, CHIP_HEIGHT, color.rgba, 3f)
            val text = chipLabels[i]
            val tw = NVGRenderer.textWidth(text, 12f, NVGRenderer.defaultFont)
            NVGRenderer.text(
                text, cx + CHIP_WIDTH / 2f - tw / 2f, chipY + 2f, 12f,
                (if (on) Colors.WHITE else Colors.MINECRAFT_GRAY).rgba, NVGRenderer.defaultFont
            )
        }
//#endif
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
//#if CHEAT
        colorEditor?.let { editor ->
            if (editor.mouseClicked(mouseX, mouseY, click)) {
                if (!editor.picking) closeColorEditor() // its header row toggles it shut
                return true
            }
        }

        // Swatch: left-click edits the entry's colour, right-click resets it to the module's default.
        if (mouseY in listY..(listY + listHeight) && mouseX in swatchX..(swatchX + SWATCH)) {
            val name = entries().getOrNull(((mouseY - listY - scrollOffset) / ROW_HEIGHT).toInt())
            val style = name?.let { rowStyle(it, it in activeSet()) }
            if (name != null && style != null) {
                when (click.button()) {
                    0 -> if (colorEntry == name) closeColorEditor() else openColorEditor(name, style)
                    1 -> {
                        style.color = null
                        if (colorEntry == name) closeColorEditor()
                        onStyleChanged()
                    }
                }
                return true
            }
        }

//#endif
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
//#if CHEAT
                val style = rowStyle(name, name in activeSet())
                val chip = if (style == null) -1 else (0..2).firstOrNull { mouseX in chipX(it)..(chipX(it) + CHIP_WIDTH) } ?: -1
                when (chip) {
                    -1 -> toggle(name)
                    0 -> style!!.box = !style.box
                    1 -> style!!.tracer = !style.tracer
                    else -> style!!.label = !style.label
                }
                if (chip >= 0) onStyleChanged()
//#else
//$                toggle(name)
//#endif
                return true
            }
        }
        return true // swallow clicks anywhere else inside the popup so they don't fall through to the panel below
    }

    override fun mouseReleased(click: MouseButtonEvent) {
        input.mouseReleased()
        draggingScrollbar = false
//#if CHEAT
        colorEditor?.let {
            it.mouseReleased(click)
            onStyleChanged() // save once the drag ends, not every frame
        }
//#endif
    }

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Int): Boolean {
        if (mouseY !in listY..(listY + listHeight)) return false
        val contentHeight = entries().size * ROW_HEIGHT
        scrollOffset = (scrollOffset + amount).coerceIn((-contentHeight + listHeight).coerceAtMost(0f), 0f)
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        // Escape closes the popup (ClickGUI) instead of just leaving the always-focused search box.
        if (input.key == GLFW.GLFW_KEY_ESCAPE) return false
        val wasEnter = input.key == GLFW.GLFW_KEY_ENTER
        val handled = this.input.keyPressed(input)
        if (wasEnter) submitNewEntry()
        return handled || wasEnter
    }

    override fun keyTyped(input: CharacterEvent): Boolean = this.input.keyTyped(input)

    companion object {
        private const val TAB_HEIGHT = 24f
        private const val INPUT_HEIGHT = 22f
        private const val ROW_HEIGHT = 24f
        private const val MIN_THUMB_HEIGHT = 20f
//#if CHEAT
        private const val CHIP_WIDTH = 42f
        private const val CHIP_HEIGHT = 16f
        private const val SWATCH = 16f
//#endif
    }
}
