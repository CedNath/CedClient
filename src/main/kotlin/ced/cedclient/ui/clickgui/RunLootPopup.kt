package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.funqol.RunProfit
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.TextInputHandler
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Run Profit's loot history (/cc loot): one row per logged chest, newest first, with a search box and Floor / Mode /
 * Sort buttons (click to cycle). Click a row to see its items under the list.
 */
class RunLootPopup : Popup("Run Loot", WIDTH, HEIGHT) {

    private val all = RunProfit.Log.entries.asReversed().toList()
    private val floors = listOf("All") + all.map { it.floor }.distinct()
        .sortedWith(compareBy({ it.firstOrNull() ?: ' ' }, { it.drop(1).toIntOrNull() ?: 99 }))
    private val modes = listOf("All", "Dungeon", "Kuudra")
    private val sorts = listOf("Newest", "Profit", "Value")
    private var floorIdx = 0
    private var modeIdx = 0
    private var sortIdx = 0

    private var search = ""
    private val input = TextInputHandler(textProvider = { search }, textSetter = { search = it.take(32) })

    private var shown: List<RunProfit.Entry> = all
    private var builtKey = ""
    private var scroll = 0f
    private var selected: RunProfit.Entry? = null
    private val dateFormat = SimpleDateFormat("MMM d  HH:mm")

    init { input.focus() }

    private val rowH = 22f
    private val listTop get() = y + 118f
    private val listHeight get() = height - 118f - 150f
    private val columns = listOf("Date" to 0f, "Floor" to 100f, "Chest" to 160f, "Cost" to 280f, "Value" to 360f, "Profit" to 450f, "Top drop" to 540f)

    private val searchW = 300f
    private val floorX get() = x + 12f + searchW + 8f
    private val modeX get() = floorX + 118f
    private val sortX get() = modeX + 118f
    private val buttonW = 110f

    private fun rebuild() {
        val key = "$search|$floorIdx|$modeIdx|$sortIdx"
        if (key == builtKey) return
        builtKey = key
        scroll = 0f
        val floor = floors[floorIdx]
        val mode = modes[modeIdx]
        var list = all.filter { e ->
            (floor == "All" || e.floor == floor) && (mode == "All" || e.mode == mode) &&
                (search.isBlank() || e.chest.contains(search, true) || e.floor.contains(search, true) || e.items.any { it.name.contains(search, true) })
        }
        list = when (sorts[sortIdx]) {
            "Profit" -> list.sortedByDescending(RunProfit::profitOf)
            "Value" -> list.sortedByDescending(RunProfit::valueOf)
            else -> list
        }
        shown = list
        if (selected !in shown) selected = null
    }

    private fun button(bx: Float, by: Float, label: String, mouseX: Float, mouseY: Float) {
        val font = NVGRenderer.defaultFont
        val hovered = isAreaHovered(bx, by, buttonW, 24f, mouseX, mouseY)
        NVGRenderer.rect(bx, by, buttonW, 24f, (if (hovered) ClickGUI.clickGUIColor else Colors.gray38).rgba, 4f)
        NVGRenderer.text(label, bx + buttonW / 2f - NVGRenderer.textWidth(label, 13f, font) / 2f, by + 5f, 13f, Colors.WHITE.rgba, font)
    }

    private fun cut(text: String, max: Int) = if (text.length > max) text.take(max - 1) + "…" else text

    private fun profitColor(v: Double) = (if (v >= 0) Colors.MINECRAFT_GREEN else Colors.MINECRAFT_RED).rgba

