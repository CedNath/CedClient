package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.render.EntityESP
import ced.cedclient.ui.inventory.editor.Popup
import ced.cedclient.ui.inventory.editor.mouseClicked
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.max

abstract class EntityCheckListPopup(private val title: String) : Popup {

    protected val mc = Minecraft.getInstance()
    protected val font: Font = mc.font

    var x: Int = 220
    var y: Int = 60
    val width: Int = 300
    val height: Int = 420

    init {
        // spawn on the right side of the screen instead of the old fixed left-ish default
        x = mc.window.guiScaledWidth - width - 20
        y = 60
    }

    private val headerHeight = 26
    private val searchHeight = 22
    private val footerHeight = 20
    private val rowHeight = 14
    private val categoryHeight = 16
    private val bodyTop get() = y + headerHeight + searchHeight
    private val bodyBottom get() = y + height - footerHeight

    private val searchField = EditBox(font, 0, 0, width - 24, 14, Component.empty())

    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    private var scrollOffset = 0 // pixels

    private val expanded = mutableMapOf<String, Boolean>()

    protected abstract fun groupsProvider(): List<Pair<String, List<String>>>

    private fun isChecked(name: String): Boolean =
        EntityESP.onlyNames.any { it.equals(name, ignoreCase = true) }

    private fun toggle(name: String) {
        if (isChecked(name)) EntityESP.unOnlyName(name) else EntityESP.onlyName(name)
    }

    private sealed class Row {
        data class CategoryRow(val category: String, val items: List<String>) : Row()
        data class ItemRow(val name: String) : Row()
    }

    private fun buildRows(filterText: String): List<Row> {
        val rows = mutableListOf<Row>()
        for ((category, items) in groupsProvider()) {
            val filteredItems = if (filterText.isBlank()) items
            else items.filter { it.contains(filterText, ignoreCase = true) }

            if (filterText.isNotBlank() && filteredItems.isEmpty()) continue

            rows += Row.CategoryRow(category, items)
            if (expanded.getOrPut(category) { true }) {
                for (item in filteredItems) rows += Row.ItemRow(item)
            }
        }
        return rows
    }

    override fun render(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        g.fill(x, y, x + width, y + height, 0xEE1A1A1A.toInt())
        g.fill(x, y, x + width, y + headerHeight, 0xFF7A3AFF.toInt())
        g.text(font, title, x + 10, y + 8, 0xFFFFFFFF.toInt())

        val closeHover = mouseX in (x + width - 18)..(x + width - 6) && mouseY in (y + 6)..(y + 18)
        g.text(font, "x", x + width - 14, y + 8, if (closeHover) 0xFFFF5555.toInt() else 0xFFDDDDDD.toInt())

        searchField.x = x + 10
        searchField.y = y + headerHeight + 4
        searchField.extractRenderState(g, mouseX, mouseY, delta)

        val rows = buildRows(searchField.value.trim())
        val contentHeight = rows.sumOf { if (it is Row.CategoryRow) categoryHeight else rowHeight }
        val maxScroll = max(0, contentHeight - (bodyBottom - bodyTop))
        scrollOffset = scrollOffset.coerceIn(0, maxScroll)

        var rowY = bodyTop - scrollOffset
        for (row in rows) {
            val rh = if (row is Row.CategoryRow) categoryHeight else rowHeight
            if (rowY + rh >= bodyTop && rowY <= bodyBottom) {
                when (row) {
                    is Row.CategoryRow -> drawCategoryRow(g, row, rowY)
                    is Row.ItemRow -> drawItemRow(g, row, rowY)
                }
            }
            rowY += rh
        }

        if (rows.none { it is Row.ItemRow } && searchField.value.isNotBlank()) {
            g.text(font, "No matches \u2014 press Enter to filter by \"${searchField.value.trim()}\" anyway", x + 10, bodyTop + 4, 0xFF888888.toInt())
        }

        // footer
        val footerY = y + height - footerHeight
        g.fill(x, footerY, x + width, y + height, 0xFF151515.toInt())

        val onlyCount = EntityESP.onlyNames.size
        g.text(font, "Active filters: $onlyCount", x + 10, footerY + 6, 0xFF999999.toInt())

        val clearHover = mouseX in (x + width - 60)..(x + width - 10) && mouseY in (footerY + 2)..(footerY + 14)
        g.text(font, "[Clear]", x + width - 58, footerY + 6, if (clearHover) 0xFFFF5555.toInt() else 0xFFCCCCCC.toInt())
    }

