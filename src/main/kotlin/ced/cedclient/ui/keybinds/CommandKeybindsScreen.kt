package ced.cedclient.ui.keybinds

import ced.cedclient.features.impl.misc.CommandKeybinds
import ced.cedclient.features.impl.misc.KeyBind
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.state.IslandState
import ced.cedclient.ui.clickgui.ClickGUI
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import ced.cedclient.utils.Keys
import kotlin.math.max

/**
 * Editor for [CommandKeybinds]: categories down the left (click to filter, right-click to delete, "All" shows
 * everything), the binds of that category on the right. Each row: on/off box, key (click, then press a key or
 * middle mouse; Delete clears), command (click to type), category (click = next, right-click = previous), delete.
 */
class CommandKeybindsScreen : Screen(Component.literal("Command Keybinds")) {

    private val font: Font = Minecraft.getInstance().font

    private val winW = 520
    private val winH = 270
    private val catW = 100
    private val headerH = 18
    private val rowH = 20
    private val catRowH = 16
    private val allLabel = "All"

    private var left = 0
    private var top = 0

    /** null = All */
    private var selectedCat: String? = null
    private var scroll = 0

    private var listening: KeyBind? = null
    private var editing: KeyBind? = null
    private var addingCategory = false

    private lateinit var commandField: EditBox
    private lateinit var categoryField: EditBox

    // right-hand column geometry (set in init)
    private var rx = 0
    private var rw = 0
    private var cmdW = 0
    private val keyW = 56
    private val catBtnW = 62
    private val whereW = 70

    /** Bind whose island / phase popup is open. */
    private var whereFor: KeyBind? = null
    private val phaseNames = listOf("P1", "P2", "P3", "P4", "P5")
    private val popW = 380
    private val popH = 170
    private val popCols = 4
    private val popCellW = 92
    private val popCellH = 16

    private val rowsTop get() = top + headerH + 8
    private val rowsBottom get() = top + winH - 30
    private val rowsVisible get() = max(1, (rowsBottom - rowsTop) / rowH)

    override fun init() {
        left = (width - winW) / 2
        top = (height - winH) / 2
        rx = left + catW + 6
        rw = winW - catW - 12
        cmdW = rw - 16 - keyW - 4 - 4 - whereW - 4 - catBtnW - 4 - 12

        commandField = EditBox(font, 0, 0, cmdW, 16, Component.empty()).also { it.setMaxLength(256) }
        categoryField = EditBox(font, left + 6, top + winH - 24, catW - 12, 16, Component.empty()).also { it.setMaxLength(24) }
    }

    private fun visible(): List<KeyBind> =
        CommandKeybinds.binds.filter { selectedCat == null || it.category == selectedCat }

    private fun maxScroll() = max(0, visible().size - rowsVisible)

    // ───────────────────────── geometry helpers ─────────────────────────

    private fun rowY(i: Int) = rowsTop + (i - scroll) * rowH
    private fun cmdX() = rx + 16 + keyW + 4
    private fun whereX() = cmdX() + cmdW + 4
    private fun catBtnX() = whereX() + whereW + 4
    private fun popX() = left + (winW - popW) / 2
    private fun popY() = top + (winH - popH) / 2
    private fun popGridY() = popY() + headerH + 14
    private fun delX() = rx + rw - 12
    private fun newBtnY() = top + winH - 24
    private fun inBox(mx: Int, my: Int, x: Int, y: Int, w: Int, h: Int) = mx in x until x + w && my in y until y + h

    // ───────────────────────── render ─────────────────────────

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        context.fill(0, 0, width, height, 0x90101010.toInt())

        val list = visible()
        scroll = scroll.coerceIn(0, maxScroll())

        NVGSpecialRenderer.draw(context, 0, 0, context.guiWidth(), context.guiHeight(), renderContent = {
            val ratio = NVGRenderer.canvasWidth / context.guiWidth()
            NVGRenderer.scale(ratio, ratio)
            drawChrome(mouseX, mouseY, list)
        })

