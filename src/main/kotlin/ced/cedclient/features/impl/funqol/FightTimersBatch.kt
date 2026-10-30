package ced.cedclient.features.impl.funqol

import ced.cedclient.events.ChatMessageEvent
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.Minecraft
import net.minecraft.client.DeltaTracker
import com.google.gson.Gson
import ced.cedclient.config.CedPaths
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.state.DungeonState
import ced.cedclient.state.HotbarDungeonMap
import ced.cedclient.state.HotbarDungeonMap.RoomState
import ced.cedclient.utils.Color
import ced.cedclient.utils.PingTracker
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents

/** The F7 boss counters, shared by the four tick timer HUDs. A counter only starts if its HUD is enabled. */
private object FightTicks {
    var start = -1
    var goldor = -1
    var pad = -1
    var py = -1
    private var pyTriggered = false
    private var stormActive = false
    private var world: Any? = null

    fun init() {} // touching the object runs the listeners below

    private fun reset() {
        start = -1; goldor = -1; pad = -1; py = -1; pyTriggered = false; stormActive = false
    }

    init {
        on<ChatMessageEvent> {
            when (it.unformattedText.trim()) {
                "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!" -> if (TickTimers.on(TickTimers.showMaxor)) start = 83
                "[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!" ->
                    if (TickTimers.on(TickTimers.showPy) && !pyTriggered) { pyTriggered = true; py = 62 }
                "[BOSS] Storm: Pathetic Maxor, just like expected." -> if (TickTimers.on(TickTimers.showPad)) { pad = 20; stormActive = true }
                "[BOSS] Storm: I should have known that I stood no chance." -> { pyTriggered = false; py = -1; stormActive = false; pad = -1 }
                "[BOSS] Goldor: Who dares trespass into my domain?" -> if (TickTimers.on(TickTimers.showGoldor)) goldor = 60
                "The Core entrance is opening!" -> goldor = -1
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (mc.level !== world) { world = mc.level; reset() }
            if (start >= 0) start--
            if (stormActive && pad >= 0) { pad--; if (pad <= 0) pad = 20 }
            if (py >= 0) py--
            if (goldor >= 0) { goldor--; if (goldor == 0) goldor = 60 }
        }
    }
}

/**
 * The F7 tick counters that Hypixel does not show, as one module with a HUD panel per counter: Maxor's start (83),
 * Goldor's death tick (60, loops), Storm's PY (62) and Storm's pad (20, loops), each started by the boss's chat line.
 * Counted in client ticks, which run at the same 20 per second. Unique: an optional ping compensation, the colour fades
 * from green through gold to red as a counter runs down, and it can be shown in seconds or ticks.
 */
object TickTimers : Module(
    "Tick Timers", Category.Skyblock,
    "Tick counters for the F7 boss fight (Maxor start, Goldor death ticks, Storm PY and pad), each its own HUD panel."
) {
    val showMaxor = BooleanSetting("Maxor Start", true, "Counts down Maxor's start (83 ticks).")
    val showGoldor = BooleanSetting("Goldor Death Ticks", true, "Goldor's 60 tick loop once he starts, until the core opens.")
    val showPy = BooleanSetting("Storm PY", true, "Storm's PY counter (62 ticks).")
    val showPad = BooleanSetting("Storm Pad", true, "Storm's 20 tick pad loop.")
    val format = DropdownSetting("Format", listOf("Ticks", "Seconds"), "Ticks", "Show the counters as ticks or seconds.")
    val showPrefix = BooleanSetting("Show Prefix", true, "Text in front of the number.")
    val maxorPrefix = TextSetting("Maxor Prefix", "Start", "The text in front of Maxor's number.").withDependency { showPrefix.value }
    val goldorPrefix = TextSetting("Goldor Prefix", "Goldor", "The text in front of Goldor's number.").withDependency { showPrefix.value }
    val pyPrefix = TextSetting("PY Prefix", "PY", "The text in front of the PY number.").withDependency { showPrefix.value }
    val padPrefix = TextSetting("Pad Prefix", "Pad", "The text in front of the pad number.").withDependency { showPrefix.value }
    val showSuffix = BooleanSetting("Show Suffix", true, "Text after the number (the unit by default).")
    val suffixText = TextSetting("Suffix", "", "The text after the number. Empty = the unit (t or s).").withDependency { showSuffix.value }
    val pingFix = BooleanSetting("Ping Compensation", false, "Subtract half your ping (in ticks) so the number matches the server a little better.")
    val outline = BooleanSetting("Text Outline", false, "A black outline around the text so it reads on any background.")
    val high = ColorSetting("High Color", Color(85, 255, 85), "More than two thirds left.")
    val mid = ColorSetting("Mid Color", Color(255, 170, 0), "Between a third and two thirds left.")
    val low = ColorSetting("Low Color", Color(255, 85, 85), "Less than a third left.")

    /** Touching the panels here makes sure they (and the counters) exist from startup. */
    val panels: List<HudElement> = listOf(MaxorPanel, GoldorPanel, StormPyPanel, StormPadPanel)

    /** [setting] is on and the module is too. */
    fun on(setting: BooleanSetting) = isEnabled && setting.value

    init {
        FightTicks.init()
        addSettings(showMaxor, showGoldor, showPy, showPad, format, showPrefix, maxorPrefix, goldorPrefix, pyPrefix, padPrefix,
            showSuffix, suffixText, pingFix, outline, high, mid, low)
    }
}

/** One tick counter's HUD panel (a module-less HudElement owned by [TickTimers]). */
abstract class TickPanel(
    override val label: String, private val key: String, private val defY: Int,
    private val rowLabel: () -> String, private val max: Int, private val sample: Int,
    private val toggle: BooleanSetting, private val ticks: () -> Int
) : HudElement {
    override var panelX = 3
    override var panelY = defY
    override var panelScale = 1f
    override var lastWidth = 0
        protected set
    override var lastHeight = 0
        protected set
    override val hudActive get() = TickTimers.on(toggle)

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val gson = Gson()
    private val hudFile by lazy { CedPaths.hud(key) }

    override fun save() {
        runCatching { hudFile.parentFile?.mkdirs(); hudFile.writeText(gson.toJson(HudPosition(panelX, panelY, panelScale))) }
    }

    override fun resetPosition() { panelX = 3; panelY = defY; panelScale = 1f }
    override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.4f, 3f) }

    private fun shown(value: Int): String {
        val adjusted = if (TickTimers.pingFix.value) (value - PingTracker.latest().coerceAtLeast(0) / 100).coerceAtLeast(0) else value
        val seconds = TickTimers.format.value == "Seconds"
        val number = if (seconds) "%.2f".format(java.util.Locale.ROOT, adjusted / 20.0) else adjusted.toString()
        val suffix = if (!TickTimers.showSuffix.value) "" else TickTimers.suffixText.value.ifEmpty { if (seconds) "s" else "t" }
        return number + suffix
    }

    private fun row(value: Int): Triple<String, String, Int> {
        val f = value.toFloat() / max
        val color = (if (f > 0.66f) TickTimers.high else if (f > 0.33f) TickTimers.mid else TickTimers.low).value.rgba
        return Triple(if (TickTimers.showPrefix.value) rowLabel() else "", shown(value), color)
    }

    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") tickCounter: DeltaTracker) {
        val mc = Minecraft.getInstance()
        if (!hudActive || mc.gui.hud.isHidden || !DungeonState.inDungeon) return
        val v = ticks()
        if (v >= 0) draw(g, row(v))
    }

    override fun renderInternal(g: GuiGraphicsExtractor) {
        val v = ticks()
        draw(g, row(if (v >= 0) v else sample))
    }

    private fun text(t: String, x: Float, y: Float, size: Float, color: Int, s: Float) {
        if (TickTimers.outline.value) {
            val o = 0.9f * s
            for ((dx, dy) in listOf(-o to 0f, o to 0f, 0f to -o, 0f to o, -o to -o, o to o, -o to o, o to -o)) HudCard.text(t, x + dx, y + dy, size, 0xFF000000.toInt())
        }
        HudCard.text(t, x, y, size, color)
    }

    private fun draw(g: GuiGraphicsExtractor, line: Triple<String, String, Int>) {
        val s = panelScale
        val size = 10f * s
        val pad = 6f * s
        val gap = 10f * s
        val width = HudCard.steady(label, HudCard.width(line.first, size) + gap + HudCard.width(line.second, size) + pad * 2, s)
        val height = size + pad * 2
        lastWidth = (width / s).toInt()
        lastHeight = (height / s).toInt()
        HudCard.draw(g, panelX, panelY) {
            HudCard.card(width, height, s)
            text(line.first, pad, pad, size, line.third, s)
            text(line.second, width - pad - HudCard.width(line.second, size), pad, size, line.third, s)
        }
    }

    init {
        runCatching { gson.fromJson(hudFile.readText(), HudPosition::class.java) }.getOrNull()?.let {
            panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.4f, 3f)
        }
    }
}