    private fun drawCategoryRow(g: GuiGraphicsExtractor, row: Row.CategoryRow, rowY: Int) {
        g.fill(x + 6, rowY + 2, x + width - 6, rowY + categoryHeight - 2, 0xFF262626.toInt())

        val allChecked = row.items.isNotEmpty() && row.items.all { isChecked(it) }
        val boxColor = if (allChecked) 0xFF66CC66.toInt() else 0xFF555555.toInt()
        g.fill(x + 10, rowY + 4, x + 18, rowY + 12, boxColor)

        val arrow = if (expanded.getOrPut(row.category) { true }) "-" else "+"
        g.text(font, arrow, x + width - 20, rowY + 2, 0xFFAAAAAA.toInt())

        g.text(font, "${row.category} (${row.items.size})", x + 24, rowY + 2, 0xFFCCCCCC.toInt())
    }

    private fun drawItemRow(g: GuiGraphicsExtractor, row: Row.ItemRow, rowY: Int) {
        val checked = isChecked(row.name)
        val boxColor = if (checked) 0xFF66CC66.toInt() else 0xFF3A3A3A.toInt()
        g.fill(x + 20, rowY + 2, x + 28, rowY + 10, boxColor)
        g.outline(x + 20, rowY + 2, 8, 8, 0xFF888888.toInt())

        g.text(font, row.name, x + 34, rowY, 0xFFDDDDDD.toInt())
    }

    override fun mouseClicked(mx: Int, my: Int, button: Int): Boolean {
        if (!isInside(mx, my)) return false

        if (my in y..(y + headerHeight) && mx < x + width - 18) {
            dragging = true
            dragOffsetX = mx - x
            dragOffsetY = my - y
            return true
        }

        if (mx in (x + width - 18)..(x + width - 6) && my in (y + 6)..(y + 18)) {
            ced.cedclient.ui.clickgui.ClickGUIPopupHost.close()
            return true
        }

        if (searchField.mouseClicked(mx, my, button)) {
            searchField.setFocused(true)
            return true
        }

        // footer "Clear" button
        val footerY = y + height - footerHeight
        if (my in (footerY + 2)..(footerY + 14) && mx in (x + width - 60)..(x + width - 10)) {
            EntityESP.clearOnly()
            return true
        }

        val rows = buildRows(searchField.value.trim())
        var rowY = bodyTop - scrollOffset
        for (row in rows) {
            val rh = if (row is Row.CategoryRow) categoryHeight else rowHeight
            val visible = rowY + rh >= bodyTop && rowY <= bodyBottom
            if (visible && my in rowY..(rowY + rh)) {
                when (row) {
                    is Row.CategoryRow -> {
                        if (mx in (x + 10)..(x + 18)) {
                            val allChecked = row.items.isNotEmpty() && row.items.all { isChecked(it) }
                            for (item in row.items) {
                                if (allChecked) {
                                    if (isChecked(item)) EntityESP.unOnlyName(item)
                                } else {
                                    if (!isChecked(item)) EntityESP.onlyName(item)
                                }
                            }
                        } else {
                            expanded[row.category] = !(expanded.getOrPut(row.category) { true })
                        }
                        return true
                    }
                    is Row.ItemRow -> {
                        toggle(row.name)
                        return true
                    }
                }
            }
            rowY += rh
        }

        return true
    }

    override fun mouseDragged(mx: Int, my: Int, button: Int, dx: Double, dy: Double): Boolean {
        if (dragging) {
            x = mx - dragOffsetX
            y = my - dragOffsetY
            return true
        }
        return false
    }

    override fun mouseReleased(mx: Int, my: Int, button: Int): Boolean {
        dragging = false
        return true
    }

    override fun mouseScrolled(mx: Int, my: Int, scrollX: Double, scrollY: Double): Boolean {
        if (!isInside(mx, my)) return false
        scrollOffset -= (scrollY * rowHeight * 2).toInt()
        if (scrollOffset < 0) scrollOffset = 0
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (!searchField.isFocused) return false

        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            val typed = searchField.value.trim()
            if (typed.isNotEmpty()) {
                // stored as-is; EntityESP.scan() now matches this as a substring
                // against each entity's full display name, so partial text like
                // "frozille" keeps matching even as a player's health/level suffix changes
                EntityESP.onlyName(typed)
                searchField.value = ""
            }
            return true
        }

        return searchField.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (searchField.isFocused) return searchField.charTyped(event)
        return false
    }

    override fun isInside(mx: Int, my: Int): Boolean {
        return mx in x..(x + width) && my in y..(y + height)
    }
}