        if (addingCategory) categoryField.extractRenderState(context, mouseX, mouseY, deltaTicks)
        editing?.let { b ->
            val i = list.indexOf(b)
            if (i in scroll until scroll + rowsVisible) {
                commandField.x = cmdX()
                commandField.y = rowY(i) + 1
                commandField.extractRenderState(context, mouseX, mouseY, deltaTicks)
            }
        }
    }

    private fun drawChrome(mx: Int, my: Int, list: List<KeyBind>) {
        val f = NVGRenderer.defaultFont
        val accent = ClickGUI.clickGUIColor.rgba

        NVGRenderer.dropShadow(left.toFloat(), top.toFloat(), winW.toFloat(), winH.toFloat(), 10f, 2f, 5f)
        NVGRenderer.rect(left.toFloat(), top.toFloat(), winW.toFloat(), winH.toFloat(), 0xEE1A1A1A.toInt(), 5f)
        NVGRenderer.drawHalfRoundedRect(left.toFloat(), top.toFloat(), winW.toFloat(), headerH.toFloat(), accent, 5f, true)
        NVGRenderer.text("Command Keybinds", left + 8f, top + 5f, 8f, 0xFFFFFFFF.toInt(), f)

        val closeHover = inBox(mx, my, left + winW - 16, top + 3, 12, 12)
        NVGRenderer.text("x", left + winW - 13f, top + 5f, 8f, if (closeHover) 0xFFFF5555.toInt() else 0xFFFFFFFF.toInt(), f)

        // category column
        NVGRenderer.rect(left + 4f, top + headerH + 4f, catW - 4f, winH - headerH - 34f, 0xFF222222.toInt(), 4f)
        val cats = listOf<String?>(null) + CommandKeybinds.categories
        cats.forEachIndexed { i, cat ->
            val y = top + headerH + 8 + i * catRowH
            if (y + catRowH > top + winH - 30) return@forEachIndexed
            val sel = cat == selectedCat
            val hover = inBox(mx, my, left + 6, y, catW - 8, catRowH - 2)
            if (sel) NVGRenderer.rect(left + 6f, y.toFloat(), catW - 8f, catRowH - 2f, accent, 3f)
            else if (hover) NVGRenderer.rect(left + 6f, y.toFloat(), catW - 8f, catRowH - 2f, 0xFF333333.toInt(), 3f)
            val count = if (cat == null) CommandKeybinds.binds.size else CommandKeybinds.binds.count { it.category == cat }
            NVGRenderer.text(NVGRenderer.truncate(cat ?: allLabel, catW - 36f, 8f, f), left + 10f, y + 4f, 8f, 0xFFFFFFFF.toInt(), f)
            NVGRenderer.text("$count", left + catW - 18f, y + 4f, 8f, 0xFFAAAAAA.toInt(), f)
        }
        if (addingCategory) {
            NVGRenderer.text("Name, then Enter", left + 6f, top + winH - 34f, 7f, 0xFFAAAAAA.toInt(), f)
        } else {
            button(left + 6, newBtnY(), catW - 12, 16, "+ Category", mx, my)
        }

        // column titles
        NVGRenderer.text("Key", rx + 18f, top + headerH + 1f, 7f, 0xFF888888.toInt(), f)
        NVGRenderer.text("Command", cmdX() + 2f, top + headerH + 1f, 7f, 0xFF888888.toInt(), f)
        NVGRenderer.text("Where", whereX() + 2f, top + headerH + 1f, 7f, 0xFF888888.toInt(), f)
        NVGRenderer.text("Category", catBtnX() + 2f, top + headerH + 1f, 7f, 0xFF888888.toInt(), f)

        // rows
        NVGRenderer.pushScissor(rx.toFloat(), rowsTop.toFloat(), rw.toFloat(), (rowsVisible * rowH).toFloat())
        for (i in scroll until minOf(list.size, scroll + rowsVisible)) {
            val b = list[i]
            val y = rowY(i)
            NVGRenderer.rect(rx.toFloat(), y.toFloat(), rw.toFloat(), rowH - 2f, 0xFF242424.toInt(), 3f)

            // enabled box
            NVGRenderer.rect(rx + 3f, y + 4f, 10f, 10f, if (b.enabled) accent else 0xFF3A3A3A.toInt(), 2f)
            if (b.enabled) NVGRenderer.text("x", rx + 5.5f, y + 4.5f, 8f, 0xFFFFFFFF.toInt(), f)

            // key
            val kx = rx + 16
            val isListening = listening === b
            val kHover = inBox(mx, my, kx, y + 1, keyW, rowH - 4)
            NVGRenderer.rect(kx.toFloat(), y + 1f, keyW.toFloat(), rowH - 4f,
                if (isListening) accent else if (kHover) 0xFF3A3A3A.toInt() else 0xFF2E2E2E.toInt(), 3f)
            NVGRenderer.text(if (isListening) "press..." else NVGRenderer.truncate(CommandKeybinds.keyLabel(b.key), keyW - 8f, 8f, f),
                kx + 4f, y + 5f, 8f, 0xFFFFFFFF.toInt(), f)

            // command
            if (editing !== b) {
                val shown = b.command.ifEmpty { "click to type a command" }
                NVGRenderer.text(NVGRenderer.truncate(shown, cmdW - 6f, 8f, f), cmdX() + 3f, y + 5f, 8f,
                    if (b.command.isEmpty()) 0xFF666666.toInt() else 0xFFDDDDDD.toInt(), f)
            }

            // where (islands / F7-M7 phases)
            val wHover = inBox(mx, my, whereX(), y + 1, whereW, rowH - 4)
            val restricted = b.islands.isNotEmpty() || b.phases.isNotEmpty()
            NVGRenderer.rect(whereX().toFloat(), y + 1f, whereW.toFloat(), rowH - 4f, if (wHover) 0xFF3A3A3A.toInt() else 0xFF2E2E2E.toInt(), 3f)
            NVGRenderer.text(NVGRenderer.truncate(CommandKeybinds.whereLabel(b), whereW - 8f, 8f, f), whereX() + 4f, y + 5f, 8f,
                if (restricted) accent else 0xFF888888.toInt(), f)

            // category
            val cHover = inBox(mx, my, catBtnX(), y + 1, catBtnW, rowH - 4)
            NVGRenderer.rect(catBtnX().toFloat(), y + 1f, catBtnW.toFloat(), rowH - 4f, if (cHover) 0xFF3A3A3A.toInt() else 0xFF2E2E2E.toInt(), 3f)
            NVGRenderer.text(NVGRenderer.truncate(b.category, catBtnW - 8f, 8f, f), catBtnX() + 4f, y + 5f, 8f, 0xFFBBBBBB.toInt(), f)

            // delete
            val dHover = inBox(mx, my, delX(), y + 3, 12, 12)
            NVGRenderer.text("x", delX() + 3f, y + 5f, 8f, if (dHover) 0xFFFF5555.toInt() else 0xFF888888.toInt(), f)
        }
        NVGRenderer.popScissor()

        if (list.isEmpty()) {
            NVGRenderer.text("No binds here yet. Press + New Bind.", rx + 8f, rowsTop + 6f, 8f, 0xFF888888.toInt(), f)
        } else if (maxScroll() > 0) {
            NVGRenderer.text("scroll for more (${list.size} binds)", rx + rw - 110f, rowsBottom + 2f, 7f, 0xFF666666.toInt(), f)
        }

        button(rx, newBtnY(), 90, 16, "+ New Bind", mx, my)
        NVGRenderer.text("Commands start with /, anything else is sent as chat.", rx + 98f, newBtnY() + 4f, 7f, 0xFF666666.toInt(), f)

        whereFor?.let { drawWherePopup(it, mx, my) }
    }

    /** Island + F7/M7 phase picker for one bind. Nothing ticked = no restriction. */
    private fun drawWherePopup(b: KeyBind, mx: Int, my: Int) {
        val f = NVGRenderer.defaultFont
        val accent = ClickGUI.clickGUIColor.rgba
        val px = popX()
        val py = popY()

        NVGRenderer.rect(left.toFloat(), top.toFloat(), winW.toFloat(), winH.toFloat(), 0xAA000000.toInt(), 5f)
        NVGRenderer.dropShadow(px.toFloat(), py.toFloat(), popW.toFloat(), popH.toFloat(), 10f, 2f, 5f)
        NVGRenderer.rect(px.toFloat(), py.toFloat(), popW.toFloat(), popH.toFloat(), 0xFF1E1E1E.toInt(), 5f)
        NVGRenderer.drawHalfRoundedRect(px.toFloat(), py.toFloat(), popW.toFloat(), headerH.toFloat(), accent, 5f, true)
        NVGRenderer.text("Where can it trigger?  (nothing ticked = anywhere)", px + 8f, py + 5f, 8f, 0xFFFFFFFF.toInt(), f)

        NVGRenderer.text("Islands", px + 6f, py + headerH + 3f, 7f, 0xFF888888.toInt(), f)
        IslandState.Island.entries.forEachIndexed { i, island ->
            val x = px + 6 + (i % popCols) * popCellW
            val y = popGridY() + (i / popCols) * popCellH
            val on = island.name in b.islands
            val hover = inBox(mx, my, x, y, popCellW - 2, popCellH - 2)
            NVGRenderer.rect(x.toFloat(), y.toFloat(), popCellW - 2f, popCellH - 2f, if (on) accent else if (hover) 0xFF3A3A3A.toInt() else 0xFF2A2A2A.toInt(), 3f)
            NVGRenderer.text(NVGRenderer.truncate(island.displayName, popCellW - 10f, 7f, f), x + 4f, y + 4f, 7f, 0xFFFFFFFF.toInt(), f)
        }

        val phaseY = popGridY() + ((IslandState.Island.entries.size + popCols - 1) / popCols) * popCellH + 14
        NVGRenderer.text("F7 / M7 phase (only counts inside the boss fight)", px + 6f, phaseY - 11f, 7f, 0xFF888888.toInt(), f)
        phaseNames.forEachIndexed { i, p ->
            val x = px + 6 + i * 48
            val on = p in b.phases
            val hover = inBox(mx, my, x, phaseY, 44, popCellH - 2)
            NVGRenderer.rect(x.toFloat(), phaseY.toFloat(), 44f, popCellH - 2f, if (on) accent else if (hover) 0xFF3A3A3A.toInt() else 0xFF2A2A2A.toInt(), 3f)
            NVGRenderer.text(p, x + 17f, phaseY + 4f, 7f, 0xFFFFFFFF.toInt(), f)
        }

        button(px + popW - 124, phaseY, 56, popCellH - 2, "Clear", mx, my)
        button(px + popW - 62, phaseY, 56, popCellH - 2, "Done", mx, my)
    }

    private fun button(x: Int, y: Int, w: Int, h: Int, label: String, mx: Int, my: Int) {
        val hover = inBox(mx, my, x, y, w, h)
        NVGRenderer.rect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), if (hover) 0xFF3A3A3A.toInt() else 0xFF2A2A2A.toInt(), 3f)
        NVGRenderer.text(label, x + 6f, y + 4f, 8f, 0xFFFFFFFF.toInt(), NVGRenderer.defaultFont)
    }

    // ───────────────────────── mouse ─────────────────────────

    override fun mouseClicked(event: MouseButtonEvent, bl: Boolean): Boolean {
        val mx = event.x().toInt()
        val my = event.y().toInt()
        val button = Keys.mouse(event)

        // waiting for a key: middle mouse binds, left/right click cancel
        listening?.let { b ->
            if (button == 2) { b.key = CommandKeybinds.MOUSE_BASE + Keys.MOUSE_MIDDLE; CommandKeybinds.save() }
            listening = null
            return true
        }

        // island / phase popup swallows every click while it is open
        whereFor?.let { b ->
            val px = popX()
            val phaseY = popGridY() + ((IslandState.Island.entries.size + popCols - 1) / popCols) * popCellH + 14
            if (!inBox(mx, my, px, popY(), popW, popH)) { whereFor = null; return true }
            IslandState.Island.entries.forEachIndexed { i, island ->
                val x = px + 6 + (i % popCols) * popCellW
                val y = popGridY() + (i / popCols) * popCellH
                if (inBox(mx, my, x, y, popCellW - 2, popCellH - 2)) {
                    if (!b.islands.remove(island.name)) b.islands.add(island.name)
                    CommandKeybinds.save()
                }
            }
            phaseNames.forEachIndexed { i, p ->
                if (inBox(mx, my, px + 6 + i * 48, phaseY, 44, popCellH - 2)) {
                    if (!b.phases.remove(p)) b.phases.add(p)
                    CommandKeybinds.save()
                }
            }
            if (inBox(mx, my, px + popW - 124, phaseY, 56, popCellH - 2)) { b.islands.clear(); b.phases.clear(); CommandKeybinds.save() }
            if (inBox(mx, my, px + popW - 62, phaseY, 56, popCellH - 2)) whereFor = null
            return true
        }

        if (inBox(mx, my, left + winW - 16, top + 3, 12, 12)) { onClose(); return true }

        // clicking away from a text field ends the edit
        if (editing != null) {
            if (commandField.mouseClicked(event, bl)) return true
            editing = null
            commandField.setFocused(false)
        }
        if (addingCategory) {
            if (categoryField.mouseClicked(event, bl)) return true
            addingCategory = false
            categoryField.setFocused(false)
        }

        // categories
        val cats = listOf<String?>(null) + CommandKeybinds.categories
        cats.forEachIndexed { i, cat ->
            val y = top + headerH + 8 + i * catRowH
            if (y + catRowH > top + winH - 30) return@forEachIndexed
            if (inBox(mx, my, left + 6, y, catW - 8, catRowH - 2)) {
                if (button == 1 && cat != null && cat != CommandKeybinds.GENERAL) {
                    CommandKeybinds.removeCategory(cat)
                    if (selectedCat == cat) selectedCat = null
                } else if (button == 0) {
                    selectedCat = cat
                    scroll = 0
                }
                return true
            }
        }

        if (inBox(mx, my, left + 6, newBtnY(), catW - 12, 16)) {
            addingCategory = true
            categoryField.value = ""
            categoryField.setFocused(true)
            return true
        }

        if (inBox(mx, my, rx, newBtnY(), 90, 16)) {
            val b = KeyBind(category = selectedCat ?: CommandKeybinds.GENERAL)
            CommandKeybinds.binds.add(b)
            CommandKeybinds.save()
            scroll = maxScroll()
            listening = b // ready to take a key straight away
            return true
        }

        // rows
        val list = visible()
        for (i in scroll until minOf(list.size, scroll + rowsVisible)) {
            val b = list[i]
            val y = rowY(i)
            if (my !in y until y + rowH - 2 || mx !in rx until rx + rw) continue
            when {
                inBox(mx, my, rx + 3, y + 4, 10, 10) -> { b.enabled = !b.enabled; CommandKeybinds.save() }
                inBox(mx, my, rx + 16, y + 1, keyW, rowH - 4) -> listening = b
                inBox(mx, my, cmdX(), y + 1, cmdW, rowH - 4) -> {
                    editing = b
                    commandField.value = b.command
                    commandField.setFocused(true)
                }
                inBox(mx, my, whereX(), y + 1, whereW, rowH - 4) -> whereFor = b
                inBox(mx, my, catBtnX(), y + 1, catBtnW, rowH - 4) -> {
                    val all = CommandKeybinds.categories
                    val at = max(0, all.indexOf(b.category))
                    b.category = all[(at + (if (button == 1) -1 else 1) + all.size) % all.size]
                    CommandKeybinds.save()
                }
                inBox(mx, my, delX(), y + 3, 12, 12) -> { CommandKeybinds.binds.remove(b); CommandKeybinds.save() }
            }
            return true
        }

        return super.mouseClicked(event, bl)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        scroll = (scroll - verticalAmount.toInt()).coerceIn(0, maxScroll())
        return true
    }

    // ───────────────────────── keyboard ─────────────────────────

    override fun keyPressed(event: KeyEvent): Boolean {
        if (whereFor != null) {
            if (event.key == Keys.ESCAPE) whereFor = null
            return true
        }
        listening?.let { b ->
            when (event.key) {
                Keys.ESCAPE -> {}
                Keys.DELETE, Keys.BACKSPACE -> b.key = -1
                else -> b.key = event.key
            }
            CommandKeybinds.save()
            listening = null
            return true
        }

        if (addingCategory) {
            when (event.key) {
                Keys.ENTER, Keys.KP_ENTER -> {
                    val name = categoryField.value
                    if (CommandKeybinds.addCategory(name)) selectedCat = name.trim()
                    addingCategory = false
                    categoryField.setFocused(false)
                }
                Keys.ESCAPE -> { addingCategory = false; categoryField.setFocused(false) }
                else -> categoryField.keyPressed(event)
            }
            return true
        }

        editing?.let { b ->
            when (event.key) {
                Keys.ENTER, Keys.KP_ENTER, Keys.ESCAPE -> { editing = null; commandField.setFocused(false) }
                else -> { commandField.keyPressed(event); b.command = commandField.value; CommandKeybinds.save() }
            }
            return true
        }

        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (addingCategory) { categoryField.charTyped(event); return true }
        editing?.let { b ->
            commandField.charTyped(event)
            b.command = commandField.value
            CommandKeybinds.save()
            return true
        }
        return super.charTyped(event)
    }

    override fun onClose() {
        CommandKeybinds.save()
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false
}
