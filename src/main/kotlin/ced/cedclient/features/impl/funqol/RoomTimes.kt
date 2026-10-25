package ced.cedclient.features.impl.funqol

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.state.DungeonScanner
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.safely
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ced.cedclient.features.settings.Setting.Companion.withDependency
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.world.InteractionResult
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import java.io.File

/**
 * Times the secrets of each dungeon room: from stepping into the room to the first secret, between every two
 * secrets, and to the last one. Secrets are counted from the action bar's "x/y Secrets" line for the room you stand
 * in (whoever finds them). A compact HUD shows the room you are in, /cc room time prints it, and /cc room time list
 * opens every room of the run with its splits. Starts over in each new dungeon world; the data stays after the run.
 */
object RoomTimes : Module(
    "Room Times",
    Category.Skyblock,
    "Times dungeon room secrets: entering the room to the first secret, between secrets, to the last one. HUD, /cc room time, /cc room time list."
), HudElement {
    private val hud = BooleanSetting("HUD", false, "Compact timer for the room you are in.")
    private val startTimer = DropdownSetting("Start Timer", listOf("Room Enter", "First Secret"), "Room Enter",
        "Room Enter: times start when you step into the room (the first split is enter to the first secret). First Secret: they start at the first secret.")
    private val chatTimes = BooleanSetting("Times In Chat", false, "Prints a room's time (only to you) when its last secret is found.")
    private val onlyPb = BooleanSetting("Only On PB", false, "Times In Chat: only when the room's time beats your best for that room.").withDependency { chatTimes.value }
    private val resetButton = ActionSetting("Reset Saved Times", "Deletes every saved best room time (click twice within 5 seconds).") { resetPbs() }
    private val openListButton = ActionSetting("Room Time List", "Opens the list of every room this run with its secret splits (same as /cc room time list).") { openList = true }
    private val debug = BooleanSetting("Debug", false, "Prints to chat (only to you) every room change, own action and secret count, and why a room got marked shared.")
    private val fromFirstSecret get() = startTimer.value == "First Secret"

    /** One room of the run. [found] = when each secret was found (ms, in order). */
    class Room(val name: String, var total: Int, val enteredAt: Long) {
        val found = ArrayList<Long>()

        /** A secret was found that wasn't yours (no click / hit / pickup of yours, or several at once, e.g. some were already done when you came in): the times aren't counted. */
        var shared = false
        var reported = false
        /** When [shared] was last set by a secret that might still turn out to be yours (the pickup packet can land just after the action bar), else 0. */
        var maybeSharedAt = 0L
        val done get() = total > 0 && found.size >= total

        /** When this room's timer started: stepping in, or the first secret (Start Timer); null until that happens. */
        val startedAt: Long? get() = if (RoomTimes.fromFirstSecret) found.firstOrNull() else enteredAt

        /** Time of each step: [startedAt] -> 1st secret (Room Enter only), 1st -> 2nd, ... */
        val splits: List<Long> get() = if (RoomTimes.fromFirstSecret) found.zipWithNext { a, b -> b - a }
            else found.mapIndexed { i, t -> t - (if (i == 0) enteredAt else found[i - 1]) }

        /** [startedAt] to the last secret found so far. */
        val totalTime: Long? get() = startedAt?.let { s -> found.lastOrNull()?.let { it - s } }
    }

    /** Rooms with secrets entered this run, in the order first entered. */
    val rooms = LinkedHashMap<String, Room>()
    private var current: Room? = null
    private var runLevel: ClientLevel? = null
    private var scanDelay = 0
    /** Last thing you did that can pick up a secret (click a block / entity, hit something, gain an item), ms. */
    private var lastOwnAction = 0L
    private var lastItems = 0
    private var lastOwnSource = "none"
    /** Times of recent own actions, to tell how many secrets in one action bar update can be yours. */
    private val ownTimes = ArrayDeque<Long>()

    /** A room change waits SWITCH_DELAY before it counts (name null = left every room): standing on a room edge must not flip rooms. */
    private var pendingName: String? = null
    private var pendingSince = 0L
    private var hasPending = false
    private const val SWITCH_DELAY = 450L

    /** The action bar lags a room change by a couple of seconds and keeps showing the old room's count: that count is ignored. */
    private var lastBarFound = -1
    private var lastBarTotal = -1
    private var staleFound = -1
    private var staleTotal = -1
    private var staleUntil = 0L
    private const val STALE_MS = 3000L

    private fun dbg(msg: String) {
        if (debug.value) mc.player?.sendSystemMessage(ChatPrefix.msgFor("Room Times", "§8[debug] §7$msg"))
    }

    private fun own(source: String) {
        val now = System.currentTimeMillis()
        lastOwnAction = now
        lastOwnSource = source
        ownTimes.addLast(now)
        while (ownTimes.isNotEmpty() && now - ownTimes.first() > 3000) ownTimes.removeFirst()
        dbg("own action: $source")
    }
    @Volatile private var openList = false

    private val CODES = Regex("§.")
    private val SECRETS = Regex("""(\d+)/(\d+) Secrets""")
    private const val START = -185

    private val mc get() = Minecraft.getInstance()

    init {
        SecretSignals.ensure()
        addSettings(hud, startTimer, chatTimes, onlyPb, openListButton, resetButton, debug)

        ClientTickEvents.END_CLIENT_TICK.register { safely("Room Times") {
            if (openList) { openList = false; mc.setScreen(RoomTimesScreen()) }
            val level = mc.level ?: return@register
            val player = mc.player ?: return@register
            if (!isEnabled || !DungeonState.active || DungeonState.inBoss) { current = null; return@register }
            if (level !== runLevel) { runLevel = level; rooms.clear(); current = null }
            if (scanDelay-- <= 0) { scanDelay = 10; DungeonScanner.scan(level, player.x, player.z) }
            val info = DungeonScanner.room(Math.floorDiv(player.x.toInt() - START + 16, 32), Math.floorDiv(player.z.toInt() - START + 16, 32))
            val target = info?.takeIf { it.secrets > 0 }
            if (target?.name == current?.name) { hasPending = false; return@register }
            val now = System.currentTimeMillis()
            if (!hasPending || pendingName != target?.name) { hasPending = true; pendingName = target?.name; pendingSince = now; return@register }
            if (now - pendingSince < SWITCH_DELAY) return@register
            hasPending = false
            staleFound = lastBarFound; staleTotal = lastBarTotal; staleUntil = now + STALE_MS
            current = target?.let { t -> rooms.getOrPut(t.name) { Room(t.name, t.secrets, pendingSince) } }
            if (target != null) dbg("in room ${target.name} (${target.secrets} secrets, shared=${current?.shared}, found=${current?.found?.size})")
        } }
        // Items picked up are the other way a secret reaches you (the count only goes up).
        ClientTickEvents.END_CLIENT_TICK.register {
            val inv = mc.player?.inventory ?: return@register
            val items = (0 until 41).sumOf { inv.getItem(it).count }
            if (items > lastItems) own("inventory +${items - lastItems}")
            lastItems = items
        }
        UseBlockCallback.EVENT.register(UseBlockCallback { _, level, _, _ ->
            if (level.isClientSide) own("block click")
            InteractionResult.PASS
        })
        UseEntityCallback.EVENT.register(UseEntityCallback { _, level, _, _, _ ->
            if (level.isClientSide) own("entity use")
            InteractionResult.PASS
        })
        AttackEntityCallback.EVENT.register(AttackEntityCallback { _, level, _, _, _ ->
            if (level.isClientSide) own("entity hit")
            InteractionResult.PASS
        })

        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!overlay || !isEnabled) return@register
            val m = SECRETS.find(message.string.replace(CODES, "")) ?: return@register
            val found = m.groupValues[1].toInt()
            val barTotal = m.groupValues[2].toInt()
            lastBarFound = found; lastBarTotal = barTotal
            val room = current ?: return@register
            val now = System.currentTimeMillis()
            // Room change under way, or the bar still showing the room you just left: not this room's count.
            if (hasPending) return@register
            if (now < staleUntil && ((room.total > 0 && barTotal != room.total) || (found == staleFound && barTotal == staleTotal))) {
                dbg("action bar $found/$barTotal ignored (old room's count)")
                return@register
            }
            room.total = barTotal
            // A new secret you didn't just reach for (click, hit, pick up) was someone else's: cancel the room's times.
            // Several can land between two bar updates, as long as you did at least as many actions.
            val delta = found - room.found.size
            val recent = ownTimes.count { now - it < 2500 }
            if (delta != 0) dbg("action bar $found/$barTotal (had ${room.found.size}); last own action: $lastOwnSource ${now - lastOwnAction}ms ago, $recent own in 2.5s -> " +
                if (delta < 0) "no new secret" else if (recent == 0) "SHARED: no own action in 2.5s" else if (delta > recent) "SHARED: $delta secrets but only $recent own actions" else "yours")
            if (delta > 0 && (recent == 0 || delta > recent)) {
                if (!room.shared && delta == 1) room.maybeSharedAt = now
                room.shared = true
            }
            while (room.found.size < found) room.found += now
            if (room.done && !room.reported) onDone(room)
        }
    }

    /** A secret item / bat reached you (see SecretSignals), whatever your inventory says. */
    fun onOwnSecret(source: String) {
        if (!isEnabled) return
        val now = System.currentTimeMillis()
        // Counts as an action like a click: the pickup often lands before the action bar's count.
        own(source)
        current?.let { if (it.shared && now - it.maybeSharedAt < 1500) { it.shared = false; it.maybeSharedAt = 0L } }
    }

    private val pbFile: File by lazy { CedPaths.file("room_times_pb.json") }

    /** Best time per room (key: room name + start mode), ms. */
    private val pbs: HashMap<String, Long> by lazy {
        runCatching { gson.fromJson<HashMap<String, Long>>(pbFile.readText(), object : TypeToken<HashMap<String, Long>>() {}.type) }.getOrNull() ?: HashMap()
    }

    private var resetAskedAt = 0L

    /** First click asks, a second within 5 s wipes the saved bests (this run's list is left alone). */
    private fun resetPbs() {
        val now = System.currentTimeMillis()
        if (now - resetAskedAt > 5000) {
            resetAskedAt = now
            mc.player?.sendSystemMessage(ChatPrefix.msgFor("Room Times", "§eClick Reset Saved Times again within 5 seconds to delete ${pbs.size} saved room time(s)"))
            return
        }
        resetAskedAt = 0L
        val count = pbs.size
        pbs.clear()
        runCatching { pbFile.delete() }
        mc.player?.sendSystemMessage(ChatPrefix.msgFor("Room Times", "§7Deleted §f$count §7saved room time(s)"))
    }

    /** All secrets of a room found: keep the PB (only when the time is all yours) and say it in chat if asked. */
    private fun onDone(room: Room) {
        room.reported = true
        if (room.shared) return
        val time = room.totalTime ?: return
        if (fromFirstSecret && room.found.size < 2) return // the timer would read 0
        val key = "${room.name}|${if (fromFirstSecret) "s" else "e"}"
        val old = pbs[key]
        val pb = old == null || time < old
        if (pb) { pbs[key] = time; runCatching { pbFile.parentFile?.mkdirs(); pbFile.writeText(gson.toJson(pbs)) } }
        if (!chatTimes.value || (onlyPb.value && !pb)) return
        fun sec(ms: Long) = "%.1fs".format(ms / 1000.0)
        val note = if (pb) " §d(New PB" + (old?.let { ", was ${sec(it)}" } ?: "") + ")" else " §8(PB ${sec(old!!)})"
        mc.player?.sendSystemMessage(ChatPrefix.msgFor("Room Times", "§f${room.name} §7done in §e${sec(time)}$note"))
    }

    /** Label of the [i]th split (0-based) for the current Start Timer mode. */
    fun splitLabel(i: Int) = if (fromFirstSecret) "${i + 1} -> ${i + 2}" else if (i == 0) "enter -> 1" else "$i -> ${i + 1}"

    /** Whether the list shows a 1st -> last chip (only worth it when the total starts at room enter). */
    val showFirstToLast get() = !fromFirstSecret

    fun fmt(ms: Long): String {
        val t = ms.coerceAtLeast(0) / 100
        return if (t >= 600) "%d:%02d.%d".format(t / 600, t / 10 % 60, t % 10) else "%d.%ds".format(t / 10, t % 10)
    }

    // ------------------------------------------------------------------
    // Command: /cc room time [list]
    // ------------------------------------------------------------------

    fun commandTree(): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal("room").then(
            ClientCommands.literal("time")
                .executes { ctx -> print(ctx.source); 1 }
                .then(ClientCommands.literal("list").executes { ctx ->
                    if (!isEnabled) ctx.source.sendFeedback(ChatPrefix.msgFor("Room Times", "§cRoom Times is off")) else openList = true
                    1
                })
        )

    private fun print(source: FabricClientCommandSource) {
        val room = current
        when {
            !isEnabled -> source.sendFeedback(ChatPrefix.msgFor("Room Times", "§cRoom Times is off"))
            room == null -> source.sendFeedback(ChatPrefix.msgFor("Room Times", "§7Not in a room with secrets (§f/cc room time list §7for the run)"))
            room.shared -> source.sendFeedback(ChatPrefix.msgFor("Room Times", "§f${room.name} §7- a secret here wasn't yours, not timed"))
            else -> {
                val now = System.currentTimeMillis()
                val start = room.startedAt
                val time = if (start == null) "waiting for the first secret" else fmt(if (room.done) room.totalTime!! else now - start)
                source.sendFeedback(ChatPrefix.msgFor("Room Times", "§f${room.name} §7- §e$time §7in room, §a${room.found.size}§7/${room.total} secrets" + if (room.done) " §a(done)" else ""))
                if (room.found.isNotEmpty()) source.sendFeedback(net.minecraft.network.chat.Component.literal(
                    "§7Splits: " + room.splits.mapIndexed { i, s -> "§8#${i + 1} §f${fmt(s)}" }.joinToString("§7, ")))
            }
        }
    }

    // ------------------------------------------------------------------
    // HUD
    // ------------------------------------------------------------------

    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") tickCounter: DeltaTracker) {
        if (!isEnabled || !hud.value || mc.options.hideGui || !DungeonState.active || DungeonState.inBoss || current == null) return
        renderInternal(g)
    }

    override fun renderInternal(g: GuiGraphicsExtractor) {
        val room = current
        val now = System.currentTimeMillis()
        val pb = room?.let { pbs["${it.name}|${if (fromFirstSecret) "s" else "e"}"] }
        val pbRow = "PB" to (pb?.let { fmt(it) } ?: "-")
        val rows = if (room == null) listOf("Mines" to "12.4s", "2/6 secrets" to "last +3.1s", "PB" to "9.8s") else if (room.shared) listOf(
            room.name to "shared", "${room.found.size}/${room.total} secrets" to "not timed", pbRow,
        ) else listOf(
            room.name to (room.startedAt?.let { fmt(if (room.done) room.totalTime!! else now - it) } ?: "-"),
            "${room.found.size}/${room.total} secrets" to (room.splits.lastOrNull()?.let { "last +${fmt(it)}" } ?: "none yet"),
            pbRow,
        )
        val s = panelScale
        val size = 10f * s
        val lineH = 12f * s
        val pad = 6f * s
        val gap = 10f * s
        val leftW = rows.maxOf { HudCard.width(it.first, size) }
        val rightW = rows.maxOf { HudCard.width(it.second, size) }
        val width = HudCard.steady("Room Times", leftW + gap + rightW + pad * 2, s)
        val height = rows.size * lineH + pad * 2 - (lineH - size)
        lastWidth = (width / s).toInt()
        lastHeight = (height / s).toInt()
        HudCard.draw(g, panelX, panelY) {
            HudCard.card(width, height, s)
            rows.forEachIndexed { i, (left, right) ->
                val y = pad + i * lineH
                HudCard.text(left, pad, y, size, if (i == 0) -1 else 0xFFBBBBBB.toInt())
                val color = if (i == 0 && room?.done == true) 0xFF55FF55.toInt() else if (i == 0) 0xFFFFFF55.toInt() else 0xFFBBBBBB.toInt()
                HudCard.text(right, width - pad - HudCard.width(right, size), y, size, color)
            }
        }
    }

    override val label = "Room Times"
    private const val DEFAULT_X = 1157
    private const val DEFAULT_Y = 683
    override var panelX = DEFAULT_X
    override var panelY = DEFAULT_Y
    override var panelScale = 1f
    override var lastWidth = 0
        private set
    override var lastHeight = 0
        private set

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val gson = Gson()
    private val hudFile by lazy { CedPaths.hud("room_times") }

    init {
        runCatching { gson.fromJson(hudFile.readText(), HudPosition::class.java) }.getOrNull()?.let {
            panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.4f, 3f)
        }
    }

    override fun save() {
        runCatching { hudFile.parentFile?.mkdirs(); hudFile.writeText(gson.toJson(HudPosition(panelX, panelY, panelScale))) }
    }

    override fun resetPosition() { panelX = DEFAULT_X; panelY = DEFAULT_Y; panelScale = 1f }
    override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.4f, 3f) }
}