    override fun drawContent(mouseX: Float, mouseY: Float) {
        rebuild()
        val font = NVGRenderer.defaultFont
        val left = x + 16f

        NVGRenderer.rect(x + 12f, y + 40f, searchW, 24f, Colors.INPUT_BG.rgba, 4f)
        NVGRenderer.hollowRect(x + 12f, y + 40f, searchW, 24f, 1f, Colors.INPUT_BORDER.rgba, 4f)
        if (search.isEmpty()) NVGRenderer.text("Search chest, floor or item...", x + 18f, y + 45f, 14f, Colors.MINECRAFT_GRAY.rgba, font)
        input.x = x + 12f; input.y = y + 41f; input.width = searchW; input.height = 22f
        input.draw(mouseX, mouseY)
        button(floorX, y + 40f, "Floor: ${floors[floorIdx]}", mouseX, mouseY)
        button(modeX, y + 40f, "Mode: ${modes[modeIdx]}", mouseX, mouseY)
        button(sortX, y + 40f, "Sort: ${sorts[sortIdx]}", mouseX, mouseY)

        if (shown.isEmpty()) {
            NVGRenderer.text(if (all.isEmpty()) "No chests logged yet. Open a dungeon or Kuudra reward chest." else "Nothing matches.", left, y + 86f, 15f, Colors.WHITE.rgba, font)
            return
        }
        val total = shown.sumOf(RunProfit::profitOf)
        val best = shown.maxOf(RunProfit::profitOf)
        NVGRenderer.text(
            "${shown.size} chests   profit ${RunProfit.signed(total)}   avg ${RunProfit.signed(total / shown.size)}   best ${RunProfit.signed(best)}",
            left, y + 74f, 14f, ClickGUI.clickGUIColor.rgba, font,
        )
        for ((name, cx) in columns) NVGRenderer.text(name, left + cx, y + 98f, 13f, 0xFF999999.toInt(), font)

        NVGRenderer.pushScissor(x, listTop, width, listHeight)
        var rowY = listTop + scroll
        for (e in shown) {
            if (rowY > listTop - rowH && rowY < listTop + listHeight) {
                if (e === selected) NVGRenderer.rect(x + 8f, rowY - 2f, width - 16f, rowH - 2f, Colors.gray38.rgba, 4f)
                val profit = RunProfit.profitOf(e)
                val top = e.items.maxByOrNull { it.value }?.name.orEmpty()
                val cells = listOf(
                    dateFormat.format(Date(e.time)), e.floor, cut(e.chest, 14), RunProfit.short(e.cost),
                    RunProfit.short(RunProfit.valueOf(e)), RunProfit.signed(profit), cut(top, 16),
                )
                cells.forEachIndexed { i, text ->
                    val color = if (i == 5) profitColor(profit) else Colors.WHITE.rgba
                    NVGRenderer.text(text, left + columns[i].second, rowY, 13f, color, font)
                }
            }
            rowY += rowH
        }
        NVGRenderer.popScissor()

        val detailTop = y + height - 140f
        NVGRenderer.rect(x + 8f, detailTop - 4f, width - 16f, 1f, Colors.gray38.rgba)
        val sel = selected
        if (sel == null) {
            NVGRenderer.text("Click a chest to see its items.", left, detailTop + 6f, 13f, Colors.MINECRAFT_GRAY.rgba, font)
            return
        }
        NVGRenderer.text("${sel.mode} ${sel.floor}  ${sel.chest}   cost ${RunProfit.short(sel.cost)}", left, detailTop + 2f, 14f, Colors.WHITE.rgba, font)
        sel.items.sortedByDescending { it.value }.take(10).forEachIndexed { i, item ->
            val col = i / 5
            val ix = left + col * 320f
            val iy = detailTop + 28f + (i % 5) * 20f
            NVGRenderer.text(cut((if (item.count > 1) "${item.count}x " else "") + item.name, 30), ix, iy, 13f, Colors.WHITE.rgba, font)
            val v = RunProfit.short(item.value)
            NVGRenderer.text(v, ix + 300f - NVGRenderer.textWidth(v, 13f, font), iy, 13f, 0xFFFFD700.toInt(), font)
        }
        if (sel.items.size > 10) NVGRenderer.text("+${sel.items.size - 10} more", left, detailTop + 128f, 12f, Colors.MINECRAFT_GRAY.rgba, font)
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (input.mouseClicked(mouseX, mouseY, click)) return true
        if (click.button() != 0) return false
        val by = y + 40f
        if (isAreaHovered(floorX, by, buttonW, 24f, mouseX, mouseY)) { floorIdx = (floorIdx + 1) % floors.size; return true }
        if (isAreaHovered(modeX, by, buttonW, 24f, mouseX, mouseY)) { modeIdx = (modeIdx + 1) % modes.size; return true }
        if (isAreaHovered(sortX, by, buttonW, 24f, mouseX, mouseY)) { sortIdx = (sortIdx + 1) % sorts.size; return true }
        if (mouseY !in listTop..(listTop + listHeight)) return false
        selected = shown.getOrNull(((mouseY - listTop - scroll) / rowH).toInt()) ?: return false
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (input.key == GLFW.GLFW_KEY_ESCAPE) return false
        return this.input.keyPressed(input)
    }

    override fun keyTyped(input: CharacterEvent): Boolean = this.input.keyTyped(input)

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Int): Boolean {
        scroll = (scroll + amount * rowH * 2).coerceIn(minOf(0f, listHeight - shown.size * rowH), 0f)
        return true
    }
}
