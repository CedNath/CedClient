package ced.cedclient.features.impl.funqol

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.state.DungeonState
import ced.cedclient.state.HotbarDungeonMap
import ced.cedclient.state.HotbarDungeonMap.RoomState
import ced.cedclient.utils.Color
import ced.cedclient.utils.PingTracker
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents

/**
 * F7 tick counters that Hypixel does not show: Maxor's start (83), Goldor's death tick (60, loops), Storm's pad (20, loops)
 * and Storm's PY (62), each started by the boss's chat line. Counted in client ticks, which run at the same 20 per second.
 * Unique: an optional ping compensation, the colour fades from green through gold to red as a counter runs down, and
 * every counter can be shown in seconds or ticks.
 */
object TickTimers : RowHud("Tick Timers", "Tick counters for the F7 boss fight: Maxor start, Goldor death ticks, Storm pad and PY.", "tick_timers", 3, 120) {
    private val format = DropdownSetting("Format", listOf("Ticks", "Seconds"), "Ticks", "Show counters as ticks or seconds.")
    private val showPrefix = BooleanSetting("Show Label", true, "The name in front of the number.")
    private val maxor = BooleanSetting("Maxor Start", true, "Counts down Maxor's start (83 ticks).")
    private val goldor = BooleanSetting("Goldor Death Ticks", true, "Goldor's 60 tick loop once he starts, until the core opens.")
    private val pad = BooleanSetting("Storm Pad Timer", true, "Storm's 20 tick pad loop.")
    private val py = BooleanSetting("Storm PY Timer", true, "Storm's PY counter (62 ticks).")
    private val pingFix = BooleanSetting("Ping Compensation", false, "Subtract half your ping (in ticks) so the number matches the server a little better.")
    private val high = ColorSetting("High Color", Color(85, 255, 85), "More than two thirds left.")
    private val mid = ColorSetting("Mid Color", Color(255, 170, 0), "Between a third and two thirds left.")
    private val low = ColorSetting("Low Color", Color(255, 85, 85), "Less than a third left.")

    private var startTicks = -1
    private var goldorTicks = -1
    private var padTicks = -1
    private var pyTicks = -1
    private var pyTriggered = false
    private var stormActive = false
    private var world: Any? = null

    private fun reset() {
        startTicks = -1; goldorTicks = -1; padTicks = -1; pyTicks = -1; pyTriggered = false; stormActive = false
    }

    private fun shown(value: Int): String {
        val adjusted = if (pingFix.value) (value - PingTracker.latest().coerceAtLeast(0) / 100).coerceAtLeast(0) else value
        return if (format.value == "Seconds") "%.2fs".format(java.util.Locale.ROOT, adjusted / 20.0) else "${adjusted}t"
    }

    private fun row(label: String, value: Int, max: Int): Triple<String, String, Int> {
        val f = value.toFloat() / max
        val color = (if (f > 0.66f) high else if (f > 0.33f) mid else low).value.rgba
        return Triple(if (showPrefix.value) label else "", shown(value), color)
    }

    override fun rows(): List<Triple<String, String, Int>>? {
        if (!isEnabled || !DungeonState.inDungeon) return null
        val out = ArrayList<Triple<String, String, Int>>()
        if (startTicks >= 0) out += row("Start", startTicks, 83)
        if (goldorTicks >= 0) out += row("Goldor", goldorTicks, 60)
        if (pyTicks >= 0) out += row("PY", pyTicks, 75)
        if (padTicks >= 0) out += row("Pad", padTicks, 20)
        return out.ifEmpty { null }
    }

    override fun preview() = listOf(
        row("Start", 60, 83), row("Goldor", 35, 60), row("PY", 20, 75), row("Pad", 6, 20)
    )

    init {
        addSettings(format, showPrefix, maxor, goldor, pad, py, pingFix, high, mid, low)
        loadPosition()
        on<ChatMessageEvent> {
            if (!isEnabled) return@on
            when (it.unformattedText.trim()) {
                "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!" -> if (maxor.value) startTicks = 83
                "[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!" ->
                    if (py.value && !pyTriggered) { pyTriggered = true; pyTicks = 62 }
                "[BOSS] Storm: Pathetic Maxor, just like expected." -> if (pad.value) { padTicks = 20; stormActive = true }
                "[BOSS] Storm: I should have known that I stood no chance." -> { pyTriggered = false; pyTicks = -1; stormActive = false; padTicks = -1 }
                "[BOSS] Goldor: Who dares trespass into my domain?" -> if (goldor.value) goldorTicks = 60
                "The Core entrance is opening!" -> goldorTicks = -1
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (mc.level !== world) { world = mc.level; reset() }
            if (startTicks >= 0) startTicks--
            if (stormActive && padTicks >= 0) { padTicks--; if (padTicks <= 0) padTicks = 20 }
            if (pyTicks >= 0) pyTicks--
            if (goldorTicks >= 0) { goldorTicks--; if (goldorTicks == 0) goldorTicks = 60 }
        }
    }
}

/**
 * The 30 second wait after entering a dungeon before you can start another one, counted from the floor announcement.
 * Unique: a ready ping (title and sound) when it runs out, and an option to hide the counter once you are in a dungeon.
 */
object WarpCooldown : RowHud("Warp Cooldown", "How long until you can start another dungeon run.", "warp_cooldown", 3, 150) {
    private val seconds = NumberSetting("Cooldown (s)", 30.0, 5.0, 120.0, 1.0, "The wait after entering a dungeon.")
    private val ping = BooleanSetting("Ready Ping", true, "A title and sound when the wait is over.")
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
    private var enteredAt = 0L
    private var lastTile: Pair<Int, Int>? = null
    private var lastAlert = 0L
    private var secretsRoomDone = false
    private var wasCleared = false

    private fun gap() = System.currentTimeMillis() - lastAlert > gapSeconds.value * 1000

    init {
        addSettings(cleared, secrets, showTime, sound, gapSeconds)
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!isEnabled || !overlay || !secrets.value || !DungeonState.inDungeon) return@register
            val m = SECRETS.find(message.string.replace(Regex("§."), "")) ?: return@register
            val done = m.groupValues[1].toInt()
            val total = m.groupValues[2].toInt()
            if (total > 0 && done == total) {
                if (!secretsRoomDone && gap()) {
                    secretsRoomDone = true
                    lastAlert = System.currentTimeMillis()
                    Alerts.title("Room Alerts", "Secrets Done", "$done/$total", 0x55FF55, sound.value, 1.2f)
                }
            } else secretsRoomDone = false
        }
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (!isEnabled || !cleared.value || !DungeonState.inDungeon) return@register
            val player = mc.player ?: return@register
            val tile = Math.floorDiv(player.x.toInt() - START + 16, 32) to Math.floorDiv(player.z.toInt() - START + 16, 32)
            if (tile != lastTile) { lastTile = tile; enteredAt = System.currentTimeMillis(); wasCleared = false; secretsRoomDone = false }
            val state = HotbarDungeonMap.roomStates[tile]
            val done = state == RoomState.CLEARED || state == RoomState.GREEN
            if (!done) { wasCleared = false; return@register }
            if (!wasCleared) {
                wasCleared = true
                if (gap()) {
                    lastAlert = System.currentTimeMillis()
                    val time = if (showTime.value) "%.1fs in room".format(java.util.Locale.ROOT, (System.currentTimeMillis() - enteredAt) / 1000.0) else ""
                    Alerts.title("Room Alerts", "Cleared", time, 0x55FFFF, sound.value, 1.0f)
                }
            }
        }
    }
}
