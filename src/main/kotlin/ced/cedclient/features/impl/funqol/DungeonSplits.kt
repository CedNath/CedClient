package ced.cedclient.features.impl.funqol

import ced.cedclient.utils.ChatPrefix
import ced.cedclient.config.CedPaths
import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.RenderableSetting
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.SettingsPopup
import ced.cedclient.utils.Color
import ced.cedclient.utils.TpsTracker
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.state.DungeonState
import com.google.gson.GsonBuilder
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.io.File
import kotlin.math.abs

/**
 * Dungeon Splits (FishMod-style): each run is cut into chat-driven segments -- Blood Open, Blood Clear, Portal,
 * then the floor's boss phases (F6: Terracottas / Giants / Sadan, F7: Maxor / Storm / Terminals / Goldor / Necron,
 * M7 adds Dragons) -- each one ending on the boss / Watcher line that starts the next. Also shows Boss Entry and
 * Run Time (cumulative), an estimated total, and F7/M7 terminal sections (1st-4th).
 *
 * Personal bests per floor: the PB run's segments are what each split is compared to. Only a clear counts
 * (the "☠ Defeated <boss> in ..." line); a failed run just stops the timer.
 */
object DungeonSplits : Module(
    "Dungeon Splits",
    Category.Skyblock,
    "FishMod-style dungeon split timer: per-floor boss phase splits, terminal sections, estimated total and PBs."
), HudElement {

    // ---- settings ----
    private val showDeltas = BooleanSetting("Compare to PB", true, "Colour each split green / red against your PB run.")
    private val secondColumn = DropdownSetting(
        "Second Column", listOf("Tick Time", "PB Difference", "None"), "Tick Time",
        "Tick Time: the split timed in server ticks (lag-free, like FishMod). PB Difference: +/- against your PB run."
    )
    private val onlyStarted = BooleanSetting("Only Show Started Splits", false, "Hide each split until its phase has started.")
    private val showEstimate = BooleanSetting("Est. Total", true, "Estimated final time: elapsed + the expected time of each split still to come.")
    private val estimateFrom = DropdownSetting(
        "Estimate From", listOf("Recent Runs", "PB"), "Recent Runs",
        "Recent Runs: each split's median over your last $HISTORY_SIZE clears on that floor (how your party usually plays). PB: your PB run's splits."
    )
    private val terminalSplits = BooleanSetting("Terminal Splits", true, "F7 / M7: time each terminal section (1st-4th) during the Terminals split.")
    private val chatSummary = BooleanSetting("Chat Summary", true, "Post the run's splits (and a new PB) to chat when it ends.")
    private val lingerSeconds = NumberSetting("Show After Run", 10.0, 0.0, 60.0, 1.0, "Seconds the splits stay up after the run ends (hidden right away once you leave the dungeon).")
    private val resetPbs = ActionSetting("Reset All PBs", "Forget every saved personal best and the recent-run history.") {
        data.pbs.clear()
        data.history.clear()
        persist()
        say("Personal bests reset")
    }.also { it.advanced = true }

    // ---- HUD customisation (shown in the "Customize HUD" window) ----
    private val showHeader = BooleanSetting("Header", false, "A title line with the floor (\"F7 Splits\").")
    private val showBossEntry = BooleanSetting("Boss Entry", true, "Time from the start of the run to entering the boss.")
    private val showRunTime = BooleanSetting("Run Time", true, "Total run time.")
    private val showPbRow = BooleanSetting("PB Row", true, "Your personal best for this floor.")
    private val showLagLost = BooleanSetting("Lag Lost", true, "Real time minus tick time: how much of the run was lost to server lag.")

    private val showBackground = BooleanSetting("Background", true)
    private val cornerRadius = NumberSetting("Corner Radius", 5.0, 0.0, 12.0, 1.0)
    private val padding = NumberSetting("Padding", 6.0, 0.0, 16.0, 1.0)
    private val lineSpacing = NumberSetting("Line Spacing", 4.0, 0.0, 12.0, 1.0)
    private val decimals = NumberSetting("Decimals", 2.0, 0.0, 3.0, 1.0, "Decimal places on split times.")
    private val textShadow = BooleanSetting("Text Shadow", false)
    private val coloredNames = BooleanSetting("Colored Split Names", true, "Give each split its own colour (off: all names use Text Color).")

    private val bgColor = ColorSetting("Background Color", Color(16, 16, 20, 0.7f))
    private val textColor = ColorSetting("Text Color", Color(255, 255, 255))
    private val runningColor = ColorSetting("Running Color", Color(255, 224, 102), "The split currently running.")
    private val aheadColor = ColorSetting("Ahead Color", Color(90, 220, 90), "Faster than your PB.")
    private val behindColor = ColorSetting("Behind Color", Color(240, 80, 80), "Slower than your PB.")
    private val upcomingColor = ColorSetting("Upcoming Color", Color(140, 140, 150), "Splits not reached yet.")
    private val tickColor = ColorSetting("Tick Color", Color(180, 180, 200), "The tick time column.")

    private val customizeHud = ActionSetting("Customize HUD", "Which rows show, and how the splits card looks.") {
        (mc.screen as? ClickGUI)?.openPopup(
            SettingsPopup(
                "Dungeon Splits HUD",
                columns = listOf(
                    listOf(SettingsPopup.Section("Rows", listOf(
                        showHeader, onlyStarted, terminalSplits, showBossEntry, showRunTime, showEstimate, showPbRow, showLagLost,
                    ))),
                    listOf(SettingsPopup.Section("Look", listOf(
                        showBackground, cornerRadius, padding, lineSpacing, decimals, textShadow, coloredNames,
                    ))),
                    listOf(SettingsPopup.Section("Colors", listOf(
                        bgColor, textColor, runningColor, aheadColor, behindColor, upcomingColor, tickColor,
                    ))),
                ),
            )
        )
    }

    /** Settings that only live in the Customize HUD window (not the module's dropdown). */
    private val popupOnly: List<RenderableSetting<*>> = listOf(
        showHeader, onlyStarted, terminalSplits, showBossEntry, showRunTime, showEstimate, showPbRow, showLagLost,
        showBackground, cornerRadius, padding, lineSpacing, decimals, textShadow, coloredNames,
        bgColor, textColor, runningColor, aheadColor, behindColor, upcomingColor, tickColor,
    )

    // ------------------------------------------------------------------
    // Split definitions
    // ------------------------------------------------------------------

    /** One segment: runs from the previous segment's end until [ends] matches a chat line (null = until the clear). */
    private class SegDef(val name: String, val color: Int, val estimate: Double, val ends: ((String) -> Boolean)?)

    private fun starts(prefix: String): (String) -> Boolean = { it.startsWith(prefix) }

    private const val MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
    private val CLEAR = Regex("""^\s*☠ Defeated (.+) in """)

    // Floor-independent: the Watcher's first line (or the blood door) opens blood, "You have proven yourself" clears
    // it. The boss entry is the floor boss's first line: the Watcher's blood mobs include earlier floors' bosses that
    // talk as [BOSS] too (Scarf in F4 blood), so "any non-Watcher [BOSS] line" ended Portal mid blood clear.
    private val bloodOpen = SegDef("Blood Open", 0xAA0000, 30.0) { it == "The BLOOD DOOR has been opened!" || it.startsWith("[BOSS] The Watcher:") }
    private val bloodClear = SegDef("Blood Clear", 0xFF5555, 65.0, starts("[BOSS] The Watcher: You have proven yourself"))
    private fun portal(boss: String?) = SegDef(PORTAL, 0xFF55FF, 10.0) {
        if (boss != null) it.startsWith("[BOSS] $boss:") else it.startsWith("[BOSS] ") && !it.startsWith("[BOSS] The Watcher")
    }
    private const val PORTAL = "Portal"
    /** Who speaks first on entering each floor's boss (F1..F7). */
    private val FLOOR_BOSS = listOf("Bonzo", "Scarf", "The Professor", "Thorn", "Livid", "Sadan", "Maxor")

    private fun boss(name: String, color: Int, estimate: Double) = SegDef(name, color, estimate, null)

    private fun segmentsFor(floor: String): List<SegDef> {
        val n = floor.drop(1).toIntOrNull()
        val master = floor.startsWith("M")
        val common = listOf(bloodOpen, bloodClear, portal(n?.let { FLOOR_BOSS.getOrNull(it - 1) }))
        return when (n) {
            null -> listOf(bloodOpen, SegDef("Blood Clear", 0xFF5555, 30.0, null)) // Entrance: the Watcher is the end
            1 -> common + boss("Bonzo", 0x00AAAA, 30.0)
            2 -> common + boss("Scarf", 0x55FF55, 45.0)
            3 -> common + boss("Professor", 0x5555FF, 55.0)
            4 -> common + boss("Thorn", 0x00AA00, 80.0)
            5 -> common + boss("Livid", 0xAAAAAA, 85.0)
            6 -> common + listOf(
                SegDef("Terracottas", 0xFFAA00, 40.0, starts("[BOSS] Sadan: ENOUGH!")),
                SegDef("Giants", 0xAA00AA, 12.0, starts("[BOSS] Sadan: You did it.")),
                boss("Sadan", 0xFFFF55, 13.0),
            )
            7 -> common + listOf(
                SegDef("Maxor", 0x55FFFF, 26.0, starts("[BOSS] Storm: Pathetic Maxor")),
                SegDef("Storm", 0x5555FF, 48.0, starts("[BOSS] Goldor: Who dares trespass")),
                SegDef("Terminals", 0xFFFF55, 50.0) { it == "The Core entrance is opening!" },
                SegDef("Goldor", 0xFFAA00, 9.0, starts("[BOSS] Necron: You went further than any human")),
                if (master) SegDef("Necron", 0xFF5555, 33.0, starts("[BOSS] Necron: All this, for nothing"))
                else boss("Necron", 0xFF5555, 57.0),
            ) + (if (master) listOf(boss("Dragons", 0xAA00AA, 57.0)) else emptyList())
            else -> common
        }
    }

    // ------------------------------------------------------------------
    // Run state
    // ------------------------------------------------------------------

    private class Seg(val def: SegDef) {
        var start = -1L
        var end = -1L
        val done get() = end >= 0
        val running get() = start >= 0 && end < 0
        fun time(now: Long) = if (start < 0) -1L else (if (end >= 0) end else now) - start
        // Same, in server ticks (relative to the run start tick)
        var startTick = -1L
        var endTick = -1L
        fun ticks(nowTick: Long) = if (startTick < 0) -1L else (if (endTick >= 0) endTick else nowTick) - startTick
    }

    private var runStart = 0L
    private var runStartTick = 0L
    /** Run time in server ticks when it ended (frozen for the HUD). */
    private var finishedTick = 0L
    private var floor: String? = null
    private var segs: List<Seg> = emptyList()
    private var current = 0
    private var finishedAt = 0L
    private var cleared = false
    private var lastRunEnded = false
    /** When the end-of-run summary started (run time, ms), waiting a moment for a "Defeated" line. */
    private var summaryAt = -1L
    /** Run time of Necron's "All this, for nothing..." (M7 fallback above), -1 until seen. */
    private var necronDownAt = -1L
    private var necronDownTick = -1L
    private var summarySeenAtMillis = 0L
    private var summaryTick = -1L
    /** End-of-run chat summary, posted at [summarySayAt]. */
    private val pendingSummary = ArrayList<String>()
    private var summarySayAt = 0L

    // F7 / M7 terminal sections
    private val sections = LongArray(5) { -1L } // end time of section 1..4 (index 1..4), relative to run start
    private val sectionTicks = LongArray(5) { -1L }
    private var section = 1
    private var sectionTermsDone = false
    private var sectionGateDown = false
    private val TERMINAL = Regex("""^(\w+) (activated|completed) a (terminal|device|lever)! \((\d+)/(\d+)\)""")

    // ------------------------------------------------------------------
    // Saved: PB per floor (segment durations of the PB run) + HUD position
    // ------------------------------------------------------------------

    private class PbRun(val total: Long, val segments: Map<String, Long>)
    private class SaveData(
        val pbs: MutableMap<String, PbRun> = mutableMapOf(),
        /** Split durations of the last [HISTORY_SIZE] clears per floor, oldest first (for Est. Total). */
        var history: MutableMap<String, MutableList<Map<String, Long>>> = mutableMapOf(),
        var x: Int = 1124, var y: Int = 2, var scale: Float = 1f,
    )

    private var data = SaveData()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val saveFile: File by lazy { CedPaths.file("dungeon_splits.json") }

    override val label = "Dungeon Splits"
    override var panelX: Int
        get() = data.x
        set(v) { data.x = v }
    override var panelY: Int
        get() = data.y
        set(v) { data.y = v }
    override var panelScale: Float
        get() = data.scale
        set(v) { data.scale = v }
    override var lastWidth: Int = 150
        private set
    override var lastHeight: Int = 120
        private set

    private val mc get() = Minecraft.getInstance()
    private fun now() = System.currentTimeMillis() - runStart
    private fun nowTick() = TpsTracker.serverTicks - runStartTick

    init {
        addSettings(showDeltas, secondColumn, estimateFrom, chatSummary, lingerSeconds, customizeHud, resetPbs)
        addSettings(*popupOnly.toTypedArray())
        popupOnly.forEach { it.inPopup = true }
        load()
        on<ChatMessageEvent> { onChat(it.unformattedText.trim()) }
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
    }

    private fun onChat(text: String) {
        if (text == MORT) {
            startRun()
            return
        }
        if (runStart == 0L || finishedAt != 0L) return
        ensureFloor()

        if (CLEAR.containsMatchIn(text)) {
            finishRun(clear = true, at = if (summaryAt >= 0) summaryAt else now(), atTick = if (summaryTick >= 0) summaryTick else nowTick())
            return
        }

        // Terminal sections (only meaningful during Terminals)
        if (segs.getOrNull(current)?.def?.name == "Terminals" && section <= 4) {
            TERMINAL.find(text)?.let { m ->
                if (m.groupValues[4] == m.groupValues[5]) sectionTermsDone = true
            }
            if (text == "The gate has been destroyed!") sectionGateDown = true
            if (section < 4 && sectionTermsDone && sectionGateDown) {
                sections[section] = now()
                sectionTicks[section] = nowTick()
                section++
                sectionTermsDone = false
                sectionGateDown = false
            }
        }

        // M7 read as F7 (no Dragons row): Necron's last line was noted, and the Wither King speaking proves it's M7.
        if (text.startsWith("[BOSS] Necron: All this, for nothing")) { necronDownAt = now(); necronDownTick = nowTick() }
        if (text.startsWith("[BOSS] Wither King:") && segs.none { it.def.name == "Dragons" } && necronDownAt >= 0) {
            ced.cedclient.utils.debug.Diag.warn("Dungeon Splits", "Wither King in a run read as $floor; added the Dragons split")
            floor = "M7"
            segs.getOrNull(current)?.takeIf { it.running }?.let { it.end = necronDownAt; it.endTick = necronDownTick }
            segs = segs + Seg(boss("Dragons", 0xAA00AA, 57.0)).also { it.start = necronDownAt; it.startTick = necronDownTick }
            current = segs.lastIndex
            return
        }

        // Advance (one line ends one segment). If a later segment's end line shows up, an earlier trigger was missed:
        // close everything up to it here, skipped ones without a time, so the next split still starts on time.
        val hit = (current until segs.size).firstOrNull { segs[it].def.ends?.invoke(text) == true } ?: return
        val t = now()
        val tk = nowTick()
        for (i in current..hit) {
            val seg = segs[i]
            seg.end = t
            seg.endTick = tk
            if (seg.def.name == "Terminals" && section <= 4) { sections[4] = t; sectionTicks[4] = tk }
        }
        current = hit + 1
        segs.getOrNull(current)?.let { it.start = t; it.startTick = tk }
    }

    private fun tick() {
        if (pendingSummary.isNotEmpty() && System.currentTimeMillis() >= summarySayAt) {
            pendingSummary.forEach(::say)
            pendingSummary.clear()
        }
        if (runStart == 0L || finishedAt != 0L) {
            lastRunEnded = DungeonState.runEnded
            return
        }
        ensureFloor()
        // Left the dungeon mid-run: drop it.
        if (!DungeonState.inDungeon && now() > 5_000) {
            runStart = 0L
            return
        }
        // The end-of-run summary's title line comes before "☠ Defeated ...": stop the clock there, and if no
        // Defeated line follows within a few seconds it was a failed run.
        val ended = DungeonState.runEnded
        if (ended && !lastRunEnded && summaryAt < 0) {
            summaryAt = now()
            summaryTick = nowTick()
            summarySeenAtMillis = System.currentTimeMillis()
        }
        lastRunEnded = ended
        if (summaryAt >= 0 && System.currentTimeMillis() - summarySeenAtMillis > DEFEATED_GRACE_MS) {
            finishRun(clear = false, at = summaryAt, atTick = summaryTick)
        }
    }

    private fun startRun() {
        runStart = System.currentTimeMillis()
        runStartTick = TpsTracker.serverTicks
        floor = null
        segs = emptyList()
        current = 0
        finishedAt = 0L
        cleared = false
        lastRunEnded = DungeonState.runEnded
        summaryAt = -1L
        summaryTick = -1L
        sections.fill(-1L)
        sectionTicks.fill(-1L)
        section = 1
        sectionTermsDone = false
        sectionGateDown = false
        necronDownAt = -1L
        necronDownTick = -1L
        ensureFloor()
    }

    /** The floor is announced in chat around the same time as Mort; build the split list once it is known. */
    private fun ensureFloor() {
        if (segs.isNotEmpty()) return
        val f = DungeonState.floor ?: return
        floor = f
        segs = segmentsFor(f).map(::Seg)
        segs.first().start = 0L
        segs.first().startTick = 0L
    }

    /** [at] = final run time (ms since Mort). */
    private fun finishRun(clear: Boolean, at: Long, atTick: Long) {
        val t = at
        segs.getOrNull(current)?.let { if (it.running) { it.end = t; it.endTick = atTick } }
        finishedTick = atTick
        finishedAt = runStart + t
        cleared = clear
        val f = floor ?: return
        val pb = data.pbs[f]
        if (clear) {
            val runs = data.history.getOrPut(f) { mutableListOf() }
            runs += segs.filter { it.done && it.start >= 0 }.associate { it.def.name to it.time(t) }
            while (runs.size > HISTORY_SIZE) runs.removeAt(0)
        }
        val newPb = clear && (pb == null || t < pb.total)
        if (newPb) data.pbs[f] = PbRun(t, segs.filter { it.done && it.start >= 0 }.associate { it.def.name to it.time(t) })
        if (clear) persist()
        if (isEnabled && chatSummary.value) {
            val delta = if (clear && pb != null) " " + deltaText(t - pb.total, chat = true) else ""
            // Held back until Hypixel's end-of-run block has finished printing, so it isn't split in the middle.
            pendingSummary += "§f$f ${if (clear) "cleared" else "§cfailed§f"} in §e${fmtTotal(t)}$delta" + if (newPb) " §a§lNEW PB!" else ""
            pendingSummary += segs.filter { it.done && it.start >= 0 }.joinToString(" §8| ") { "${mcColor(it.def.color)}${it.def.name} §f${fmtSeg(it.time(t))}" }
            pendingSummary += "§7Lag lost: §c${fmtSeg(lagLost(t, atTick))}"
            summarySayAt = System.currentTimeMillis() + SUMMARY_DELAY_MS
        }
    }

    // ------------------------------------------------------------------
    // HUD
    // ------------------------------------------------------------------

    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") tickCounter: DeltaTracker) {
        if (!isEnabled || runStart == 0L || segs.isEmpty()) return
        if (finishedAt != 0L && (!DungeonState.inDungeon || System.currentTimeMillis() - finishedAt > lingerSeconds.value * 1000)) return
        renderInternal(g)
    }

    /** One HUD line: name, main time, and the optional second column (tick time or PB difference). */
    private class Row(
        val name: String, val color: Int, val time: String, val timeColor: Int,
        val second: String? = null, val secondColor: Int = 0, val indent: Boolean = false,
    )

    override fun renderInternal(g: GuiGraphicsExtractor) {
        val preview = runStart == 0L || segs.isEmpty()
        val f = if (preview) "F7" else floor ?: "?"
        val list = if (preview) segmentsFor("F7").map(::Seg) else segs
        val t = when {
            preview -> 0L
            finishedAt != 0L -> finishedAt - runStart // frozen at the finish
            else -> now()
        }
        val tk = when {
            preview -> 0L
            finishedAt != 0L -> finishedTick
            else -> nowTick()
        }
        val pb = data.pbs[f]
        val rows = buildRows(list, t, tk, pb, preview)

        val s = panelScale
        val font = NVGRenderer.defaultFont
        val size = 10f * s
        val secondSize = size * 0.9f
        val pad = padding.value.toFloat() * s
        val lineH = size + lineSpacing.value.toFloat() * s
        val gap = 10f * s
        val header = if (showHeader.value) "$f Splits" else null
        val headerH = if (header != null) lineH + 3f * s else 0f

        val nameW = rows.maxOfOrNull { NVGRenderer.textWidth(it.name, size, font) + if (it.indent) 8f * s else 0f } ?: 0f
        val timeW = rows.maxOfOrNull { NVGRenderer.textWidth(it.time, size, font) } ?: 0f
        val secondW = rows.mapNotNull { it.second }.maxOfOrNull { NVGRenderer.textWidth(it, secondSize, font) + 8f * s } ?: 0f
        val w = maxOf(pad * 2 + nameW + gap + timeW + secondW, header?.let { pad * 2 + NVGRenderer.textWidth(it, size, font) } ?: 0f)
        val h = pad * 2 + headerH + lineH * rows.size - (lineH - size)

        lastWidth = (w / s).toInt()
        lastHeight = (h / s).toInt()

        val shadow = textShadow.value
        fun text(str: String, x: Float, y: Float, sz: Float, color: Int) =
            if (shadow) NVGRenderer.textShadow(str, x, y, sz, color, font) else NVGRenderer.text(str, x, y, sz, color, font)

        val full = {
            val ratio = NVGRenderer.canvasWidth / g.guiWidth().toFloat()
            NVGRenderer.scale(ratio, ratio)
            NVGRenderer.translate(panelX.toFloat(), panelY.toFloat())
            if (showBackground.value) NVGRenderer.rect(0f, 0f, w, h, BG, cornerRadius.value.toFloat() * s)

            var y = pad
            header?.let {
                text(it, pad, y, size, WHITE)
                y += headerH
            }
            for (r in rows) {
                text(r.name, pad + if (r.indent) 8f * s else 0f, y, size, r.color)
                val tx = w - pad - secondW - NVGRenderer.textWidth(r.time, size, font)
                text(r.time, tx, y, size, r.timeColor)
                r.second?.let { d ->
                    text(d, w - pad - NVGRenderer.textWidth(d, secondSize, font), y + 0.5f * s, secondSize, r.secondColor)
                }
                y += lineH
            }
        }
        if (LeapMenu.defer(full)) return
        NVGSpecialRenderer.draw(g, 0, 0, g.guiWidth(), g.guiHeight(), renderContent = full)
    }

    private val tickMode get() = secondColumn.value == "Tick Time"
    private val diffMode get() = secondColumn.value == "PB Difference"

    /** Tick column text for a duration in server ticks (shown as seconds, like the real time). */
    private fun tickText(ticks: Long): String? = if (ticks < 0) null else fmtSecs(ticks * 50)

    /** Real time [t] (ms) minus tick time [tk] (server ticks): what server lag cost the run. */
    private fun lagLost(t: Long, tk: Long) = (t - tk * 50).coerceAtLeast(0)

    private fun buildRows(list: List<Seg>, t: Long, tk: Long, pb: PbRun?, preview: Boolean): List<Row> {
        val rows = ArrayList<Row>()

        // Boss Entry (cumulative time to the boss) sits right under Portal, the split that ends on entering it.
        val bossIdx = list.indexOfFirst { it.def.name == PORTAL }
        val bossEntryRow = if (showBossEntry.value && bossIdx >= 0 && !(onlyStarted.value && !preview && list[bossIdx].end < 0)) {
            val bossSeg = list[bossIdx]
            val entry = bossSeg.end
            val pbEntry = pb?.let { p -> list.take(bossIdx + 1).sumOf { p.segments[it.def.name] ?: return@let null } }
            // Same colouring as the splits: green / red against the PB's entry, or the difference in the second column.
            val entryColor = when {
                entry < 0 -> GREY
                showDeltas.value && pbEntry != null -> if (entry <= pbEntry) GREEN else RED
                else -> WHITE
            }
            val (second, secondColor) = when {
                tickMode -> (if (bossSeg.endTick >= 0) fmtSecs(bossSeg.endTick * 50) else null) to TICK
                diffMode && entry >= 0 && pbEntry != null -> (entry - pbEntry).let { deltaText(it, chat = false) to (if (it <= 0) GREEN else RED) }
                else -> null to 0
            }
            Row("Boss Entry", nameColor(0x5555FF), if (entry >= 0) fmtTotal(entry) else pbEntry?.let(::fmtTotal) ?: "-", entryColor, second, secondColor)
        } else null

        for (seg in list) {
            if (onlyStarted.value && !preview && seg.start < 0) continue
            val time = seg.time(t)
            val pbSeg = pb?.segments?.get(seg.def.name)
            val (timeText, timeColor) = when {
                time < 0 -> (pbSeg?.let(::fmtSeg) ?: "-") to GREY
                seg.running -> fmtSeg(time) to YELLOW
                showDeltas.value && pbSeg != null -> fmtSeg(time) to (if (time <= pbSeg) GREEN else RED)
                else -> fmtSeg(time) to WHITE
            }
            val (second, secondColor) = when {
                tickMode -> tickText(seg.ticks(tk)) to TICK
                diffMode && seg.done && pbSeg != null -> (time - pbSeg).let { deltaText(it, chat = false) to (if (it <= 0) GREEN else RED) }
                else -> null to 0
            }
            rows += Row(seg.def.name, nameColor(seg.def.color), timeText, timeColor, second, secondColor)
            if (seg.def.name == PORTAL) bossEntryRow?.let { rows += it }

            // Terminal sections under Terminals, once it has started.
            if (seg.def.name == "Terminals" && terminalSplits.value && (seg.start >= 0 || preview)) {
                var prev = seg.start
                var prevTick = seg.startTick
                for (i in 1..4) {
                    val end = sections[i]
                    val endTick = sectionTicks[i]
                    if (onlyStarted.value && !preview && prev < 0) break
                    val (text, color) = when {
                        preview || prev < 0 -> "-" to GREY
                        end >= 0 -> fmtSeg(end - prev) to WHITE
                        seg.running -> fmtSeg(t - prev) to YELLOW
                        else -> "-" to GREY
                    }
                    val ticks = when {
                        preview || prevTick < 0 -> -1L
                        endTick >= 0 -> endTick - prevTick
                        seg.running -> tk - prevTick
                        else -> -1L
                    }
                    rows += Row(ORDINALS[i - 1], GREY, text, color, if (tickMode) tickText(ticks) else null, TICK, indent = true)
                    prev = if (end >= 0) end else -1L
                    prevTick = if (endTick >= 0) endTick else -1L
                }
            }
        }

        // Cumulative rows
        val runColor = when {
            preview -> GREY
            finishedAt != 0L -> if (cleared) GREEN else RED
            else -> WHITE
        }
        if (showRunTime.value) rows += Row("Run Time", WHITE, fmtTotal(t), runColor, if (tickMode) fmtSecs(tk * 50) else null, TICK)

        if (showEstimate.value && finishedAt == 0L) {
            // Elapsed + for every unfinished split: max(expected time, what it already took).
            val f = if (preview) "F7" else floor ?: "?"
            var est = t
            for (seg in list) {
                if (seg.done) continue
                val expected = expectedTime(f, seg.def, pb)
                est += (expected - seg.time(t).coerceAtLeast(0)).coerceAtLeast(0)
            }
            rows += Row("Est. Total", nameColor(0xFFAA00), fmtTotal(est), GREY)
        }
        if (pb != null && showPbRow.value) rows += Row("PB", nameColor(0xFF55FF), fmtTotal(pb.total), GREY)
        if (showLagLost.value) rows += Row("Lag Lost", nameColor(0xAAAAAA), fmtSeg(lagLost(t, tk)), if (preview) GREY else RED)
        return rows
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private val places get() = decimals.value.toInt().coerceIn(0, 3)

    /** Cut [ms] down to the shown precision, so formatting never rounds 59.996 up to "60.00". */
    private fun truncated(ms: Long): Long {
        val unit = when (places) { 0 -> 1000L; 1 -> 100L; 2 -> 10L; else -> 1L }
        return ms.coerceAtLeast(0) / unit * unit
    }

    /** "12.34s", or "1m 29.09s" past a minute (decimal places from the Decimals setting). */
    private fun fmtSeg(ms: Long): String {
        val total = truncated(ms)
        val seconds = "%.${places}fs".format((total % 60_000) / 1000.0)
        return if (total < 60_000) seconds else "${total / 60_000}m $seconds"
    }

    private fun fmtTotal(ms: Long): String = fmtSeg(ms)

    /** Seconds only, never minutes ("92.45s"): the tick time column. */
    private fun fmtSecs(ms: Long): String = "%.${places}fs".format(truncated(ms) / 1000.0)

    /** Expected duration of a split: median of recent clears (Recent Runs), else the PB run's, else a typical time. */
    private fun expectedTime(floor: String, def: SegDef, pb: PbRun?): Long {
        if (estimateFrom.value == "Recent Runs") {
            val times = data.history[floor]?.mapNotNull { it[def.name] }?.sorted()
            if (!times.isNullOrEmpty()) return times[times.size / 2]
        }
        return pb?.segments?.get(def.name) ?: (def.estimate * 1000).toLong()
    }

    private fun deltaText(d: Long, chat: Boolean): String {
        val sign = if (d <= 0) "-" else "+"
        val text = "$sign%.1f".format(abs(d) / 1000.0)
        return if (chat) (if (d <= 0) "§a($text)" else "§c($text)") else text
    }

    private fun argb(rgb: Int) = rgb or 0xFF000000.toInt()

    /** A split's own colour, or the plain text colour with Colored Split Names off. */
    private fun nameColor(rgb: Int) = if (coloredNames.value) argb(rgb) else WHITE

    /** Nearest § colour for a chat summary. */
    private fun mcColor(rgb: Int): String = when (rgb) {
        0xAA0000 -> "§4"; 0xFF5555 -> "§c"; 0xFF55FF -> "§d"; 0x00AAAA -> "§3"; 0x55FF55 -> "§a"
        0x5555FF -> "§9"; 0x00AA00 -> "§2"; 0xAAAAAA -> "§7"; 0xFFAA00 -> "§6"; 0xAA00AA -> "§5"
        0xFFFF55 -> "§e"; 0x55FFFF -> "§b"; else -> "§f"
    }

    private fun say(text: String) {
        mc.player?.sendSystemMessage(ChatPrefix.msg("$text"))
    }

    private fun load() {
        try {
            if (saveFile.exists()) gson.fromJson(saveFile.readText(), SaveData::class.java)?.let { data = it }
        } catch (e: Exception) {
            println("[DungeonSplits] Failed to load: ${e.message}")
        }
        @Suppress("SENSELESS_COMPARISON")
        if (data.history == null) data.history = mutableMapOf() // saves from before run history existed
        // Saves from the first version of this module had no "segments"; drop those PBs.
        @Suppress("SENSELESS_COMPARISON")
        data.pbs.entries.removeIf { it.value.segments == null }
        data.scale = data.scale.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    private fun persist() {
        try {
            saveFile.parentFile?.mkdirs()
            saveFile.writeText(gson.toJson(data))
        } catch (e: Exception) {
            println("[DungeonSplits] Failed to save: ${e.message}")
        }
    }

    override fun save() = persist()
    override fun resetPosition() { data.x = 1124; data.y = 2; data.scale = 1f }

    override fun adjustScale(delta: Float) {
        panelScale = (panelScale + delta).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    private val ORDINALS = listOf("1st", "2nd", "3rd", "4th")
    private const val HISTORY_SIZE = 10
    private const val DEFEATED_GRACE_MS = 4_000L
    private const val SUMMARY_DELAY_MS = 1_500L
    private const val MIN_SCALE = 0.5f
    private const val MAX_SCALE = 3.0f

    // Colours come from the Customize HUD settings.
    private val BG get() = bgColor.value.rgba
    private val WHITE get() = textColor.value.rgba
    private val GREY get() = upcomingColor.value.rgba
    private val YELLOW get() = runningColor.value.rgba
    private val TICK get() = tickColor.value.rgba
    private val GREEN get() = aheadColor.value.rgba
    private val RED get() = behindColor.value.rgba
}
