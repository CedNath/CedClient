package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.mixin.accessor.PlayerTabOverlayAccessor
import ced.cedclient.utils.PingTracker
import ced.cedclient.utils.TabListCache
import ced.cedclient.utils.TpsTracker
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.components.PlayerTabOverlay
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.world.scores.DisplaySlot
import java.util.Optional
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Compact custom tab list. Hypixel packs every column into player-list
 * entries ordered column-major by `!A-a`/`!B-a` sort keys, so entries are
 * rendered verbatim into a translucent panel rather than re-deriving data.
 * Adds a header stat bar and footer. Cancels vanilla's PlayerTabOverlay
 * render (see PlayerTabOverlayMixin) and draws this instead.
 */
object CompactTab : Module(
    "Compact Tab",
    Category.Render,
    "Reorganizes Hypixel's tab list into a compact panel with a stat bar",
    defaultEnabled = true
) {

    private val opacity = registerSetting(NumberSetting("Panel Opacity", 65.0, 0.0, 100.0, 1.0))
    private val statBar = registerSetting(BooleanSetting("Stat Bar", true))
    private val statBarPos = registerSetting(DropdownSetting("Stat Bar Position", listOf("TOP", "BOTTOM", "LEFT", "RIGHT"), "TOP"))
    private val effectsPanel = registerSetting(
        BooleanSetting(
            "Effects Panel", true,
            "Shows the Active Effects / Cookie Buff info from Hypixel's tab footer in a compact panel under the tab list"
        )
    )

    private const val BG_RGB = 0x0B0D13
    private fun bgPanel(): Int {
        val a = (opacity.value.coerceIn(0.0, 100.0) * 2.55).roundToInt()
        return (a shl 24) or BG_RGB
    }

    private const val BORDER = 0x66303440
    private const val DIVIDER = 0x44454a58
    private const val LABEL = 0xFF8A8F9C.toInt()
    private const val VALUE = 0xFF55FF55.toInt()
    private const val GOLD = 0xFFFFD700.toInt()
    private const val NAME = 0xFFE8ECF2.toInt()
    private const val BAR_ON = 0xFF55E05A.toInt()
    private const val BAR_OFF = 0x55202530

    private val COL_KEY: Pattern = Pattern.compile("^!([A-Za-z])")
    private val SERVER_ID: Pattern = Pattern.compile("\\b((?:mini|mega|m)\\d+[A-Za-z]{1,3})\\b")

    init {
        // Drives the real-ping probe (PingTracker) -- only while the module is on.
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (isEnabled) PingTracker.tick(client)
        }
        // Don't carry the previous server's ping/TPS over to the next one.
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            PingTracker.reset()
            TpsTracker.reset()
        }
    }

    private var shouldRenderVersion = -1
    private var shouldRenderCached = false

    @JvmStatic
    fun shouldRender(): Boolean {
        if (!isEnabled) return false
        val mc = Minecraft.getInstance()
        if (mc.player == null || mc.connection == null) return false
        val v = TabListCache.version
        if (v == shouldRenderVersion) return shouldRenderCached
        shouldRenderVersion = v
        shouldRenderCached = TabListCache.entries.any { COL_KEY.matcher(it.info.profile.name).find() }
        return shouldRenderCached
    }

    /** Best-effort TPS hook - wire this up to your server's TPS source (e.g. a party-chat parser) if you have one. */
    private fun currentTps(): Double = TpsTracker.currentTps()

    @JvmStatic
    fun render(overlay: PlayerTabOverlay, ctx: GuiGraphicsExtractor, screenW: Int) {
        val mc = Minecraft.getInstance()
        if (mc.player == null || mc.connection == null) return
        val tr = mc.font
        val lh = 10

        val acc = overlay as PlayerTabOverlayAccessor
        val header = acc.`ced$getHeader`()?.string
        val footerComp = acc.`ced$getFooter`()
        val footer = footerComp?.string

        val mdl = model(mc, overlay, header, footer, footerComp) ?: return
        val columns = mdl.columns
        val colWidths = mdl.colWidths
        val rows = mdl.rows

        val ping = realPing(mc)
        val fps = mc.fps
        val tps = currentTps()
        val server = mdl.server

        val labels = arrayOf("SERVER", "TPS", "FPS", "PING")
        val values = arrayOf(
            server,
            if (tps < 0) "-" else String.format("%.2f", tps),
            fps.toString(),
            if (ping < 0) "-" else "${ping}ms"
        )
        fun valueColor(i: Int) = if (i == 1 && tps in 0.0..18.99) 0xFFFF5555.toInt() else VALUE

        val pad = 8
        val gap = 8
        var contentW = 0
        for (w in colWidths) contentW += w
        contentW += gap * (columns.size - 1)
        val bodyH = rows * lh + 6
        val footH = 12
        val topPad = 8
        val tabW = contentW + pad * 2
        val tabH = topPad + bodyH + footH
        val boxGap = 8

        if (!statBar.value) {
            val w = min(screenW - 12, tabW)
            val x0 = (screenW - w) / 2
            val y0 = 4
            roundRect(ctx, x0, y0, x0 + w, y0 + tabH, bgPanel())
            drawColumns(ctx, overlay, tr, columns, colWidths, x0 + pad, y0 + topPad, rows, gap, lh)
            centeredText(ctx, tr, mdl.footer, x0 + w / 2, y0 + tabH - footH + 2, GOLD)
            drawEffects(ctx, tr, mdl, x0, w, y0 + tabH + boxGap / 2)
            return
        }

        val pos = statBarPos.value.uppercase()
        if (pos == "LEFT" || pos == "RIGHT") {
            val statLineH = 14
            var statW = 0
            for (i in labels.indices) statW = max(statW, tr.width("\u00A77" + labels[i] + " " + values[i]))
            statW += 16
            val statH = tabH

            val totalW = tabW + boxGap + statW
            val w = min(screenW - 12, totalW)
            val x0 = (screenW - w) / 2
            val y0 = 4
            val tabX0 = if (pos == "LEFT") x0 + statW + boxGap else x0
            val statX0 = if (pos == "LEFT") x0 else x0 + tabW + boxGap

            roundRect(ctx, tabX0, y0, tabX0 + tabW, y0 + tabH, bgPanel())
            drawColumns(ctx, overlay, tr, columns, colWidths, tabX0 + pad, y0 + topPad, rows, gap, lh)
            centeredText(ctx, tr, mdl.footer, tabX0 + tabW / 2, y0 + tabH - footH + 2, GOLD)

            roundRect(ctx, statX0, y0, statX0 + statW, y0 + statH, bgPanel())
            val cellH = statH / labels.size
            for (i in labels.indices) {
                val cellTop = y0 + i * cellH
                val sy = cellTop + (cellH - statLineH) / 2 + 3
                if (i > 0) ctx.fill(statX0 + 6, cellTop, statX0 + statW - 6, cellTop + 1, DIVIDER)
                ctx.text(tr, "\u00A77" + labels[i] + " ", statX0 + 8, sy, LABEL)
                val lw = tr.width("\u00A77" + labels[i] + " ")
                ctx.text(tr, values[i], statX0 + 8 + lw, sy, valueColor(i))
            }
            drawEffects(ctx, tr, mdl, x0, w, y0 + tabH + boxGap / 2)
        } else {
            val statBarH = 32
            val gapTB = 4
            val w = min(screenW - 12, tabW)
            val x0 = (screenW - w) / 2
            val y0 = 4
            val bottom = pos == "BOTTOM"
            val tabY0 = if (bottom) y0 else y0 + statBarH + gapTB
            val statY0 = if (bottom) tabY0 + tabH + gapTB else y0

            roundRect(ctx, x0, tabY0, x0 + w, tabY0 + tabH, bgPanel())
            drawColumns(ctx, overlay, tr, columns, colWidths, x0 + pad, tabY0 + topPad, rows, gap, lh)
            centeredText(ctx, tr, mdl.footer, x0 + w / 2, tabY0 + tabH - footH + 2, GOLD)

            roundRect(ctx, x0, statY0, x0 + w, statY0 + statBarH, bgPanel())
            val cellW = w / labels.size
            for (i in labels.indices) {
                val cxL = x0 + i * cellW
                if (i > 0) ctx.fill(cxL, statY0 + 6, cxL + 1, statY0 + statBarH - 6, DIVIDER)
                val cxC = cxL + cellW / 2
                centeredText(ctx, tr, "\u00A77" + labels[i], cxC, statY0 + 7, LABEL)
                centeredText(ctx, tr, values[i], cxC, statY0 + 18, valueColor(i))
            }
            val groupBottom = if (bottom) statY0 + statBarH else tabY0 + tabH
            drawEffects(ctx, tr, mdl, x0, w, groupBottom + gapTB)
        }
    }

    /**
     * Slim effects bar under the tab list: same width as the tab group, one cell per effect
     * ("God Potion: 2 days", "Cookie Buff: 2 hours", ...) spread evenly across it with dividers,
     * like the stat bar. If there are too many to fit on one line they wrap onto extra rows.
     */
    private fun drawEffects(ctx: GuiGraphicsExtractor, tr: Font, mdl: Model, x0: Int, w: Int, topY: Int) {
        if (!effectsPanel.value) return
        val entries = mdl.effects
        if (entries.isEmpty()) return

        val pad = 8
        val minGap = 16
        val rowPitch = 12
        val innerW = w - pad * 2

        val rows = ArrayList<List<Component>>()
        var cur = ArrayList<Component>()
        var curW = 0
        for (e in entries) {
            val ew = tr.width(e)
            if (cur.isNotEmpty() && curW + minGap + ew > innerW) {
                rows.add(cur)
                cur = ArrayList()
                curW = 0
            }
            curW += if (cur.isEmpty()) ew else minGap + ew
            cur.add(e)
        }
        if (cur.isNotEmpty()) rows.add(cur)

        val panelH = rows.size * rowPitch + 6
        roundRect(ctx, x0, topY, x0 + w, topY + panelH, bgPanel())

        for ((ri, row) in rows.withIndex()) {
            val cellW = w / row.size
            val ty = topY + 4 + ri * rowPitch
            for ((ci, entry) in row.withIndex()) {
                val cellX = x0 + ci * cellW
                if (ci > 0) ctx.fill(cellX, ty - 1, cellX + 1, ty + 9, DIVIDER)
                val ew = tr.width(entry)
                ctx.text(tr, entry, cellX + (cellW - ew) / 2, ty, NAME)
            }
        }
    }

    private class Model(
        val columns: List<List<PlayerInfo>>,
        val colWidths: List<Int>,
        val rows: Int,
        val server: String,
        val footer: String,
        val effects: List<Component>,
    )

    private var modelVersion = -1
    private var modelHeaderFooterSig = ""
    private var cachedModel: Model? = null

    /** Rebuilt only when TabListCache.version bumps or the header/footer text changes -- avoids
     *  re-sorting/re-grouping every single frame while Tab is held. */
    private fun model(mc: Minecraft, overlay: PlayerTabOverlay, header: String?, footer: String?, footerComp: Component?): Model? {
        val v = TabListCache.version
        val hf = (header ?: "") + "\u0000" + (footer ?: "")
        val cached = cachedModel
        if (cached != null && v == modelVersion && hf == modelHeaderFooterSig) return cached
        modelVersion = v
        modelHeaderFooterSig = hf
        cachedModel = buildModel(mc, overlay, header, footer, footerComp)
        return cachedModel
    }

    private fun buildModel(mc: Minecraft, overlay: PlayerTabOverlay, header: String?, footer: String?, footerComp: Component?): Model? {
        val tr = mc.font
        val all = ArrayList(TabListCache.entries.map { it.info })
        all.sortWith(Comparator { a, b -> a.profile.name.compareTo(b.profile.name, ignoreCase = true) })
        val grouped = LinkedHashMap<String, MutableList<PlayerInfo>>()
        for (e in all) {
            val m = COL_KEY.matcher(e.profile.name)
            if (m.find()) grouped.getOrPut(m.group(1).uppercase()) { ArrayList() }.add(e)
        }
        if (grouped.isEmpty()) return null

        val columns = ArrayList<List<PlayerInfo>>()
        val colWidths = ArrayList<Int>()
        var rows = 0
        var first = true
        for (col in grouped.values) {
            var last = -1
            var nonBlank = 0
            for (i in col.indices) if (!blank(overlay, col[i])) {
                last = i
                nonBlank++
            }
            if (last < 0 || nonBlank <= 1) {
                first = false
                continue
            }
            val trimmed = ArrayList(col.subList(0, last + 1))
            val playersCol = first
            first = false
            var maxW = 0
            for (e in trimmed) maxW = max(maxW, tr.width(overlay.getNameForDisplay(e)))
            val extra = if (playersCol) 26 else 8
            val w = max(if (playersCol) 116 else 60, min(maxW + extra, 230))
            columns.add(trimmed)
            colWidths.add(w)
            rows = max(rows, trimmed.size)
        }
        if (columns.isEmpty()) return null
        rows = min(rows, 22)

        return Model(columns, colWidths, rows, findServer(mc, footer, header), footerLine(footer), effectEntries(footerComp))
    }

    private fun drawColumns(
        ctx: GuiGraphicsExtractor, overlay: PlayerTabOverlay, tr: Font,
        columns: List<List<PlayerInfo>>, colWidths: List<Int>,
        startX: Int, cy: Int, rows: Int, gap: Int, lh: Int
    ) {
        var colX = startX
        for (c in columns.indices) {
            val w = colWidths[c]
            if (c > 0) ctx.fill(colX - gap / 2, cy, colX - gap / 2 + 1, cy + rows * lh, DIVIDER)
            val playersCol = c == 0
            val entries = columns[c]
            var r = 0
            while (r < entries.size && r < rows) {
                val e = entries[r]
                val dn = overlay.getNameForDisplay(e)
                val ry = cy + r * lh
                var tx = colX
                if (playersCol && r > 0) {
                    try {
                        PlayerFaceExtractor.extractRenderState(ctx, e.skin.body().texturePath(), colX, ry - 1, 8, e.showHat(), false, -1)
                    } catch (ignored: Exception) {
                    }
                    tx = colX + 10
                }
                ctx.text(tr, dn, tx, ry, NAME)
                if (playersCol && r > 0 && e.latency > 0) drawSignal(ctx, colX + w - 13, ry, e.latency)
                r++
            }
            colX += w + gap
        }
    }

    private val BLANK_COLOR: Pattern = Pattern.compile("\u00A7.")
    private val BLANK_INVISIBLE: Pattern = Pattern.compile("[\\p{Cf}\\p{Z}\\s]")

    private fun blank(overlay: PlayerTabOverlay, e: PlayerInfo): Boolean {
        val dn = overlay.getNameForDisplay(e).string
        val s = BLANK_INVISIBLE.matcher(BLANK_COLOR.matcher(dn).replaceAll("")).replaceAll("")
        return s.isEmpty()
    }

    private fun centeredText(ctx: GuiGraphicsExtractor, tr: Font, text: String, cx: Int, y: Int, color: Int) {
        ctx.text(tr, text, cx - tr.width(text) / 2, y, color)
    }

    private fun roundRect(ctx: GuiGraphicsExtractor, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        ctx.fill(x1 + 2, y1, x2 - 2, y1 + 1, color)
        ctx.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 2, color)
        ctx.fill(x1, y1 + 2, x2, y2 - 2, color)
        ctx.fill(x1 + 1, y2 - 2, x2 - 1, y2 - 1, color)
        ctx.fill(x1 + 2, y2 - 1, x2 - 2, y2, color)
    }

    private fun drawSignal(ctx: GuiGraphicsExtractor, x: Int, y: Int, latency: Int) {
        val filled = if (latency <= 75) 4 else if (latency <= 150) 3 else if (latency <= 300) 2 else 1
        for (b in 0 until 4) {
            val h = 2 + b * 2
            val bx = x + b * 3
            ctx.fill(bx, y + 8 - h, bx + 2, y + 8, if (b < filled) BAR_ON else BAR_OFF)
        }
    }

    /** PingTracker's ping/pong round trip is freshest; falls back to tab latency, then server-list ping. */
    private fun realPing(mc: Minecraft): Int {
        val live = PingTracker.latest()
        if (live > 0) return live
        try {
            val self = mc.connection?.getPlayerInfo(mc.player!!.uuid)
            if (self != null && self.latency > 1) return self.latency // Hypixel pins this at 1, so ignore it
        } catch (ignored: Exception) {
        }
        try {
            val si = mc.currentServer
            if (si != null && si.ping > 0) return si.ping.toInt()
        } catch (ignored: Exception) {
        }
        return -1
    }

    private fun findServer(mc: Minecraft, footer: String?, header: String?): String {
        var hay = (footer ?: "") + " " + (header ?: "")
        try {
            if (mc.level != null) {
                val sb = mc.level!!.scoreboard
                val obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR)
                if (obj != null) for (en in sb.listPlayerScores(obj)) {
                    val team = sb.getPlayersTeam(en.owner())
                    val raw = if (team != null) team.playerPrefix.string + en.owner() + team.playerSuffix.string else en.ownerName().string
                    hay += " " + raw.replace(Regex("\u00A7."), "")
                }
            }
        } catch (ignored: Exception) {
        }
        val m = SERVER_ID.matcher(hay)
        return if (m.find()) m.group(1) else "-"
    }

    /** Splits a (styled) component into lines at '\n', keeping each piece's colors/formatting. */
    private fun splitLines(c: Component): List<Component> {
        val lines = ArrayList<MutableComponent>()
        var cur: MutableComponent = Component.empty()
        c.visit(FormattedText.StyledContentConsumer<Unit> { style, text ->
            val parts = text.split("\n")
            for ((i, part) in parts.withIndex()) {
                if (i > 0) {
                    lines.add(cur)
                    cur = Component.empty()
                }
                if (part.isNotEmpty()) cur.append(Component.literal(part).setStyle(style))
            }
            Optional.empty()
        }, Style.EMPTY)
        lines.add(cur)
        return lines
    }

    /** The part of [c] between plain-text offsets [from, to), keeping each piece's colors/formatting. */
    private fun slice(c: Component, from: Int, to: Int): Component {
        val out: MutableComponent = Component.empty()
        var pos = 0
        c.visit(FormattedText.StyledContentConsumer<Unit> { style, text ->
            val start = max(from, pos)
            val end = min(to, pos + text.length)
            if (start < end) out.append(Component.literal(text.substring(start - pos, end - pos)).setStyle(style))
            pos += text.length
            Optional.empty()
        }, Style.EMPTY)
        return out
    }

    private val GOD_POT: Pattern = Pattern.compile("^You have an? (.+?) active!\\s*(.*)$")
    private val NON_GOD: Pattern = Pattern.compile("^You have (\\d+) non-god effects?\\.?$")

    private fun entry(label: Component, value: Component): Component {
        if (value.string.isBlank()) return label
        return Component.empty()
            .append(label)
            .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
            .append(value)
    }

    /** One footer line from the "Active Effects" block -> one compact "Name: time" entry (or null to drop it). */
    private fun parseEffectLine(line: Component): Component? {
        val raw = line.string
        val lead = raw.length - raw.trimStart().length
        val text = raw.trim()
        if (text.isEmpty() || text.contains("/effects")) return null

        val god = GOD_POT.matcher(text)
        if (god.matches()) {
            return entry(
                slice(line, lead + god.start(1), lead + god.end(1)),
                slice(line, lead + god.start(2), lead + god.end(2))
            )
        }
        val nonGod = NON_GOD.matcher(text)
        if (nonGod.matches()) {
            return entry(
                Component.literal("Effects").withStyle(ChatFormatting.GRAY),
                slice(line, lead + nonGod.start(1), lead + nonGod.end(1))
            )
        }
        // generic "<name> <time>", e.g. "Celestial Mason Jar I 6d"
        val sp = text.lastIndexOf(' ')
        if (sp > 0 && text.substring(sp + 1).any { it.isDigit() }) {
            return entry(slice(line, lead, lead + sp), slice(line, lead + sp + 1, lead + text.length))
        }
        return slice(line, lead, lead + text.length)
    }

    /**
     * Footer -> compact entries. Sections are split on blank lines: the "Active Effects" block
     * becomes one entry per line (God Potion, effect count, jars, ...), every other block
     * (e.g. "Cookie Buff") becomes "Header: first sentence of its body". Hypixel store/server
     * brand lines and the "/effects" hint are dropped.
     */
    private fun effectEntries(footer: Component?): List<Component> {
        if (footer == null) return emptyList()

        val sections = ArrayList<List<Component>>()
        var cur = ArrayList<Component>()
        for (line in splitLines(footer)) {
            val plain = BLANK_COLOR.matcher(line.string).replaceAll("")
            val upper = plain.uppercase()
            val isBlank = BLANK_INVISIBLE.matcher(plain).replaceAll("").isEmpty()
            // drops the store advert and the server brand line (e.g. ALPHA.HYPIXEL.NET) -- useless info
            if (!isBlank && (upper.contains("HYPIXEL.NET") || upper.contains("RANKS, BOOSTERS"))) continue
            if (isBlank) {
                if (cur.isNotEmpty()) {
                    sections.add(cur)
                    cur = ArrayList()
                }
            } else {
                cur.add(line)
            }
        }
        if (cur.isNotEmpty()) sections.add(cur)

        val out = ArrayList<Component>()
        for (sec in sections) {
            val header = sec[0]
            val headerRaw = header.string
            val headerLead = headerRaw.length - headerRaw.trimStart().length
            val headerText = headerRaw.trim()
            val label = slice(header, headerLead, headerLead + headerText.length)
            val body = sec.drop(1)

            if (headerText.equals("Active Effects", ignoreCase = true)) {
                for (line in body) parseEffectLine(line)?.let { out.add(it) }
            } else if (body.isEmpty()) {
                out.add(label)
            } else {
                for (line in body) {
                    val raw = line.string
                    val lead = raw.length - raw.trimStart().length
                    val text = raw.trim()
                    if (text.isEmpty()) continue
                    // "Not active! Obtain ..." -> "Not active"; plain times have no punctuation.
                    val cut = text.indexOfFirst { it == '!' || it == '.' }.let { if (it < 0) text.length else it }
                    out.add(entry(label, slice(line, lead, lead + cut)))
                }
            }
        }
        return out
    }

    private fun footerLine(footer: String?): String {
        if (footer != null && footer.isNotEmpty()) for (line in footer.split("\n")) {
            val s = line.replace(Regex("\u00A7."), "").trim()
            if (s.uppercase().contains("STORE") || s.uppercase().contains("RANKS")) return "\u00A76$s"
        }
        return "\u00A76Ranks, Boosters & MORE! \u00A7eSTORE.HYPIXEL.NET"
    }
}