package ced.cedclient.ui.clickgui

import ced.cedclient.utils.Keys
import ced.cedclient.features.impl.funqol.RunProfit
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.TextInputHandler
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Run Profit's loot history (/cc loot): one row per logged chest, newest first, with a search box and Floor / Mode /
 * Sort buttons (click to cycle). Click a row to see its items under the list.
 */
class RunLootPopup : Popup("Run Loot", WIDTH * K, HEIGHT * K) {
    private companion object { const val K = 1.4f } // everything in here is drawn this much bigger than the other popups


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

    private val rowH = (22f * K)
    private val listTop get() = y + (118f * K)
    private val listHeight get() = height - (118f * K) - (150f * K)
    private val columns = listOf("Date" to 0f, "Floor" to (100f * K), "Chest" to (160f * K), "Cost" to (280f * K), "Value" to (360f * K), "Profit" to (450f * K), "Top drop" to (540f * K))

    private val searchW = (300f * K)
    private val floorX get() = x + (12f * K) + searchW + (8f * K)
    private val modeX get() = floorX + (118f * K)
    private val sortX get() = modeX + (118f * K)
    private val buttonW = (110f * K)

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
        val hovered = isAreaHovered(bx, by, buttonW, (24f * K), mouseX, mouseY)
        NVGRenderer.rect(bx, by, buttonW, (24f * K), (if (hovered) ClickGUI.clickGUIColor else Colors.gray38).rgba, (4f * K))
        NVGRenderer.text(label, bx + buttonW / 2f - NVGRenderer.textWidth(label, (13f * K), font) / 2f, by + (5f * K), (13f * K), Colors.WHITE.rgba, font)
    }

    private fun cut(text: String, max: Int) = if (text.length > max) text.take(max - 1) + "…" else text

    private fun profitColor(v: Double) = (if (v >= 0) Colors.MINECRAFT_GREEN else Colors.MINECRAFT_RED).rgba

    override fun drawContent(mouseX: Float, mouseY: Float) {
        rebuild()
        val font = NVGRenderer.defaultFont
        val left = x + (16f * K)

        NVGRenderer.rect(x + (12f * K), y + (40f * K), searchW, (24f * K), Colors.INPUT_BG.rgba, (4f * K))
        NVGRenderer.hollowRect(x + (12f * K), y + (40f * K), searchW, (24f * K), 1f, Colors.INPUT_BORDER.rgba, (4f * K))
        if (search.isEmpty()) NVGRenderer.text("Search chest, floor or item...", x + (18f * K), y + (45f * K), (14f * K), Colors.MINECRAFT_GRAY.rgba, font)
        input.x = x + (12f * K); input.y = y + (41f * K); input.width = searchW; input.height = (22f * K)
        input.draw(mouseX, mouseY)
        button(floorX, y + (40f * K), "Floor: ${floors[floorIdx]}", mouseX, mouseY)
        button(modeX, y + (40f * K), "Mode: ${modes[modeIdx]}", mouseX, mouseY)
        button(sortX, y + (40f * K), "Sort: ${sorts[sortIdx]}", mouseX, mouseY)

        if (shown.isEmpty()) {
            NVGRenderer.text(if (all.isEmpty()) "No chests logged yet. Open a dungeon or Kuudra reward chest." else "Nothing matches.", left, y + (86f * K), (15f * K), Colors.WHITE.rgba, font)
            return
        }
        val total = shown.sumOf(RunProfit::profitOf)
        val best = shown.maxOf(RunProfit::profitOf)
        NVGRenderer.text(
            "${shown.size} chests   profit ${RunProfit.signed(total)}   avg ${RunProfit.signed(total / shown.size)}   best ${RunProfit.signed(best)}",
            left, y + (74f * K), (14f * K), ClickGUI.clickGUIColor.rgba, font,
        )
        for ((name, cx) in columns) NVGRenderer.text(name, left + cx, y + (98f * K), (13f * K), 0xFF999999.toInt(), font)

        NVGRenderer.pushScissor(x, listTop, width, listHeight)
        var rowY = listTop + scroll
        for (e in shown) {
            if (rowY > listTop - rowH && rowY < listTop + listHeight) {
                if (e === selected) NVGRenderer.rect(x + (8f * K), rowY - 2f, width - (16f * K), rowH - 2f, Colors.gray38.rgba, (4f * K))
                val profit = RunProfit.profitOf(e)
                val top = e.items.maxByOrNull { it.value }?.name.orEmpty()
                val cells = listOf(
                    dateFormat.format(Date(e.time)), e.floor, cut(e.chest, 14), RunProfit.short(e.cost),
                    RunProfit.short(RunProfit.valueOf(e)), RunProfit.signed(profit), cut(top, 16),
                )
                cells.forEachIndexed { i, text ->
                    val color = if (i == 5) profitColor(profit) else Colors.WHITE.rgba
                    NVGRenderer.text(text, left + columns[i].second, rowY, (13f * K), color, font)
                }
            }
            rowY += rowH
        }
        NVGRenderer.popScissor()

        val detailTop = y + height - (140f * K)
        NVGRenderer.rect(x + (8f * K), detailTop - (4f * K), width - (16f * K), 1f, Colors.gray38.rgba)
        val sel = selected
        if (sel == null) {
            NVGRenderer.text("Click a chest to see its items.", left, detailTop + (6f * K), (13f * K), Colors.MINECRAFT_GRAY.rgba, font)
            return
        }
        NVGRenderer.text("${sel.mode} ${sel.floor}  ${sel.chest}   cost ${RunProfit.short(sel.cost)}", left, detailTop + 2f, (14f * K), Colors.WHITE.rgba, font)
        sel.items.sortedByDescending { it.value }.take(10).forEachIndexed { i, item ->
            val col = i / 5
            val ix = left + col * (320f * K)
            val iy = detailTop + (28f * K) + (i % 5) * (20f * K)
            NVGRenderer.text(cut((if (item.count > 1) "${item.count}x " else "") + item.name, 30), ix, iy, (13f * K), Colors.WHITE.rgba, font)
            val v = RunProfit.short(item.value)
            NVGRenderer.text(v, ix + (300f * K) - NVGRenderer.textWidth(v, (13f * K), font), iy, (13f * K), 0xFFFFD700.toInt(), font)
        }
        if (sel.items.size > 10) NVGRenderer.text("+${sel.items.size - 10} more", left, detailTop + (128f * K), (12f * K), Colors.MINECRAFT_GRAY.rgba, font)
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (input.mouseClicked(mouseX, mouseY, click)) return true
        if (Keys.mouse(click) != 0) return false
        val by = y + (40f * K)
        if (isAreaHovered(floorX, by, buttonW, (24f * K), mouseX, mouseY)) { floorIdx = (floorIdx + 1) % floors.size; return true }
        if (isAreaHovered(modeX, by, buttonW, (24f * K), mouseX, mouseY)) { modeIdx = (modeIdx + 1) % modes.size; return true }
        if (isAreaHovered(sortX, by, buttonW, (24f * K), mouseX, mouseY)) { sortIdx = (sortIdx + 1) % sorts.size; return true }
        if (mouseY !in listTop..(listTop + listHeight)) return false
        selected = shown.getOrNull(((mouseY - listTop - scroll) / rowH).toInt()) ?: return false
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (input.key == Keys.ESCAPE) return false
        return this.input.keyPressed(input)
    }

    override fun keyTyped(input: CharacterEvent): Boolean = this.input.keyTyped(input)

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Int): Boolean {
        scroll = (scroll + amount * rowH * 2).coerceIn(minOf(0f, listHeight - shown.size * rowH), 0f)
        return true
    }
}
