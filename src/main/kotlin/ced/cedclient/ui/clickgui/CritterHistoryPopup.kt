package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.funqol.CritterSafari
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Critter Safari "Run History": totals over every saved run, then one row per run (newest first) with its
 * length, species caught by the party and by you, shards and what they're worth at today's bazaar price.
 */
class CritterHistoryPopup : Popup("Critter Safari Runs", WIDTH, HEIGHT) {

    private var scroll = 0f
    private val runs = CritterSafari.RunHistory.runs.asReversed().toList()
    private val dateFormat = SimpleDateFormat("MMM d  HH:mm")

    private val listTop get() = y + 96f
    private val listHeight get() = height - 106f

    private val columns = listOf("Date" to 0f, "Time" to 130f, "Party" to 200f, "You" to 270f, "Shards" to 330f, "Value" to 410f, "Best catches" to 490f)

    private fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun drawContent(mouseX: Float, mouseY: Float) {
        val font = NVGRenderer.defaultFont
        val left = x + 16f
        val total = CritterSafari.critterCount()

        if (runs.isEmpty()) {
            NVGRenderer.text("No runs saved yet.", left, y + 50f, 15f, Colors.WHITE.rgba, font)
            return
        }
        val times = runs.map { it.ended - it.started }.filter { it > 0 }
        val summary = "${runs.size} runs   avg ${duration(times.average().toLong())}   best ${duration(times.minOrNull() ?: 0)}   " +
            "${runs.sumOf { it.totalShards }} shards   ${CritterSafari.formatCoins(runs.sumOf { CritterSafari.runValue(it.shards) })}"
        NVGRenderer.text(summary, left, y + 44f, 14f, ClickGUI.clickGUIColor.rgba, font)
        for ((name, cx) in columns) NVGRenderer.text(name, left + cx, y + 72f, 13f, Colors.gray38.rgba or 0xFF999999.toInt(), font)

        NVGRenderer.pushScissor(x, listTop, width, listHeight)
        var rowY = listTop + scroll
        for (r in runs) {
            if (rowY > listTop - 22f && rowY < listTop + listHeight) {
                val party = (r.own.keys + r.shared.keys).size
                val best = (r.own.keys + r.shared.keys).mapNotNull(CritterSafari::critterNamed)
                    .sortedByDescending { it.rarity.ordinal }.take(3).joinToString(", ") { it.name }
                val cells = listOf(
                    dateFormat.format(Date(r.started)), duration(r.ended - r.started), "$party/$total", "${r.own.size}",
                    "${r.totalShards}", CritterSafari.formatCoins(CritterSafari.runValue(r.shards)), best,
                )
                cells.forEachIndexed { i, text -> NVGRenderer.text(text, left + columns[i].second, rowY, 13f, Colors.WHITE.rgba, font) }
            }
            rowY += 22f
        }
        NVGRenderer.popScissor()
    }

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Int): Boolean {
        val content = runs.size * 22f
        scroll = (scroll + amount * 44f).coerceIn(minOf(0f, listHeight - content), 0f)
        return true
    }
}
