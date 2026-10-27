package ced.cedclient.features.impl.render

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.utils.Countdown
import ced.cedclient.utils.Debug
import com.google.gson.Gson
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.io.File

/**
 * SkyBlock event countdowns like the calendar tooltip ("Starts in: 3d 2h 59m 22s"), without opening the calendar.
 * SkyBlock time is fixed by the clock: a year is 446 400 s (372 days of 20 min), so the dates below give exact
 * start times. Shows what is active (time left) and what comes next (time until), plus the local time it lands.
 * shortcut: only fixed-date events; Jacob's Contest, mayors and random events need the calendar menu parsed.
 */
object EventCountdown : Module(
    "Event Countdown",
    Category.HUD,
    "SkyBlock event countdowns (Spooky Festival, Jerry, Zoo...) with the local time they start."
), HudElement {
    private const val EPOCH = 1_560_275_700_000L // SkyBlock year 1, day 1 (Early Spring 1)
    private const val DAY = 1_200_000L
    private const val YEAR = 372 * DAY

    private class Event(val name: String, val color: Int, val durationMs: Long, val offsets: List<Long> = emptyList(), val periodMs: Long = 0L) {
        val toggle = BooleanSetting(name, true, "Show $name.")
    }

    /** SkyBlock month [month] (0 = Early Spring ... 11 = Late Winter), day [day] 1-31, as an offset into the year. */
    private fun at(month: Int, day: Int) = (month * 31 + day - 1) * DAY

    private val events = listOf(
        Event("Spooky Festival", 0xFFFF9933.toInt(), 3 * DAY, listOf(at(8, 29))),
        Event("Season of Jerry", 0xFF55FFFF.toInt(), 3 * DAY, listOf(at(11, 24))),
        Event("New Year", 0xFFFFFF55.toInt(), 3 * DAY, listOf(at(11, 29))),
        Event("Traveling Zoo", 0xFF55FF55.toInt(), 3 * DAY, listOf(at(3, 1), at(9, 1))),
        Event("Dark Auction", 0xFFAA55FF.toInt(), 5 * 60_000L, periodMs = 3 * DAY),
    )

    private val maxRows = NumberSetting("Max Rows", 3.0, 1.0, events.size.toDouble(), 1.0, "How many events to list.")
    private val showClock = BooleanSetting("Local Time", true, "The real time each event starts / ends, next to the countdown.")
    private val hideFar = BooleanSetting("Hide Far Away", false, "Only list events that are active or less than a day away.")

    private val mc get() = Minecraft.getInstance()

    private class Row(val name: String, val color: Int, val active: Boolean, val start: Long, val end: Long)

    private fun window(e: Event, now: Long): Pair<Long, Long> {
        if (e.periodMs > 0) {
            val base = EPOCH + Math.floorDiv(now - EPOCH, e.periodMs) * e.periodMs
            val s = if (now < base + e.durationMs) base else base + e.periodMs
            return s to s + e.durationMs
        }
        val year = Math.floorDiv(now - EPOCH, YEAR)
        var best = Long.MAX_VALUE
        for (y in year - 1..year + 1) for (o in e.offsets) {
            val s = EPOCH + y * YEAR + o
            if (s + e.durationMs > now && s < best) best = s
        }
        return best to best + e.durationMs
    }

    private fun rows(now: Long): List<Row> =
        events.filter { it.toggle.value }
            .map { e -> window(e, now).let { (s, end) -> Row(e.name, e.color, s <= now, s, end) } }
            .filter { !hideFar.value || it.active || it.start - now < 24 * 3_600_000L }
            .sortedWith(compareBy({ !it.active }, { it.start }))
            .take(maxRows.value.toInt())

    private var lastLogged = ""

    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") tickCounter: DeltaTracker) {
        if (!isEnabled || mc.gui.hud.isHidden || mc.level == null) return
        val now = System.currentTimeMillis()
        val list = rows(now)
        val head = list.firstOrNull()?.let { "${it.name}${if (it.active) " active" else " next"}" } ?: ""
        if (head != lastLogged) { lastLogged = head; Debug.line("EventCountdown", "top row -> $head") }
        draw(g, list, now)
    }

    override fun renderInternal(g: GuiGraphicsExtractor) {
        val now = System.currentTimeMillis()
        draw(g, rows(now).ifEmpty { listOf(Row("Spooky Festival", events[0].color, false, now + 3_000_000, now + 4_000_000)) }, now)
    }

    private fun draw(g: GuiGraphicsExtractor, list: List<Row>, now: Long) {
        if (list.isEmpty()) return
        val s = panelScale
        val size = 10f * s
        val lineH = 12f * s
        val pad = 6f * s
        val gap = 10f * s
        val times = list.map { if (it.active) "ends " + Countdown.format(it.end - now, 3) else Countdown.format(it.start - now) }
        val clocks = list.map { if (showClock.value) Countdown.clock(if (it.active) it.end else it.start, now) else "" }
        val leftW = list.maxOf { HudCard.width(it.name, size) }
        val midW = times.maxOf { HudCard.width(it, size) }
        val rightW = clocks.maxOf { HudCard.width(it, size) }
        val width = HudCard.steady("Event Countdown", leftW + gap + midW + (if (rightW > 0f) gap + rightW else 0f) + pad * 2, s)
        val height = list.size * lineH + pad * 2 - (lineH - size)
        lastWidth = (width / s).toInt()
        lastHeight = (height / s).toInt()
        HudCard.draw(g, panelX, panelY) {
            HudCard.card(width, height, s)
            list.forEachIndexed { i, row ->
                val y = pad + i * lineH
                HudCard.text(row.name, pad, y, size, row.color)
                val timeColor = if (row.active) 0xFF55FF55.toInt() else 0xFFFFFFFF.toInt()
                HudCard.text(times[i], pad + leftW + gap, y, size, timeColor)
                if (rightW > 0f) HudCard.text(clocks[i], width - pad - HudCard.width(clocks[i], size), y, size, 0xFFAAAAAA.toInt())
            }
        }
    }

    override val label = "Event Countdown"
    private const val DEFAULT_X = 3
    private const val DEFAULT_Y = 200
    override var panelX = DEFAULT_X
    override var panelY = DEFAULT_Y
    override var panelScale = 1f
    override var lastWidth = 0
        private set
    override var lastHeight = 0
        private set

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val gson = Gson()
    private val hudFile by lazy { CedPaths.hud("event_countdown") }

    override fun save() {
        runCatching { hudFile.parentFile?.mkdirs(); hudFile.writeText(gson.toJson(HudPosition(panelX, panelY, panelScale))) }
    }

    override fun resetPosition() { panelX = DEFAULT_X; panelY = DEFAULT_Y; panelScale = 1f }
    override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.4f, 3f) }

    init {
        addSettings(maxRows, showClock, hideFar, *events.map { it.toggle }.toTypedArray())
        runCatching { gson.fromJson(hudFile.readText(), HudPosition::class.java) }.getOrNull()?.let {
            panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.4f, 3f)
        }
    }
}