object MaxorPanel : TickPanel("Maxor Start Timer", "tick_maxor", 120, { TickTimers.maxorPrefix.value }, 83, 60, TickTimers.showMaxor, { FightTicks.start })
object GoldorPanel : TickPanel("Goldor Death Ticks", "tick_goldor", 136, { TickTimers.goldorPrefix.value }, 60, 35, TickTimers.showGoldor, { FightTicks.goldor })
object StormPyPanel : TickPanel("Storm PY Timer", "tick_py", 152, { TickTimers.pyPrefix.value }, 75, 20, TickTimers.showPy, { FightTicks.py })
object StormPadPanel : TickPanel("Storm Pad Timer", "tick_pad", 168, { TickTimers.padPrefix.value }, 20, 6, TickTimers.showPad, { FightTicks.pad })

/**
 * The 30 second wait after entering a dungeon before you can start another one, counted from the floor announcement.
 * Unique: a ready ping (title and sound) when it runs out, and an option to hide the counter once you are in a dungeon.
 */
object WarpCooldown : RowHud("Warp Cooldown", "How long until you can start another dungeon run.", "warp_cooldown", 3, 150) {
    private val seconds = NumberSetting("Cooldown (s)", 30.0, 5.0, 120.0, 1.0, "The wait after entering a dungeon.")
    private val ping = BooleanSetting("Ready Ping", false, "A title and sound when the wait is over.")
    private val hideInside = BooleanSetting("Hide While In Dungeon", false, "Only show the counter outside a dungeon.")

