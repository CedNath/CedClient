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
    private val fromFirstSecret get() = startTimer.value == "First Secret"

    /** One room of the run. [found] = when each secret was found (ms, in order). */
    class Room(val name: String, var total: Int, val enteredAt: Long) {
        val found = ArrayList<Long>()

        /** A secret was found that wasn't yours (no click / hit / pickup of yours, or several at once, e.g. some were already done when you came in): the times aren't counted. */
        var shared = false
        var reported = false
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
    @Volatile private var openList = false

    private val CODES = Regex("§.")
    private val SECRETS = Regex("""(\d+)/(\d+) Secrets""")
    private const val START = -185

    private val mc get() = Minecraft.getInstance()

    init {
        addSettings(hud, startTimer, chatTimes, onlyPb, openListButton, resetButton)

        ClientTickEvents.END_CLIENT_TICK.register {
            if (openList) { openList = false; mc.setScreen(RoomTimesScreen()) }
            val level = mc.level ?: return@register
            val player = mc.player ?: return@register
            if (!isEnabled || !DungeonState.active || DungeonState.inBoss) { current = null; return@register }
            if (level !== runLevel) { runLevel = level; rooms.clear(); current = null }
            if (scanDelay-- <= 0) { scanDelay = 10; DungeonScanner.scan(level, player.x, player.z) }
            val info = DungeonScanner.room(Math.floorDiv(player.x.toInt() - START + 16, 32), Math.floorDiv(player.z.toInt() - START + 16, 32))
            if (info == null || info.secrets <= 0) { current = null; return@register }
            if (current?.name != info.name) current = rooms.getOrPut(info.name) { Room(info.name, info.secrets, System.currentTimeMillis()) }
        }
        // Items picked up are the other way a secret reaches you (the count only goes up).
        ClientTickEvents.END_CLIENT_TICK.register {
            val inv = mc.player?.inventory ?: return@register
            val items = (0 until 41).sumOf { inv.getItem(it).count }
            if (items > lastItems) lastOwnAction = System.currentTimeMillis()
            lastItems = items
        }
        UseBlockCallback.EVENT.register(UseBlockCallback { _, level, _, _ ->
            if (level.isClientSide) lastOwnAction = System.currentTimeMillis()
            InteractionResult.PASS
        })
        UseEntityCallback.EVENT.register(UseEntityCallback { _, level, _, _, _ ->
            if (level.isClientSide) lastOwnAction = System.currentTimeMillis()
            InteractionResult.PASS
        })
        AttackEntityCallback.EVENT.register(AttackEntityCallback { _, level, _, _, _ ->
            if (level.isClientSide) lastOwnAction = System.currentTimeMillis()
            InteractionResult.PASS
        })

        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!overlay || !isEnabled) return@register
            val room = current ?: return@register
            val m = SECRETS.find(message.string.replace(CODES, "")) ?: return@register
            room.total = m.groupValues[2].toInt()
            val found = m.groupValues[1].toInt()
            val now = System.currentTimeMillis()
            // A new secret you didn't just reach for (click, hit, pick up) was someone else's: cancel the room's times.
            val own = now - lastOwnAction < 2500
            if (found > room.found.size && (!own || found - room.found.size > 1)) room.shared = true
            while (room.found.size < found) room.found += now
            if (room.done && !room.reported) onDone(room)
        }
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
            mc.player?.sendSystemMessage(ChatPrefix.msg("§eClick Reset Saved Times again within 5 seconds to delete ${pbs.size} saved room time(s)"))
            return
        }
        resetAskedAt = 0L
        val count = pbs.size
        pbs.clear()
        runCatching { pbFile.delete() }
        mc.player?.sendSystemMessage(ChatPrefix.msg("§7Deleted §f$count §7saved room time(s)"))
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
        mc.player?.sendSystemMessage(ChatPrefix.msg("§f${room.name} §7done in §e${sec(time)}$note"))
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
                    if (!isEnabled) ctx.source.sendFeedback(ChatPrefix.msg("§cRoom Times is off")) else openList = true
                    1
                })
        )

    private fun print(source: FabricClientCommandSource) {
        val room = current
        when {
            !isEnabled -> source.sendFeedback(ChatPrefix.msg("§cRoom Times is off"))
            room == null -> source.sendFeedback(ChatPrefix.msg("§7Not in a room with secrets (§f/cc room time list §7for the run)"))
            room.shared -> source.sendFeedback(ChatPrefix.msg("§f${room.name} §7- a secret here wasn't yours, not timed"))
            else -> {
                val now = System.currentTimeMillis()
                val start = room.startedAt
                val time = if (start == null) "waiting for the first secret" else fmt(if (room.done) room.totalTime!! else now - start)
                source.sendFeedback(ChatPrefix.msg("§f${room.name} §7- §e$time §7in room, §a${room.found.size}§7/${room.total} secrets" + if (room.done) " §a(done)" else ""))
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
        val rows = if (room == null) listOf("Mines" to "12.4s", "2/6 secrets" to "last +3.1s") else if (room.shared) listOf(
            room.name to "shared", "${room.found.size}/${room.total} secrets" to "not timed",
        ) else listOf(
            room.name to (room.startedAt?.let { fmt(if (room.done) room.totalTime!! else now - it) } ?: "-"),
            "${room.found.size}/${room.total} secrets" to (room.splits.lastOrNull()?.let { "last +${fmt(it)}" } ?: "none yet"),
        )
        val s = panelScale
        val size = 10f * s
        val lineH = 12f * s
        val pad = 6f * s
        val gap = 10f * s
        val leftW = rows.maxOf { HudCard.width(it.first, size) }
        val rightW = rows.maxOf { HudCard.width(it.second, size) }
        val width = leftW + gap + rightW + pad * 2
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
    private const val DEFAULT_X = 1140
    private const val DEFAULT_Y = 300
    override var panelX = DEFAULT_X
    override var panelY = DEFAULT_Y
    override var panelScale = 1f
    override var lastWidth = 0
        private set
    override var lastHeight = 0
        private set

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val gson = Gson()
    private val hudFile: File by lazy { CedPaths.file("room_times_hud.json") }

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
