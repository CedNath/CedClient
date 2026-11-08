package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.mixin.accessor.PlayerTabOverlayAccessor
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.ui.Palette
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

/** Colour codes, compiled once. */
private val CODES = Regex("§.")

/**
 * Compact custom tab list. Hypixel packs every column into player-list
 * entries ordered column-major by `!A-a`/`!B-a` sort keys, so entries are
 * rendered verbatim into a translucent panel rather than re-deriving data.
 * Adds a header stat bar and footer. Cancels vanilla's PlayerTabOverlay
 * render (see PlayerTabOverlayMixin) and draws this instead.
 */
object CompactTab : Module(
    "Compact Tab",
    Category.HUD,
    "Reorganizes Hypixel's tab list into a compact panel with a stat bar",
    defaultEnabled = false
) {

    private val opacity = registerSetting(NumberSetting("Panel Opacity", 100.0, 0.0, 100.0, 1.0, "How solid the cards are (percent)."))
    private val statBar = registerSetting(BooleanSetting("Stat Bar", true))
    private val statBarPos = registerSetting(DropdownSetting("Stat Bar Position", listOf("TOP", "BOTTOM", "LEFT", "RIGHT"), "TOP"))
    private val effectsPanel = registerSetting(
        BooleanSetting(
            "Effects Panel", true,
            "Shows the Active Effects / Cookie Buff info from Hypixel's tab footer in a compact panel under the tab list"
        )
    )



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

    /** Text size and line step of the tab list, in GUI units. */
    private const val SIZE = 8.5f
    private const val LH = 10f

    /**
     * The tab list on the shared HUD card (HudCard): one NanoVG pass draws every panel and all text in Inter, then the
     * player heads (vanilla textures) go on top of it.
     */
    @JvmStatic
    fun render(overlay: PlayerTabOverlay, ctx: GuiGraphicsExtractor, screenW: Int) {
        val mc = Minecraft.getInstance()
        if (mc.player == null || mc.connection == null) return

        val acc = overlay as PlayerTabOverlayAccessor
        val header = acc.`ced$getHeader`()?.string
        val footerComp = acc.`ced$getFooter`()
        val footer = footerComp?.string

        val mdl = model(mc, overlay, header, footer, footerComp) ?: return
        val ping = realPing(mc)
        val tps = currentTps()
        val labels = arrayOf("SERVER", "TPS", "FPS", "PING")
        val values = arrayOf(
            mdl.server,
            if (tps < 0) "-" else String.format("%.2f", tps),
            mc.fps.toString(),
            if (ping < 0) "-" else "${ping}ms"
        )
        fun valueColor(i: Int) = if (i == 1 && tps in 0.0..18.99) Palette.BAD_SEE else Palette.TEXT

        val pad = 8f
        val gap = 8f
        val topPad = 8f
        val footH = 12f
        val boxGap = 8f
        val contentW = mdl.colWidths.sum() + gap * (mdl.columns.size - 1)
        val tabW = contentW + pad * 2
        val tabH = topPad + mdl.rows * LH + 6f + footH
        val alpha = (opacity.value / 100.0).toFloat().coerceIn(0f, 1f)
        val heads = ArrayList<Head>()

        HudCard.draw(ctx, 0, 0) {
            fun panel(x: Float, y: Float, w: Float, h: Float) {
                NVGRenderer.push()
                NVGRenderer.translate(x, y)
                HudCard.card(w, h, 1f, alpha, label = "Compact Tab")
                NVGRenderer.pop()
            }
            fun tab(x0: Float, y0: Float, w: Float) {
                panel(x0, y0, w, tabH)
                drawColumns(overlay, mdl, x0 + pad, y0 + topPad, gap, heads)
                centered(mdl.footer, x0 + w / 2f, y0 + tabH - footH + 2f, Palette.GOLD)
            }

            if (!statBar.value) {
                val w = min(screenW - 12f, tabW)
                val x0 = (screenW - w) / 2f
                tab(x0, 4f, w)
                drawEffects(mdl, x0, w, 4f + tabH + boxGap / 2f, ::panel)
                return@draw
            }

            val pos = statBarPos.value.uppercase()
            if (pos == "LEFT" || pos == "RIGHT") {
                val statLineH = 14f
                var statW = 0f
                for (i in labels.indices) statW = max(statW, HudCard.width(labels[i] + " ", SIZE) + HudCard.width(values[i], SIZE))
                statW += 16f
                val totalW = tabW + boxGap + statW
                val w = min(screenW - 12f, totalW)
                val x0 = (screenW - w) / 2f
                val y0 = 4f
                val tabX0 = if (pos == "LEFT") x0 + statW + boxGap else x0
                val statX0 = if (pos == "LEFT") x0 else x0 + tabW + boxGap

                tab(tabX0, y0, tabW)
                panel(statX0, y0, statW, tabH)
                val cellH = tabH / labels.size
                for (i in labels.indices) {
                    val cellTop = y0 + i * cellH
                    val sy = cellTop + (cellH - statLineH) / 2f + 3f
                    if (i > 0) HudCard.divider(statX0 + 6f, cellTop, statW - 12f, 1f)
                    HudCard.text(labels[i] + " ", statX0 + 8f, sy, SIZE, Palette.MUTED, remap = false)
                    HudCard.text(values[i], statX0 + 8f + HudCard.width(labels[i] + " ", SIZE), sy, SIZE, valueColor(i), remap = false)
                }
                drawEffects(mdl, x0, w, y0 + tabH + boxGap / 2f, ::panel)
            } else {
                val statBarH = 32f
                val gapTB = 4f
                val w = min(screenW - 12f, tabW)
                val x0 = (screenW - w) / 2f
                val y0 = 4f
                val bottom = pos == "BOTTOM"
                val tabY0 = if (bottom) y0 else y0 + statBarH + gapTB
                val statY0 = if (bottom) tabY0 + tabH + gapTB else y0

                tab(x0, tabY0, w)
                panel(x0, statY0, w, statBarH)
                val cellW = w / labels.size
                for (i in labels.indices) {
                    val cxL = x0 + i * cellW
                    if (i > 0) NVGRenderer.rect(cxL, statY0 + 6f, 1f, statBarH - 12f, Palette.LINE)
                    val cxC = cxL + cellW / 2f
                    centered(labels[i], cxC, statY0 + 7f, Palette.MUTED)
                    centered(values[i], cxC, statY0 + 18f, valueColor(i))
                }
                val groupBottom = if (bottom) statY0 + statBarH else tabY0 + tabH
                drawEffects(mdl, x0, w, groupBottom + gapTB, ::panel)
            }
        }

        // Heads after the NanoVG pass, so they sit on top of the card.
        for (h in heads) {
            try {
                PlayerFaceExtractor.extractRenderState(ctx, h.info.skin.body().texturePath(), h.x, h.y, 8, h.info.showHat(), false, -1)
            } catch (ignored: Exception) {
            }
        }
    }

    private class Head(val info: PlayerInfo, val x: Int, val y: Int)

    private fun centered(text: String, cx: Float, y: Float, color: Int) =
        HudCard.text(text, cx - HudCard.width(text, SIZE) / 2f, y, SIZE, color, remap = false)

    /**
     * Slim effects bar under the tab list: same width as the tab group, one cell per effect
     * ("God Potion: 2 days", "Cookie Buff: 2 hours", ...) spread evenly across it with dividers,
     * like the stat bar. If there are too many to fit on one line they wrap onto extra rows.
     */
    private fun drawEffects(mdl: Model, x0: Float, w: Float, topY: Float, panel: (Float, Float, Float, Float) -> Unit) {
        if (!effectsPanel.value) return
        val entries = mdl.effects
        if (entries.isEmpty()) return

        val pad = 8f
        val minGap = 16f
        val rowPitch = 12f
        val innerW = w - pad * 2

        val rows = ArrayList<List<Component>>()
        var cur = ArrayList<Component>()
        var curW = 0f
        for (e in entries) {
            val ew = HudCard.width(e, SIZE)
            if (cur.isNotEmpty() && curW + minGap + ew > innerW) {
                rows.add(cur)
                cur = ArrayList()
                curW = 0f
            }
            curW += if (cur.isEmpty()) ew else minGap + ew
            cur.add(e)
        }
        if (cur.isNotEmpty()) rows.add(cur)

        panel(x0, topY, w, rows.size * rowPitch + 6f)
        for ((ri, row) in rows.withIndex()) {
            val cellW = w / row.size
            val ty = topY + 4f + ri * rowPitch
            for ((ci, entry) in row.withIndex()) {
                val cellX = x0 + ci * cellW
                if (ci > 0) NVGRenderer.rect(cellX, ty - 1f, 1f, 10f, Palette.LINE)
                HudCard.componentText(entry, cellX + (cellW - HudCard.width(entry, SIZE)) / 2f, ty, SIZE, defaultRgb = Palette.TEXT and 0xFFFFFF)
            }
        }
    }

    private class Model(
        val columns: List<List<PlayerInfo>>,
        val colWidths: List<Float>,
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
        val all = ArrayList(TabListCache.entries.map { it.info })
        all.sortWith(Comparator { a, b -> a.profile.name.compareTo(b.profile.name, ignoreCase = true) })
        val grouped = LinkedHashMap<String, MutableList<PlayerInfo>>()
        for (e in all) {
            val m = COL_KEY.matcher(e.profile.name)
            if (m.find()) grouped.getOrPut(m.group(1).uppercase()) { ArrayList() }.add(e)
        }
        if (grouped.isEmpty()) return null

        val columns = ArrayList<List<PlayerInfo>>()
        val colWidths = ArrayList<Float>()
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
            var maxW = 0f
            for (e in trimmed) maxW = max(maxW, HudCard.width(overlay.getNameForDisplay(e), SIZE))
            val extra = if (playersCol) 26f else 8f
            val w = max(if (playersCol) 116f else 60f, min(maxW + extra, 230f))
            columns.add(trimmed)
            colWidths.add(w)
            rows = max(rows, trimmed.size)
        }
        if (columns.isEmpty()) return null
        rows = min(rows, 22)

        return Model(columns, colWidths, rows, findServer(mc, footer, header), footerLine(footer), effectEntries(footerComp))
    }

    private fun drawColumns(overlay: PlayerTabOverlay, mdl: Model, startX: Float, cy: Float, gap: Float, heads: MutableList<Head>) {
        var colX = startX
        for (c in mdl.columns.indices) {
            val w = mdl.colWidths[c]
            if (c > 0) NVGRenderer.rect(colX - gap / 2f, cy, 1f, mdl.rows * LH, Palette.LINE)
            val playersCol = c == 0
            val entries = mdl.columns[c]
            var r = 0
            while (r < entries.size && r < mdl.rows) {
                val e = entries[r]
                val ry = cy + r * LH
                var tx = colX
                if (playersCol && r > 0) {
                    heads += Head(e, colX.toInt(), (ry - 1f).toInt())
                    tx = colX + 10f
                }
                HudCard.componentText(overlay.getNameForDisplay(e), tx, ry, SIZE, defaultRgb = Palette.TEXT and 0xFFFFFF)
                if (playersCol && r > 0 && e.latency > 0) drawSignal(colX + w - 13f, ry, e.latency)
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

    private fun drawSignal(x: Float, y: Float, latency: Int) {
        val filled = if (latency <= 75) 4 else if (latency <= 150) 3 else if (latency <= 300) 2 else 1
        for (b in 0 until 4) {
            val h = 2f + b * 2f
            NVGRenderer.rect(x + b * 3f, y + 8f - h, 2f, h, if (b < filled) Palette.GOOD_SEE else 0x33FFFFFF)
        }
    }

    /** PingTracker's ping/pong round trip; falls back to the server-list ping. Tab latency is fake on Hypixel, so never used. */
    private fun realPing(mc: Minecraft): Int {
        val live = PingTracker.latest()
        if (live > 0) return live
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
                    hay += " " + raw.replace(CODES, "")
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
            val s = line.replace(CODES, "").trim()
            if (s.uppercase().contains("STORE") || s.uppercase().contains("RANKS")) return "\u00A76$s"
        }
        return "\u00A76Ranks, Boosters & MORE! \u00A7eSTORE.HYPIXEL.NET"
    }
}