    private val ENTER = Regex("""entered (?:MM )?The Catacombs, Floor [^!]+!""")
    private var readyAt = 0L
    private var alerted = true

    override fun rows(): List<Triple<String, String, Int>>? {
        if (!isEnabled || (hideInside.value && DungeonState.inDungeon)) return null
        val left = readyAt - System.currentTimeMillis()
        if (left <= 0) return null
        return listOf(Triple("Dungeon Warp", "%.1fs".format(java.util.Locale.ROOT, left / 1000.0), 0xFFFFAA00.toInt()))
    }

    override fun preview() = listOf(Triple("Dungeon Warp", "12.4s", 0xFFFFAA00.toInt()))

    init {
        addSettings(seconds, ping, hideInside)
        loadPosition()
        on<ChatMessageEvent> {
            if (isEnabled && ENTER.containsMatchIn(it.unformattedText)) {
                readyAt = System.currentTimeMillis() + (seconds.value * 1000).toLong()
                alerted = false
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register {
            if (isEnabled && ping.value && !alerted && System.currentTimeMillis() >= readyAt) {
                alerted = true
                Alerts.title("Warp Cooldown", "Warp ready", "you can start a run", 0x55FF55, true, 1.3f)
            }
        }
    }
}

/**
 * Alerts for the room you stand in: a title when it is cleared (the map square turns green / white) and when its secrets are
 * all found (the "5/5 Secrets" action bar line). Unique: the cleared title carries the time you spent in the room, and
 * the two titles never fire within a configurable gap of each other.
 */
object RoomAlerts : Module(
    "Room Alerts",
    Category.Dungeons,
    "Titles when your current room is cleared and when its secrets are all found."
) {
    private val cleared = BooleanSetting("Cleared", true, "A title when the room's map square is cleared.")
    private val secrets = BooleanSetting("Secrets Done", true, "A title when the action bar shows all secrets of the room found.")
    private val showTime = BooleanSetting("Show Time In Room", true, "Put the time spent in the room in the cleared title.")
    private val sound = BooleanSetting("Sound", true, "A pling with each title.")
    private val gapSeconds = NumberSetting("Gap (s)", 3.0, 0.5, 10.0, 0.5, "At least this long between two titles.")

    private val SECRETS = Regex("""(\d+)/(\d+) Secrets""")
    private const val START = -185
    private var lastTile: Pair<Int, Int>? = null
    private var lastAlert = 0L

    /**
     * One stay in a room. Walking back in within [REENTER_MS] (standing on a room edge, a lag spike moving you across it)
     * continues the same visit, so the time keeps counting and nothing is announced twice.
     */
    private class Visit(val enteredAt: Long, var lastSeen: Long) {
        /** The map showed this room not cleared while you were in it: only then does it turning cleared mean you did it. */
        var sawUncleared = false
    }
    private val visits = HashMap<Pair<Int, Int>, Visit>()
    // Each room gets each title at most once per run, whatever the tile or the action bar does.
    private val secretsAlerted = HashSet<Pair<Int, Int>>()
    private val clearedAlerted = HashSet<Pair<Int, Int>>()
    private const val REENTER_MS = 4000L

    private fun gap() = System.currentTimeMillis() - lastAlert > gapSeconds.value * 1000

    private fun reset() {
        visits.clear(); secretsAlerted.clear(); clearedAlerted.clear(); lastTile = null
    }

    init {
        addSettings(cleared, secrets, showTime, sound, gapSeconds)
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!isEnabled || !overlay || !secrets.value || !DungeonState.inDungeon) return@register
            val m = SECRETS.find(message.string.replace(Regex("§."), "")) ?: return@register
            val done = m.groupValues[1].toInt()
            val total = m.groupValues[2].toInt()
            // The bar repeats this line all the time and flickers around lag spikes: once per room is all you get.
            val tile = lastTile ?: return@register
            if (total > 0 && done == total && tile !in secretsAlerted && gap()) {
                secretsAlerted += tile
                lastAlert = System.currentTimeMillis()
                Alerts.title("Room Alerts", "Secrets Done", "$done/$total", 0x55FF55, sound.value, 1.2f)
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (!isEnabled) return@register
            if (!DungeonState.inDungeon) { reset(); return@register }
            val player = mc.player ?: return@register
            val now = System.currentTimeMillis()
            val tile = Math.floorDiv(player.x.toInt() - START + 16, 32) to Math.floorDiv(player.z.toInt() - START + 16, 32)
            if (tile != lastTile) {
                lastTile = tile
                // Coming straight back keeps the old visit (and its clock); a real return later starts a fresh one.
                val old = visits[tile]
                if (old == null || now - old.lastSeen > REENTER_MS) visits[tile] = Visit(now, now)
            }
            val visit = visits[tile] ?: return@register
            visit.lastSeen = now
            if (!cleared.value) return@register // the tile above is still tracked: the secrets title needs it
            val state =HotbarDungeonMap.roomStates[tile] ?: return@register // the map hasn't shown this room yet
            if (state != RoomState.CLEARED && state != RoomState.GREEN) {
                // Any not-cleared state counts, not just "opened": the map can skip straight from grey to cleared.
                visit.sawUncleared = true
                return@register
            }
            // A room that was already cleared when you walked in (by you earlier, or by a teammate) doesn't get a title.
            if (!visit.sawUncleared || tile in clearedAlerted) return@register
            clearedAlerted += tile
            if (gap()) {
                lastAlert = now
                val time = if (showTime.value) "%.1fs in room".format(java.util.Locale.ROOT, (now - visit.enteredAt) / 1000.0) else ""
                Alerts.title("Room Alerts", "Cleared", time, 0x55FFFF, sound.value, 1.0f)
            }
        }
    }
}
