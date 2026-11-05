//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.config.CedPaths
import ced.cedclient.events.core.on
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.pathing.PathNode
import ced.cedclient.pathing.PlanOptions
import ced.cedclient.pathing.Planner
import ced.cedclient.pathing.EtherwarpRules
import ced.cedclient.pathing.Route
import ced.cedclient.render.FilledBox
import ced.cedclient.render.LineBox
import ced.cedclient.render.geometry
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.utils.Debug
import ced.cedclient.utils.loreLines
import ced.cedclient.utils.skyblockId
import ced.cedclient.pathing.PathFailures
import ced.cedclient.pathing.PathRecorder
import ced.cedclient.pathing.SlayerRecorder
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Walks the player to a block. [Planner] finds the route off the main thread (walking, jumping, dropping, and
 * Aspect of the Void etherwarps / instant transmissions); this module then follows it: turns the camera and
 * presses W / sprint / jump along walk stretches, and for a teleport selects the Aspect of the Void, aims, sneaks
 * for an etherwarp, right clicks and waits to land. It plans again if you are pushed off the route, get stuck, or
 * a teleport does not land where it should, and gives up after a few tries.
 *
 * Macros call [goTo] and are told how it ended; `/cedclient path goto x y z` does the same by hand.
 * [goTo] only works while the module is on, and turning it off stops the walk.
 */
object Pathfinding : Module(
    "Pathfinder", Category.Cheats,
    "Walks you to a spot with A* pathfinding, using Aspect of the Void teleports. /cedclient path goto x y z, or call it from macros."
) {
    private val mc = Minecraft.getInstance()

    private val readRanges = BooleanSetting("Read Ranges From Item", true, "Take the teleport ranges (and whether it can etherwarp at all) from the Aspect of the Void's tooltip in your hotbar. Off: use the sliders below.")
    private val ether = BooleanSetting("Etherwarp", true, "Use etherwarps (sneak + right click) on the way. Needs an Aspect of the Void with Ether Transmission in the hotbar.")
    private val etherRange = NumberSetting("Etherwarp Range", 57.0, 20.0, 100.0, 1.0, "How far a warp reaches (57 base, more with the upgrades).")
    private val instant = BooleanSetting("Instant Transmission", true, "Use the plain right click teleport on the way.")
    private val instantRange = NumberSetting("Instant Range", 12.0, 4.0, 20.0, 1.0, "How far the plain teleport goes (8 base, +1 per Tuned Transmission level). Measured 12 on a recorded walk.")
    private val etherCost = NumberSetting("Etherwarp Cost", 30.0, 2.0, 100.0, 1.0, "What one etherwarp is worth in blocks of walking. Higher: walk more, warp less (only long trips warp).")
    private val instantCost = NumberSetting("Instant Cost", 14.0, 2.0, 60.0, 1.0, "What one plain teleport is worth in blocks of walking. Higher: walk more, teleport less.")
    private val flightHeight = NumberSetting("Flight Height", 45.0, 0.0, 120.0, 5.0, "On long trips, how far above the higher of start and target it prefers to fly (less in the way up there). 0: no preference.")
    private val sprint = BooleanSetting("Sprint", true, "Hold sprint while walking straight.")
    private val turnSpeed = NumberSetting("Turn Speed", 35.0, 5.0, 90.0, 5.0, "Most degrees the camera turns per tick.")
    private val renderPath = BooleanSetting("Render Path", true, "Draw the route in the world.")
    private val smoothLine = BooleanSetting("Smooth Path Line", true, "Draws the route as a rounded curve (corner cutting) instead of straight pieces between stops.")
    private val maxNodes = NumberSetting("Max Nodes", 60000.0, 5000.0, 300000.0, 5000.0, "How many blocks one search may look at.")
    private val maxDrop = NumberSetting("Max Drop", 3.0, 1.0, 10.0, 1.0, "Longest fall a route may take. 3 costs no fall damage.")
    private val chat = BooleanSetting("Chat Messages", true, "Say in chat when a walk starts, ends or fails.")
    private val debugLog = BooleanSetting("Debug Log", false)
    private val traceMoves = BooleanSetting("Log Every Move", true, "Write every tick of a walk (position, look, keys, what it decided and why) to config/cedclient/pathlogs, to find out what went wrong.")

    // Walking camera feel (Advanced Mode): tune these between runs without a rebuild. Aiming a teleport still uses Turn Speed.
    private val walkTurnCap = NumberSetting("Walk Turn Cap (deg)", 22.0, 2.0, 45.0, 1.0, "Most degrees the camera turns per tick while walking. Lower: calmer, but wide on corners.")
    private val walkTurnEase = NumberSetting("Turn Ease (%)", 25.0, 3.0, 60.0, 1.0, "Share of the angle still to turn that is aimed for each tick. Lower: slow, smooth turns. Higher: snaps onto the line.")
    private val headingLag = NumberSetting("Heading Follow (%)", 65.0, 3.0, 100.0, 1.0, "How fast the direction it follows catches up with the direction it wants (per tick). Lower: wide sweeping turns when the next stop takes over. 100: no filter.")
    private val turnSmooth = NumberSetting("Turn Response (%)", 65.0, 5.0, 100.0, 1.0, "How fast the turn speed itself builds and fades. Lower: eases in and out like a hand. 100: no smoothing.")
    private val straighten = BooleanSetting("Straighten Route", true, "Before walking, drops the stops a straight, clear walk can skip (flat ground, nothing in the way, floor all along), so a zigzag becomes a few long clean legs.")
    private val cornerCut = NumberSetting("Corner Cutting (blocks)", 3.5, 0.0, 8.0, 0.5, "How far ahead along the whole route it aims, so corners are rounded into curves. 0: aims along one straight piece at a time (sharp corners).")
    private val pitchEase = NumberSetting("Pitch Return (%)", 8.0, 2.0, 40.0, 1.0, "How fast the camera drifts back to a walking angle (share of the way, per tick).")
    private val pitchCap = NumberSetting("Pitch Return Cap", 3.0, 1.0, 15.0, 0.5, "Most degrees the pitch moves per tick on its way back.")

    init {
        listOf(maxNodes, maxDrop, debugLog, walkTurnCap, walkTurnEase, headingLag, turnSmooth, straighten, cornerCut, pitchEase, pitchCap).forEach { it.advanced = true }
        addSettings(flightHeight, readRanges, ether, etherRange, etherCost, instant, instantRange, instantCost, sprint, turnSpeed, walkTurnCap, walkTurnEase, headingLag, turnSmooth, straighten, cornerCut, pitchEase, pitchCap, renderPath, smoothLine, maxNodes, maxDrop, chat, traceMoves, debugLog)
    }

    private val ITEMS = setOf("ASPECT_OF_THE_VOID", "ASPECT_OF_THE_END")
    private const val MAX_REPATHS = 6
    /** Written to every move log, so it is clear which build produced it. */
    private const val BUILD_TAG = "altitude-in-heuristic, short-air-replans, high-cruise-flights"
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "CedClient-Pathfinder").also { it.isDaemon = true } }

    // --- walk state ---
    private var goal: BlockPos? = null
    private var route: List<PathNode> = emptyList()
    private var reachedGoal = false
    private var index = 0
    private var planning: CompletableFuture<Route>? = null
    private var appending = false
    private var onDone: ((Boolean) -> Unit)? = null
    private var repaths = 0
    private var stalls = 0
    private var lastPartialEnd = Long.MIN_VALUE
    private val avoid = HashSet<Long>()
    /** Cells a teleport was blocked from (the server said "blocks in the way" with nothing the model could see): no more tries from there. */
    private val avoidFrom = HashSet<Long>()
    /** Walk cells it got stuck against without any block the planner knew of (cleared per walk): see PlanOptions.blocked. */
    private val blockedWalk = HashSet<Long>()
    private var stuckTicks = 0
    private var lastX = 0.0
    private var lastZ = 0.0
    private var jitterIndex = -1
    private var jx = 0.0
    private var jz = 0.0
    private var yawNoise = 0f
    private var pitchWander = 8f

    // --- teleport state ---
    private var savedSlot = -1
    private var tpClicked = false
    private var tpTicks = 0
    private var sneakTicks = 0
    private var lastTeleportTick = 0L
    private var aimedTicks = 0
    private var clickDelay = 3
    private var clickPos = Vec3.ZERO
    private var clickSneak = false
    /** Teleports that did not land as planned in this walk; after [MAX_TP_MISSES] the rest of it is on foot. */
    private var tpMisses = 0
    private const val MAX_TP_MISSES = 6

    /** A walk is under way. */
    val active: Boolean get() = goal != null

    /** The block being walked to, or null. */
    val target: BlockPos? get() = goal

    /**
     * Starts walking to [pos] (the block the feet should end up in). [done] gets true on arrival, false if it was
     * stopped, or no route was found. Returns false right away if the module is off or nothing is loaded.
     */
    fun goTo(pos: BlockPos, done: ((Boolean) -> Unit)? = null): Boolean {
        if (!isEnabled || mc.player == null || mc.level == null) return false
        stop(false)
        goal = pos
        onDone = done
        repaths = 0; stalls = 0; tpMisses = 0; lastPartialEnd = Long.MIN_VALUE; bestGoalDist = Double.MAX_VALUE; searchedHarder = false; thoroughSticky = false
        avoid.clear()
        avoidFrom.clear()
        blockedWalk.clear()
        mc.player?.let { prevX = it.x; prevY = it.y; prevZ = it.z; speed = 0.0 }
        measuredJump = estimateJump()
        headingInit = false
        farTrip = mc.player?.position()?.distanceTo(Vec3.atCenterOf(pos))?.let { it > 250.0 } ?: false
        startTrace(pos)
        if (farTrip) say("Long trip: flying (teleports priced as faster than walking)")
        // A target well above you usually means going up and round: look wide from the start, not after a dead end.
        // Well above OR below: look wide from the start. Searching short first walks to the cliff edge nearest the target, then
        // back round the detour, which was the 180 degree swing of the camera.
        requestPlan(wide = abs(pos.y - (mc.player?.y ?: pos.y.toDouble())) >= 12)
        say("Planning a route to ${pos.x} ${pos.y} ${pos.z}")
        return true
    }

    /** Ends the walk and lets go of the keys. [success] is what the callback hears. */
    fun stop(success: Boolean = false) {
        val cb = onDone
        val was = goal != null
        if (was) { traceEvent("stop success=$success"); endTrace() }
        planning?.cancel(true)
        goal = null; route = emptyList(); index = 0; planning = null; appending = false; onDone = null; stuckTicks = 0
        resetTeleport()
        releaseKeys()
        restoreSlot()
        if (was) cb?.invoke(success)
    }

    private fun say(msg: String) {
        Debug.line("Pathfinder", msg)
        traceEvent(msg)
        if (chat.value) mc.player?.sendSystemMessage(Component.literal("§8[§dCC§8] §7$msg"))
    }

    private fun fail(reason: String) {
        say(reason)
        stop(false)
    }

    /** Plans from the player, or, with [from], the next leg from the end of the current route while it keeps walking. */
    private fun requestPlan(from: BlockPos? = null, wide: Boolean = false) {
        val thorough = wide || thoroughSticky
        val player = mc.player ?: return
        val level = mc.level ?: return
        val g = goal ?: return
        val start = from ?: BlockPos.containing(player.x, player.y + 0.01, player.z)
        val reach = if (readRanges.value) heldReach() else null
        // No Aspect of the Void in the hotbar: nothing to teleport with, so a route on foot.
        val haveAotv = (0..8).any { player.inventory.getItem(it).skyblockId in ITEMS }
        val o = PlanOptions(
            // A first search from where you stand is short, so you start moving quickly; the rest is planned as you walk.
            if (thorough) 250000 else maxNodes.value.toInt(), maxDrop.value.toInt(), if (thorough) (if (wide && !searchedHarder && !thoroughSticky) 3000 else 8000) else if (from == null && !player.onGround()) 250 else if (from == null) 350 else 1200,
            ether.value && haveAotv && tpMisses < MAX_TP_MISSES && (reach == null || reach.ether != null), reach?.ether ?: etherRange.value, if (farTrip) minOf(etherCost.value, 14.0) else etherCost.value,
            instant.value && haveAotv && tpMisses < MAX_TP_MISSES && (reach == null || reach.instant != null), reach?.instant ?: instantRange.value, if (farTrip) minOf(instantCost.value, 5.0) else instantCost.value,
            HashSet(avoid).also { it.addAll(PathFailures.keys()) },
            startInAir = from == null && !player.onGround(),
            thorough = thorough,
            // A normal jump is 1.25 blocks; the measured one is what a step up really costs in time (a 6-block jump is a long flight).
            stepUpCost = 5.0 + maxOf(0.0, measuredJump - 1.3) * 4.0,
            cruiseAbove = if (farTrip) flightHeight.value.toInt() else 0,
            avoidFrom = HashSet(avoidFrom),
            blocked = HashSet(blockedWalk),
        )
        if (debugLog.value) Debug.line("Pathfinder", "ranges: etherwarp ${o.etherRange} (${o.ether}), instant ${o.instantRange} (${o.instant}), start in air ${o.startInAir}")
        if (from == null) { resetTeleport(); releaseKeys(); planStart = start; planStartAir = !player.onGround() }
        planRequestedAt = System.nanoTime()
        appending = from != null
        planning?.cancel(true)
        planning = CompletableFuture.supplyAsync({ Planner.plan(level, start, g, o) }, executor)
    }

    /** Closest any partial route has got to the goal so far (blocks). */
    private var bestGoalDist = Double.MAX_VALUE

    private fun goalDist(n: PathNode): Double {
        val g = goal ?: return 0.0
        val dx = (n.x - g.x).toDouble(); val dy = (n.y - g.y).toDouble(); val dz = (n.z - g.z).toDouble()
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** False when a partial route does not end clearly closer than the earlier ones, three times running: the spot is out of reach. */
    private fun gettingCloser(r: Route): Boolean {
        val d = goalDist(r.nodes.last())
        if (d < bestGoalDist - 1.5) { bestGoalDist = d; stalls = 0; return true }
        return ++stalls <= 2
    }

    private var searchedHarder = false
    /** The wide search was needed once for this walk (a dead end near the target): every later plan of it is wide too. */
    private var thoroughSticky = false
    /**
     * A long trip (over 250 blocks) is flown: a hop of 12 blocks takes about 4 ticks, walking them about 10, so over a distance
     * like that teleports are much faster even though one hop 'costs' more than 12 blocks of walking in the cost settings.
     * Decided once at the start of the walk, so the style does not flip part way.
     */
    private var farTrip = false

    /** A normal search stalled: look once more, harder (and standing still), before giving up. */
    private fun stalled() {
        if (!searchedHarder) {
            searchedHarder = true
            thoroughSticky = true
            say("Stuck ${"%.1f".format(bestGoalDist)} blocks from the target, searching harder (up to 8 s)")
            stalls = 0
            requestPlan(wide = true)
        } else {
            giveUp()
        }
    }

    /** Nothing nearer to be found: near enough counts as arriving (the target may be inside a block or off a ledge), further off is a failure. */
    private fun giveUp() {
        val d = bestGoalDist
        if (d <= 4.0) {
            say("Arrived as close as the ground allows (${"%.1f".format(d)} blocks from the target)")
            stop(true)
        } else {
            fail("Could not get any closer to the target (${"%.1f".format(d)} blocks away)")
        }
    }

    /** The next leg of a partial route is ready: join it on, so the walk never stops to think. */
    private fun onLeg(r: Route) {
        if (debugLog.value) Debug.line("Pathfinder", "next leg: ${r.nodes.size} stops in ${r.millis} ms (reached=${r.reached})")
        traceEvent("leg ${r.nodes.size} stops, ${r.millis} ms, ${r.searched} nodes, reached=${r.reached}: " + r.nodes.joinToString(" ") { "${it.kind.name[0]}${it.x},${it.y},${it.z}/${"%.2f".format(it.standY)}" })
        if (r.nodes.size < 2) {
            if (r.reached) { reachedGoal = true; return }
            bestGoalDist = minOf(bestGoalDist, goalDist(r.nodes.last()))
            return stalled()
        }
        if (!r.reached && !gettingCloser(r)) return stalled()
        route = route + smoothRoute(r.nodes).drop(1)
        reachedGoal = r.reached
    }

    private fun onRoute(r: Route) {
        traceEvent("route ${r.nodes.size} stops, ${r.millis} ms, ${r.searched} nodes, reached=${r.reached}: " + r.nodes.joinToString(" ") { "${it.kind.name[0]}${it.x},${it.y},${it.z}/${"%.2f".format(it.standY)}" })
        if (debugLog.value) Debug.line("Pathfinder", "planned ${r.nodes.size} stops in ${r.millis} ms (${r.searched} nodes, reached=${r.reached}): " +
            r.nodes.joinToString(" ") { "${it.kind.name[0]}${it.x},${it.y},${it.z}" })
        if (r.nodes.size < 2) {
            if (r.reached) { say("Arrived"); return stop(true) }
            bestGoalDist = minOf(bestGoalDist, goalDist(r.nodes.last()))
            return stalled()
        }
        // Walking only part of the way: each leg has to end clearly closer than the ones before, or this is going nowhere.
        if (!r.reached && !gettingCloser(r)) return stalled()
        route = smoothRoute(r.nodes)
        reachedGoal = r.reached
        index = 1
        jitterIndex = -1
        distIndex = -1
        stuckTicks = 0
        val tps = r.nodes.count { it.kind != PathNode.Kind.WALK }
        say("Walking: ${r.nodes.size - 1} stops${if (tps > 0) ", $tps teleports" else ""}${if (!r.reached) ", partial" else ""}")
    }

    /**
     * Evens out the line it will walk: from each stop it jumps to the furthest later stop it can reach in a straight, clear line, and
     * drops the stops in between. Only plain flat WALK stretches are touched (same height, no teleport, step or drop between), the
     * stops that matter (the first, the last, anything beside a teleport) are always kept.
     */
    private fun smoothRoute(nodes: List<PathNode>): List<PathNode> {
        val level = mc.level ?: return nodes
        if (!straighten.value || nodes.size < 3) return nodes
        val out = ArrayList<PathNode>()
        out += nodes[0]
        var i = 0
        while (i < nodes.size - 1) {
            var best = i + 1
            var j = minOf(nodes.size - 1, i + 16)
            while (j > i + 1) {
                if (canShortcut(level, nodes, i, j)) { best = j; break }
                j--
            }
            out += nodes[best]
            i = best
        }
        if (out.size < nodes.size) traceEvent("straightened ${nodes.size} stops to ${out.size}")
        return out
    }

    /** Every stop from [i] to [j] is plain flat walking at one height, and a 0.6 wide body can walk the straight line between them. */
    private fun canShortcut(level: net.minecraft.world.level.Level, nodes: List<PathNode>, i: Int, j: Int): Boolean {
        val a = nodes[i]
        for (k in i..j) {
            val n = nodes[k]
            if (n.kind != PathNode.Kind.WALK || n.y != a.y || abs(n.standY - a.standY) > 0.01) return false
        }
        val b = nodes[j]
        val ax = a.x + 0.5; val az = a.z + 0.5
        val dx = (b.x + 0.5) - ax; val dz = (b.z + 0.5) - az
        val len = hypot(dx, dz)
        if (len < 0.5) return true
        val px = -dz / len * 0.3; val pz = dx / len * 0.3 // sideways, half the body width
        val steps = (len / 0.4).toInt().coerceAtLeast(1)
        for (s in 0..steps) {
            val cx = ax + dx * s / steps; val cz = az + dz * s / steps
            for (side in doubleArrayOf(-1.0, 0.0, 1.0)) {
                val feet = BlockPos.containing(cx + px * side, a.y + 0.01, cz + pz * side)
                if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty) return false
                val head = feet.above()
                if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty) return false
                val floor = feet.below()
                if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty) return false
            }
        }
        return true
    }

    /** What the Aspect of the Void in the hotbar says it can do; a null range means it lacks that ability. */
    private class Reach(val instant: Double?, val ether: Double?)

    private val INSTANT_RE = Regex("""Teleport (\d+) blocks ahead""")
    private val ETHER_RE = Regex("""up to (\d+) blocks away""")

    /** Read off the tooltip ("Teleport 12 blocks ahead", "up to 61 blocks away"), so upgrades are picked up by themselves. */
    private fun heldReach(): Reach? {
        val player = mc.player ?: return null
        val stack = (0..8).map { player.inventory.getItem(it) }.firstOrNull { it.skyblockId in ITEMS } ?: return null
        val lore = stack.loreLines
        if (lore.isEmpty()) return null
        return Reach(
            lore.firstNotNullOfOrNull { INSTANT_RE.find(it)?.groupValues?.get(1)?.toDoubleOrNull() },
            lore.firstNotNullOfOrNull { ETHER_RE.find(it)?.groupValues?.get(1)?.toDoubleOrNull() },
        )
    }

    private fun etherReach(): Double = (if (readRanges.value) heldReach()?.ether else null) ?: etherRange.value

    private fun releaseKeys() {
        val o = mc.options
        o.keyUp.setDown(false); o.keySprint.setDown(false); o.keyJump.setDown(false); o.keyShift.setDown(false)
    }

    private fun resetTeleport() { tpClicked = false; tpTicks = 0; sneakTicks = 0; tpStartNanos = 0L }
    private var tpStartNanos = 0L

    private fun restoreSlot() {
        if (savedSlot >= 0) mc.player?.inventory?.setSelectedSlot(savedSlot)
        savedSlot = -1
    }

    override fun onDisable() = stop(false)

    init {
        // Whatever the server says during a walk goes in the move log too (setback and anticheat messages, lag notices).
        on<ced.cedclient.events.ChatMessageEvent> {
            if (goal != null && !it.unformattedText.startsWith("[CC]")) traceEvent("chat: " + it.unformattedText.take(200))
            // What the server says when a teleport is stopped: used to fail the hop at once.
            if (goal != null && it.unformattedText.contains("blocks in the way", ignoreCase = true)) blockedMsgAt = tickNow()
        }
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { context -> render(context) })
    }

    // ---------------------------------------------------------------- tick

    // ---------------------------------------------------------------- the move log

    private var traceFile: File? = null
    private val traceBuf = ArrayList<String>()
    private val traceGson = Gson()
    /** What this tick decided, for the log: set along the way, written at the end of the tick. */
    private var decision = ""
    private var dbgDist = 0.0
    private var dbgWantYaw = 0f
    private var dbgDiff = 0f

    private fun startTrace(g: BlockPos) {
        if (!traceMoves.value) return
        val dir = CedPaths.dir.resolve("pathlogs").also { it.mkdirs() }
        traceFile = File(dir, "path-${System.currentTimeMillis() / 1000}.jsonl")
        traceBuf.clear()
        traceEvent("start goal ${g.x} ${g.y} ${g.z}")
        mc.player?.let { traceEvent("player at ${"%.2f %.2f %.2f".format(it.x, it.y, it.z)} yaw ${"%.1f".format(it.yRot)} pitch ${"%.1f".format(it.xRot)} ground ${it.onGround()}") }
        traceEvent("build $BUILD_TAG")
        traceEvent("settings ether=${ether.value} instant=${instant.value} sprint=${sprint.value} turnSpeed=${turnSpeed.value} costs ether=${etherCost.value} instant=${instantCost.value}")
        mc.player?.sendSystemMessage(Component.literal("§8[§dCC§8] §7Logging every move to ${traceFile?.name}"))
    }

    private fun traceEvent(text: String) {
        if (traceFile == null) return
        traceBuf += traceGson.toJson(JsonObject().apply { addProperty("t", tickNow()); addProperty("event", text) })
        flushTrace(false)
    }

    private fun flushTrace(force: Boolean) {
        val f = traceFile ?: return
        if (!force && traceBuf.size < 100) return
        try { f.appendText(traceBuf.joinToString("\n", postfix = "\n")) } catch (e: Exception) { Debug.line("Pathfinder", "could not write the move log: ${e.message}") }
        traceBuf.clear()
    }

    private fun endTrace() {
        val f = traceFile ?: return
        traceEvent("end")
        flushTrace(true)
        traceFile = null
        mc.player?.sendSystemMessage(Component.literal("§8[§dCC§8] §7Move log saved: ${f.path}"))
    }

    private fun traceTick() {
        if (traceFile == null) return
        val p = mc.player ?: return
        val o = mc.options
        val n = route.getOrNull(index)
        traceBuf += traceGson.toJson(JsonObject().apply {
            addProperty("t", tickNow())
            addProperty("x", Math.round(p.x * 1000) / 1000.0); addProperty("y", Math.round(p.y * 1000) / 1000.0); addProperty("z", Math.round(p.z * 1000) / 1000.0)
            addProperty("yaw", Math.round(p.yRot * 10) / 10.0); addProperty("pitch", Math.round(p.xRot * 10) / 10.0)
            addProperty("ground", p.onGround()); addProperty("sneak", p.isCrouching); addProperty("sprint", p.isSprinting); addProperty("hcol", p.horizontalCollision)
            addProperty("keys", (if (o.keyUp.isDown) "W" else "") + (if (o.keySprint.isDown) "R" else "") + (if (o.keyJump.isDown) "J" else "") + (if (o.keyShift.isDown) "S" else ""))
            addProperty("idx", index); addProperty("size", route.size)
            if (n != null) { addProperty("kind", n.kind.name); addProperty("nx", n.x); addProperty("ny", n.y); addProperty("nz", n.z); addProperty("nstand", n.standY) }
            addProperty("dist", Math.round(dbgDist * 100) / 100.0)
            addProperty("wantYaw", Math.round(dbgWantYaw * 10) / 10.0); addProperty("diff", Math.round(dbgDiff * 10) / 10.0)
            addProperty("decision", decision)
            addProperty("planning", planning != null); addProperty("appending", appending)
            if (tpClicked || tpTicks > 0) { addProperty("tpClicked", tpClicked); addProperty("tpTicks", tpTicks) }
            addProperty("slot", p.inventory.selectedSlot)
            addProperty("idle", idleTicks)
        })
        flushTrace(false)
    }

    private fun tick() {
        if (goal == null) return
        decision = ""
        tickBody()
        traceTick()
    }

    private fun tickBody() {
        val g = goal ?: return
        val player = mc.player
        val level = mc.level
        if (player == null || level == null) return stop(false)
        // idleReason is what the previous tick was doing; it is what any standing still is blamed on.
        val lastReason = idleReason
        if (mc.gui.screen() != null) { idleReason = "a menu is open"; trackIdle(player, "a menu is open"); releaseKeys(); return } // paused while a menu is open
        idleReason = "walking"
        trackIdle(player, lastReason)
        trackJump(player)

        // Watchdog: nothing changed for 3 s and no search is running: say what state it is stuck in, and plan again.
        val here = player.position()
        if (index != watchIndex || planning != null || watchPos.distanceTo(here) > 0.4) {
            watchIndex = index; watchPos = here; watchTick = tickNow()
        } else if (tickNow() - watchTick > 60) {
            val n = route.getOrNull(index)
            say("No progress for 3 s: stop $index of ${route.size - 1} ${n?.kind} at ${n?.x} ${n?.y} ${n?.z}, you at ${"%.1f %.1f %.1f".format(player.x, player.y, player.z)}, " +
                "clicked $tpClicked, crouching ${player.isCrouching}, slot ${player.inventory.selectedSlot}, on ground ${player.onGround()}")
            watchTick = tickNow()
            return replan("no progress")
        }

        val f = planning
        if (f != null) {
            if (f.isDone) {
                planning = null
                val r = try { f.get() } catch (e: Exception) { return fail("Pathfinder error: ${e.cause?.message ?: e.message}") }
                val waited = (System.nanoTime() - planRequestedAt) / 1_000_000
                Debug.line("Pathfinder", "route ready after $waited ms (search took ${r.millis} ms${if (appending) ", next leg" else ", standing still meanwhile"})")
                if (waited > 300 && !appending) say("Route took $waited ms to work out")
                if (appending) { appending = false; onLeg(r); return }
                // It was planned from where you were: if you have moved on since (falling, a teleport), it is stale.
                val ps = planStart
                // (Planned from mid-air it is expected to have moved: falling is part of that route.)
                if (!planStartAir && ps != null && player.position().distanceTo(Vec3.atCenterOf(ps)) > 3.5) {
                    if (++repaths > MAX_REPATHS) return fail("Could not plan a route that was still valid")
                    say("Moved ${"%.1f".format(player.position().distanceTo(Vec3.atCenterOf(ps)))} blocks while planning, planning again")
                    return requestPlan()
                }
                onRoute(r)
                return
            }
            // A next leg is being worked out: keep walking the current route meanwhile.
            if (!appending || index >= route.size) { idleReason = "waiting for a route"; decision = "waiting for a route"; releaseKeys(); return }
        } else if (!reachedGoal && route.isNotEmpty() && route.size - index <= 7) { // early enough that short legs do not run dry
            val e = route.last()
            requestPlan(BlockPos(e.x, e.y, e.z))
        }

        if (index >= route.size) {
            if (reachedGoal) { say("Arrived"); return stop(true) }
            if (++repaths > MAX_REPATHS) return fail("Could not get any closer to ${g.x} ${g.y} ${g.z}")
            return requestPlan()
        }

        val next = route[index]
        if (next.kind == PathNode.Kind.WALK) walkTo(player, next) else teleportTo(player, level, next)
    }

    // ---------------------------------------------------------------- debug: standing still, turning

    private var nearTicks = 0
    private var distIndex = -1
    private var minDist = Double.MAX_VALUE
    private var watchIndex = -1
    private var watchPos = Vec3.ZERO
    private var watchTick = 0L
    private var planStart: BlockPos? = null
    private var planStartAir = false
    private var planRequestedAt = 0L
    private var idleTicks = 0
    private var idleReason = ""
    private var idleWhy = ""
    private var lastGroundY = 0.0
    private var jumping = false
    private var jumpStartY = 0.0
    private var jumpApex = 0.0
    /** How high a jump takes the player, measured while walking (1.25 vanilla; far more with a jump boost). */
    private var measuredJump = 1.25

    /** The jump height from the Jump Boost effect on the player (a God Potion gives IV), before any jump has been measured. */
    private fun estimateJump(): Double {
        val effect = mc.player?.getEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST) ?: return 1.25
        val v = 0.42 + 0.1 * (effect.amplifier + 1)
        return 1.25 * (v / 0.42) * (v / 0.42)
    }

    private fun trackJump(p: net.minecraft.client.player.LocalPlayer) {
        if (p.onGround()) {
            if (jumping) {
                jumping = false
                val h = jumpApex - jumpStartY
                if (h > 0.8 && h < 12) {
                    measuredJump = measuredJump * 0.7 + h * 0.3
                    traceEvent("jump went ${"%.1f".format(h)} blocks up (measured ${"%.1f".format(measuredJump)})")
                }
            }
            lastGroundY = p.y
        } else if (!jumping && mc.options.keyJump.isDown && p.deltaMovement.y > 0.2) {
            jumping = true; jumpStartY = lastGroundY; jumpApex = p.y
        } else if (jumping) {
            jumpApex = maxOf(jumpApex, p.y)
        }
    }
    /** How far the player moved sideways last tick (blocks): a normal sprint is 0.28, but speed buffs make it several times that. */
    private var speed = 0.0
    private var prevX = 0.0
    private var prevY = 0.0
    private var prevZ = 0.0
    private var lastYaw = 0f
    private var rotCounter = 0

    /** Says how long the player stood still and why, once they move again. */
    private fun trackIdle(player: net.minecraft.client.player.LocalPlayer, reason: String) {
        val moved = abs(player.x - prevX) + abs(player.y - prevY) + abs(player.z - prevZ)
        speed = minOf(hypot(player.x - prevX, player.z - prevZ), 3.0)
        // Moved a long way in one tick with no teleport of ours pending: the server moved the player (a setback), not lag.
        val jump = Math.sqrt((player.x - prevX) * (player.x - prevX) + (player.y - prevY) * (player.y - prevY) + (player.z - prevZ) * (player.z - prevZ))
        if (jump > 2.5 && tickNow() - lastClickAt > 30 && tickNow() - lastTeleportTick > 30) {
            say("The server moved you ${"%.1f".format(jump)} blocks with no teleport of ours: ${"%.1f %.1f %.1f".format(prevX, prevY, prevZ)} to ${"%.1f %.1f %.1f".format(player.x, player.y, player.z)}")
        }
        prevX = player.x; prevY = player.y; prevZ = player.z
        if (moved < 0.02) { idleTicks++; idleWhy = reason; return }
        if (idleTicks >= 6) Debug.line("Pathfinder", "stood still for ${idleTicks * 50} ms ($idleWhy)").also { if (idleTicks >= 10) say("Stood still for ${idleTicks * 50} ms ($idleWhy)") }
        idleTicks = 0
    }

    /** Rotation trace: every 10th tick, and straight away on any turn of more than 40 degrees in one tick. */
    private fun logRot(tag: String, player: net.minecraft.client.player.LocalPlayer, wantYaw: Float, wantPitch: Float, extra: String = "") {
        if (!debugLog.value) return
        val turned = Mth.wrapDegrees(player.yRot - lastYaw)
        lastYaw = player.yRot
        if (abs(turned) > 40f || ++rotCounter % 10 == 0) {
            Debug.line("Pathfinder", "rot[$tag]${if (abs(turned) > 40f) " BIG TURN" else ""} yaw ${"%.1f".format(player.yRot)} want ${"%.1f".format(wantYaw)} turned ${"%.1f".format(turned)} pitch ${"%.1f".format(player.xRot)} want ${"%.1f".format(wantPitch)} stop $index/${route.size - 1} $extra")
        }
    }

    private fun replan(reason: String) {
        if (++repaths > MAX_REPATHS) return fail(reason)
        say("Replanning: $reason")
        requestPlan()
    }

    // ---------------------------------------------------------------- walking

    private fun walkTo(player: net.minecraft.client.player.LocalPlayer, next: PathNode) {
        restoreSlotIfIdle()
        val o = mc.options
        o.keyShift.setDown(false)
        val last = index == route.size - 1
        // Right before a teleport the click has to happen close to where it was planned from, so no cutting that corner.
        val precise = last || (route.getOrNull(index + 1)?.kind ?: PathNode.Kind.WALK) != PathNode.Kind.WALK
        val standY = next.y + next.standY

        // Each stop gets its own small offset inside the block, so the line is never walked dead centre.
        if (jitterIndex != index) {
            jitterIndex = index
            jx = if (precise) 0.0 else Random.nextDouble(-0.15, 0.15)
            jz = if (precise) 0.0 else Random.nextDouble(-0.15, 0.15)
        }
        val tx = next.x + 0.5 + jx
        val tz = next.z + 0.5 + jz
        val dist = hypot(player.x - tx, player.z - tz)
        dbgDist = dist
        decision = "walk"
        // Closest approach: once the player has been near this stop and is now moving away from it, it has been passed.
        // (A fast player cannot turn tightly enough to hit a small radius, and just orbits it.)
        if (distIndex != index) { distIndex = index; minDist = Double.MAX_VALUE; nearTicks = 0 }
        minDist = minOf(minDist, dist)
        val receding = minDist < (if (last) 1.5 else 2.5) && dist > minDist + 0.5
        // Failsafe: more than 1.5 s within a few blocks of a stop it needs to be exact at, and still not there: it is circling.
        if (dist < 4.0) nearTicks++
        val orbiting = precise && nearTicks > 30

        // Reached this stop, or walked past it: on to the next. The line is only a guide, so drifting off it is fine.
        val prev = route.getOrNull(index - 1)
        // A sharp turn at this stop (more than ~45 degrees onto the next one): at speed the player runs past it and into the
        // wall, so it gets the careful approach too.
        val after = route.getOrNull(index + 1)?.takeIf { it.kind == PathNode.Kind.WALK }
        val sharp = after != null && prev != null && run {
            val ix = tx - (prev.x + 0.5); val iz = tz - (prev.z + 0.5)
            val ox = (after.x + 0.5) - tx; val oz = (after.z + 0.5) - tz
            val li = hypot(ix, iz); val lo = hypot(ox, oz)
            li > 0.5 && lo > 0.5 && (ix * ox + iz * oz) / (li * lo) < 0.7
        }
        // Everything scales with how fast the player really moves: at 1+ blocks a tick a fixed radius is skipped over.
        val v = speed
        val passed = prev != null && !precise && !sharp &&
            (player.x - tx) * (tx - (prev.x + 0.5)) + (player.z - tz) * (tz - (prev.z + 0.5)) > 0.0 && dist < maxOf(2.5, v * 3)
        // At the end, being in the block counts: a sprint at a tiny target only circles it.
        val inCell = last && Mth.floor(player.x) == next.x && Mth.floor(player.z) == next.z
        // In the last stop's column but at another height (a spot on a ledge it cannot drop to or climb): done as far as walking goes.
        if (last && (dist < 0.7 || inCell) && abs(player.y - standY) >= 2.6) {
            say("Arrived, but ${"%.1f".format(player.y - standY)} blocks off the target height")
            return stop(true)
        }
        // A teleport lands within a block or two of where it was planned, so the stop before one does not need a tight pass.
        val reachR = maxOf(if (last) 0.7 else if (precise) 1.5 else if (sharp) 0.8 else 0.6, v * (if (last) 0.8 else if (precise) 1.2 else 1.1))
        // "Passed it" / "moving away" skip a stop, but rounding a wall end at speed they fire while still on the wrong side of it: the
        // next stop is then through the wall, and the walker runs into it and replans in a loop. Skipping needs a clear way on.
        val upcoming = route.getOrNull(index + 1)
        val wayOnClear = upcoming == null || upcoming.kind != PathNode.Kind.WALK || abs(upcoming.y + upcoming.standY - player.y) > 1.6 ||
            clearLine(mc.level ?: return, player, upcoming.x + 0.5, upcoming.z + 0.5)
        if (((dist < reachR || inCell || orbiting) || ((passed || receding) && wayOnClear)) && abs(player.y - standY) < 2.6) {
            if (orbiting && dist >= reachR) say("Gave up circling stop $index, ${"%.1f".format(dist)} blocks off")
            index++
            stuckTicks = 0
            repaths = 0
            decision = "reached stop ${index - 1}"
            return
        }

        // Pushed far off the route.
        // Measured from the line between the last stop and this one: stops can be 20+ blocks apart after smoothing.
        val ax = (prev?.x ?: next.x) + 0.5
        val az = (prev?.z ?: next.z) + 0.5
        val abx = tx - ax; val abz = tz - az
        val len2 = abx * abx + abz * abz
        val along = if (len2 < 1e-6) 0.0 else (((player.x - ax) * abx + (player.z - az) * abz) / len2).coerceIn(0.0, 1.0)
        val offLine = hypot(player.x - (ax + abx * along), player.z - (az + abz * along))
        // Height is judged along the line too: a long stretch can drop a good way before it reaches its stop.
        val prevY = prev?.let { it.y + it.standY } ?: standY
        val expectedY = prevY + (standY - prevY) * along
        // Only falling below the line counts: standing above a stop that is further down just means walking to the edge.
        if (offLine > 6.0 || player.y - expectedY < -6.0) {
            return replan("off the route (${"%.1f".format(offLine)} off the line, ${"%.1f".format(player.y - expectedY)} high, at ${"%.1f %.1f %.1f".format(player.x, player.y, player.z)}, stop $index of ${route.size - 1} ${next.kind})")
        }

        // Stuck: barely moved for two seconds.
        if (hypot(player.x - lastX, player.z - lastZ) < 0.08) stuckTicks++ else { stuckTicks = 0; lastX = player.x; lastZ = player.z }
        // Pushing into a wall and not moving: no need to wait two seconds.
        if (stuckTicks > (if (player.horizontalCollision) 8 else 40)) {
            stuckTicks = 0
            // Not moving with W held: whatever is just ahead is a wall the planner did not know about (the log showed three identical
            // 2-stop routes through the same spot, each stuck at the same place). Mark the cell ahead (and the one beside the heading),
            // so the replan goes round it instead of drawing the same straight line.
            val hx = -Math.sin(Math.toRadians(player.yRot.toDouble())); val hz = Math.cos(Math.toRadians(player.yRot.toDouble()))
            val fy = Mth.floor(player.y + 0.01)
            val ahead = BlockPos(Mth.floor(player.x + hx * 0.8), fy, Mth.floor(player.z + hz * 0.8))
            blockedWalk += ahead.asLong()
            val lvl = mc.level
            say("Stuck against ${ahead.toShortString()} (feet ${lvl?.getBlockState(ahead)?.block?.descriptionId}, head ${lvl?.getBlockState(ahead.above())?.block?.descriptionId}, below ${lvl?.getBlockState(ahead.below())?.block?.descriptionId}): walking round it")
            return replan("stuck at ${"%.1f %.1f %.1f".format(player.x, player.y, player.z)} heading for ${next.x} ${next.y} ${next.z}")
        }

        // Straight above / below the stop (falling onto it): there is no direction to turn to, so stay as facing.
        // (Airborne with more route ahead: keep going, the lookahead below aims past the stop that is under the feet.)
        if (dist < 0.3 && (player.onGround() || precise)) { decision = "above or below the stop, not turning"; o.keyUp.setDown(false); o.keySprint.setDown(false); return }
        // A stop that ended up right behind: skip it rather than spin round for it.
        // Follow the line: aim a little way ahead along it instead of straight at the stop, so drift is corrected rather than
        // growing (and so the player does not cut across to a stop through a wall). The last and pre-teleport stops are aimed at.
        val segLen = hypot(abx, abz)
        // A short lead on a long, fast straight made the heading overshoot the line and swing back: a steady weave of +-30 degrees.
        // The lead grows with speed (error / lead is the steering gain), so a boosted player settles onto the line instead.
        val aimAlong = if (segLen < 0.01) 1.0 else minOf(1.0, along + maxOf(1.5, v * 7.0) / segLen)
        var aimX = if (precise) tx else ax + abx * aimAlong
        var aimZ = if (precise) tz else az + abz * aimAlong
        // (Also in the air: down stairs half the ticks are a short fall, and without this the aim fell back to the bearing of the stop
        // underfoot. In a high boosted-jump flight the line check finds no floor, pursuit gives up and the straight aim below is used.)
        if (!precise && cornerCut.value > 0.0) {
            // Round the corners: aim at a point further along the whole route, not just along this one straight piece.
            pursuit(player, ax + abx * along, az + abz * along, maxOf(cornerCut.value, v * 8.0))?.let { aimX = it.x; aimZ = it.z }
        }
        // Right over / beside a stop (stairs down, every stop a block below the last) the bearing to it is noise and flips with each
        // tick; look past it to the first stop of the route that is clearly ahead, so the camera keeps the direction of travel.
        if (!precise && dist < 3.0) {
            for (k in index + 1 until minOf(route.size, index + 7)) {
                val nk = route[k]
                if (nk.kind != PathNode.Kind.WALK) break
                if (hypot(nk.x + 0.5 - player.x, nk.z + 0.5 - player.z) >= 3.0) {
                    // Only if the way there is open (or it is a drop, where the floor check cannot see far): a stop behind a wall corner
                    // flipped the aim between it and the stop in front.
                    if (nk.y + nk.standY < player.y - 1.0 || clearLine(mc.level ?: return, player, nk.x + 0.5, nk.z + 0.5)) { aimX = nk.x + 0.5; aimZ = nk.z + 0.5 }
                    break
                }
            }
        }
        val rawYaw = Mth.wrapDegrees(Math.toDegrees(atan2(-(aimX - player.x), aimZ - player.z)).toFloat())
        if (!last && dist < 1.5 && abs(Mth.wrapDegrees(rawYaw - player.yRot)) > 110f) { decision = "skipped a stop right behind"; index++; return }

        // Ease into the turn and wobble a little, like a hand on a mouse, not a robot arm.
        yawNoise = yawNoise * 0.9f + Random.nextFloat() * 0.5f - 0.25f
        pitchWander = (pitchWander + Random.nextFloat() * 0.5f - 0.25f).coerceIn(4f, 14f)
        // A costly jump (a boosted one is a flight of several blocks): if the way ahead is a step, look for a way round it first.
        if (detourTicks > 0) detourTicks-- else detourYaw = 0f
        if (detourYaw == 0f && measuredJump > 2.5 && player.onGround()) {
            val r = riseAhead(player, rawYaw)
            if (r > 0.62 && r <= 1.3) {
                for (off in floatArrayOf(35f, -35f, 70f, -70f)) {
                    if (riseAhead(player, rawYaw + off) <= 0.62) { detourYaw = off; detourTicks = 10; break }
                }
            }
        }
        // Falling past a stop that is right below / beside: the bearing to it flips and swings round as the player drops over it, and
        // there is nothing to steer with in the air anyway, so the camera stays where it is until the feet are down.
        val dropping = !player.onGround() && dist < maxOf(4.0, v * 3) && player.deltaMovement.y < -0.1
        val wantYaw = Mth.wrapDegrees(rawYaw + detourYaw + yawNoise)
        val diff = Mth.wrapDegrees(wantYaw - player.yRot)
        // Walking turns are slow and eased (a big direction change used to snap ~30 degrees in one tick); aiming a teleport still
        // uses the full Turn Speed. The pitch drifts back to a walking angle gently, from a steep look up or down too.
        // Two layers of smoothing. The direction it wants jumps when the next stop takes over, so the heading it follows trails the
        // wanted one (Heading Lag); and the turn speed itself eases in and out instead of being a share of the angle left each tick
        // (Turn Smoothing), which is what made it look like a mouse flick.
        if (!headingInit) { smoothHead = player.yRot; headingInit = true; yawVel = 0f }
        smoothHead = Mth.wrapDegrees(smoothHead + Mth.wrapDegrees(wantYaw - smoothHead) * (headingLag.value / 100.0).toFloat())
        val turnDiff = Mth.wrapDegrees(smoothHead - player.yRot)
        // Turning radius is speed / turn rate: at 1+ blocks a tick the usual 12-20 degrees a tick is a 4-5 block circle, wider than the
        // gaps between stops on stairs, so the player swept past them and orbited. Past walking speed the turn authority grows with it.
        // A big turn (a corner, a new leg) gets more authority whatever the speed: 60 degrees at 22 a tick eased took half a second.
        val fast = (1.0 + maxOf(0.0, v - 0.3) * 2.0).toFloat() * (if (abs(turnDiff) > 40f) 1.7f else 1f)
        val maxStep = minOf(turnSpeed.value.toFloat(), walkTurnCap.value.toFloat() * fast)
        val yawStep = (turnDiff * minOf(0.9f, (walkTurnEase.value / 100.0).toFloat() * fast)).coerceIn(-maxStep, maxStep)
        yawVel += (yawStep - yawVel) * (turnSmooth.value / 100.0).toFloat()
        player.yRot += yawVel
        val pitchStep = pitchCap.value.toFloat()
        player.xRot += ((pitchWander - player.xRot) * (pitchEase.value / 100.0).toFloat()).coerceIn(-pitchStep, pitchStep)

        // Keep walking through a turn; only stand still for a sharp one.
        // Turns are eased and capped, so running while far off the heading just carries the player sideways past the stop (the zigzag):
        // from a standstill wait until roughly facing it, and at speed back off the key earlier than before.
        val facing = abs(Mth.wrapDegrees(wantYaw - player.yRot)) < (if (v < 0.3) 30f else 50f)
        // Let go of W early when fast: the player keeps sliding. In the air there is nothing to steer with anyway, so no
        // pushing towards a stop that is close (a boosted jump flies right over it).
        // Not when the stop is clearly below: that is a drop down stairs or a slope, and letting go of W mid-fall made every step a
        // stop-start (the air has little steering, but what there is should push on towards the stop).
        val airborneNear = !player.onGround() && dist < maxOf(2.0, v * 3) && standY > player.y - 0.9
        // The last few blocks are crept (sneaking is about a third of the speed): at several blocks a tick a tight stop
        // cannot be hit, the turn rate is too low to circle in on it.
        // (Not when the stop is lower: sneaking will not walk off an edge, so it would stand there for good.)
        // Short, and only at the very end: a longer crouch before every sharp corner looked like random sneaking.
        val creep = last && dist < maxOf(1.5, v * 2) && standY >= player.y - 0.4
        o.keyShift.setDown(creep)
        val braking = (precise || sharp) && !creep && dist < v * 2
        // In the air: where would this fall end if nothing is pressed? Short of the aim point means keep pushing forward (long drops
        // are best taken moving, not by letting go in mid-air); already past it means let go.
        var airKeep = false
        if (!player.onGround() && !precise) predictLanding(player)?.let { land ->
            val ax2 = aimX - player.x; val az2 = aimZ - player.z; val l2 = hypot(ax2, az2)
            if (l2 > 0.5) airKeep = ((aimX - land.x) * ax2 + (aimZ - land.z) * az2) / l2 > 0.8
        }
        o.keyUp.setDown(facing && !braking && (!airborneNear || airKeep) && !(dropping && !airKeep && abs(Mth.wrapDegrees(rawYaw - player.yRot)) > 70f))
        o.keySprint.setDown(sprint.value && facing && abs(diff) < 25f && !(precise && dist < maxOf(3.0, v * 4)) && !airborneNear && !creep)
        dbgWantYaw = wantYaw; dbgDiff = diff
        decision = "walk: aim ${"%.1f".format(aimX)} ${"%.1f".format(aimZ)} along ${"%.2f".format(along)}, speed ${"%.2f".format(v)}, reachR ${"%.2f".format(reachR)}, turn ${"%.1f".format(diff)} (max ${"%.1f".format(yawStep)}), forward=${o.keyUp.isDown}, braking=$braking, airNear=$airborneNear, sprint=${o.keySprint.isDown}, precise=$precise, last=$last, jitter ${"%.2f".format(jx)} ${"%.2f".format(jz)}"
        logRot("walk", player, wantYaw, pitchWander, "dist ${"%.2f".format(dist)} ${next.kind}")
        // Jump up a block (a slab-high step is walked), or when something is in the way.
        // Jump only for a real step straight ahead: half-block steps (slabs) are walked up, a wall to the side is no reason,
        // and with a boosted jump every needless hop is a flight of several blocks.
        o.keyJump.setDown(player.onGround() && o.keyUp.isDown && standY > player.y + 0.05 && stepAhead(player))
    }

    private var aimOvershoot = 0f
    private var yawVel = 0f
    private var smoothHead = 0f
    private var headingInit = false

    /** Lists the blocks that are not air along the line of the teleport, to learn what the server counts as in the way. */
    private fun dumpBlockers(dest: Vec3) {
        val level = mc.level ?: return
        val from = clickPos.add(0.0, Planner.STAND_EYE, 0.0)
        val dir = dest.add(0.0, Planner.STAND_EYE, 0.0).subtract(from)
        val len = dir.length()
        if (len < 0.1) return
        val n = dir.scale(1.0 / len)
        val found = LinkedHashSet<String>()
        val model = ced.cedclient.pathing.Terrain(level)
        var d = 0.5
        while (d <= maxOf(len, 12.0) + 0.5 && found.size < 14) {
            val p = from.add(n.scale(d))
            for (dy in doubleArrayOf(-1.62, -0.8, 0.0)) {
                val pos = BlockPos.containing(p.x, p.y + dy + 0.01, p.z)
                val st = level.getBlockState(pos)
                if (!st.isAir) found += "${pos.x},${pos.y},${pos.z}:${net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.block).path}${if (st.getCollisionShape(level, pos).isEmpty) "(no collision)" else ""}${if (model.open(pos.x, pos.y, pos.z)) "(MODEL SAYS OPEN)" else ""}"
            }
            d += 0.5
        }
        traceEvent("blocks near the teleport path (from ${"%.1f %.1f %.1f".format(clickPos.x, clickPos.y, clickPos.z)} to ${"%.1f %.1f %.1f".format(dest.x, dest.y, dest.z)}): ${found.joinToString(" ")}")
    }
    private var blockedMsgAt = -1000L
    private var lastClickAt = -1000L
    private var detourYaw = 0f
    private var detourTicks = 0

    /**
     * Turns towards a teleport aim the way a hand does: quick at first and easing out (never faster than Turn Speed),
     * a little past the target on a big turn and back again, and a small wobble that fades away as it gets close. It
     * always ends exactly on the target, so what the click needs (aimed within the tolerance) is unchanged.
     */
    private fun humanAim(player: net.minecraft.client.player.LocalPlayer, wantYaw: Float, wantPitch: Float) {
        val max = turnSpeed.value.toFloat()
        val dy = Mth.wrapDegrees(wantYaw - player.yRot)
        val dp = wantPitch - player.xRot
        val aimYaw = if (abs(dy) > 8f) dy + Math.signum(dy) * aimOvershoot else dy
        val fade = minOf(1f, abs(dy) / 10f)
        val sy = (abs(aimYaw) * 0.5f + 2f).coerceAtMost(max)
        val sp = (abs(dp) * 0.45f + 1.5f).coerceAtMost(max)
        yawNoise = yawNoise * 0.9f + Random.nextFloat() * 0.5f - 0.25f
        player.yRot += Math.signum(aimYaw) * minOf(abs(aimYaw), sy) + yawNoise * fade * 0.5f
        player.xRot += Math.signum(dp) * minOf(abs(dp), sp)
    }

    /**
     * A point [ahead] blocks along the route from (p0x, p0z) (where you are on the current piece), carrying on round the next stops
     * while they are plain walking, so a corner is a curve instead of a point. Null (aim along the single piece, as before) when the
     * way is not plain: a teleport or the last stop comes first, there is a step or a drop, or the straight line to the point is not
     * clear and supported (a wall in the way, or no floor under it).
     */
    /** Where the player comes down if nothing more is pressed: a plain fall with air drag from the current motion, up to 2 s. */
    private fun predictLanding(player: net.minecraft.client.player.LocalPlayer): Vec3? {
        val level = mc.level ?: return null
        var px = player.x; var py = player.y; var pz = player.z
        var vx = player.deltaMovement.x; var vy = player.deltaMovement.y; var vz = player.deltaMovement.z
        repeat(40) {
            vy = (vy - 0.08) * 0.98
            px += vx; py += vy; pz += vz
            vx *= 0.91; vz *= 0.91
            val bp = BlockPos.containing(px, py, pz)
            val shape = level.getBlockState(bp).getCollisionShape(level, bp)
            if (!shape.isEmpty && bp.y + shape.max(Direction.Axis.Y) >= py) return Vec3(px, py, pz)
        }
        return null
    }

    private fun pursuit(player: net.minecraft.client.player.LocalPlayer, p0x: Double, p0z: Double, ahead: Double): Vec3? {
        val level = mc.level ?: return null
        val pts = ArrayList<Vec3>()
        pts += Vec3(p0x, 0.0, p0z)
        var k = index
        while (k < route.size && k < index + 8) {
            val n = route[k]
            if (n.kind != PathNode.Kind.WALK) break
            pts += Vec3(n.x + 0.5, 0.0, n.z + 0.5)
            val last = k == route.size - 1
            if (last || route[k + 1].kind != PathNode.Kind.WALK) break // a stop that needs an exact approach
            // Only a climb stops the lookahead: going down, the bearing along the route is the same however far the stops drop, and a lookahead
            // that ends at every drop made the camera swing from stop to stop on a descent.
            if ((route[k + 1].y + route[k + 1].standY) - (n.y + n.standY) > 1.1) break
            k++
        }
        if (pts.size < 2) return null
        var want = ahead
        // Not below 3 blocks: on rising stairs the line check fails for the far points, and a point only 1-2 blocks ahead steers hard on
        // a small sideways error (the weave). Giving up here leaves the longer aim along the straight piece.
        val minWant = maxOf(1.0, ahead * 0.7)
        while (want >= minWant) {
            var left = want
            var target = pts.last()
            for (i in 1 until pts.size) {
                val seg = hypot(pts[i].x - pts[i - 1].x, pts[i].z - pts[i - 1].z)
                if (seg >= left) {
                    val f = if (seg < 1e-6) 0.0 else left / seg
                    target = Vec3(pts[i - 1].x + (pts[i].x - pts[i - 1].x) * f, 0.0, pts[i - 1].z + (pts[i].z - pts[i - 1].z) * f)
                    break
                }
                left -= seg
            }
            if (clearLine(level, player, target.x, target.z)) return target
            want *= 0.85 // cut less, try again (not much less: a short lead steers hard on a small sideways error)
        }
        return null
    }

    /** Walking straight from where you are to (tx, tz): nothing solid at feet or head height (a step up of 0.6 or less is fine), floor under every half block. */
    private fun clearLine(level: net.minecraft.world.level.Level, player: net.minecraft.client.player.LocalPlayer, tx: Double, tz: Double): Boolean {
        val dx = tx - player.x; val dz = tz - player.z
        val len = hypot(dx, dz)
        if (len < 0.6) return true
        val steps = (len / 0.5).toInt()
        val y = player.y + 0.05
        for (s in 1..steps) {
            val x = player.x + dx * s / steps; val z = player.z + dz * s / steps
            val feet = BlockPos.containing(x, y, z)
            val fShape = level.getBlockState(feet).getCollisionShape(level, feet)
            if (!fShape.isEmpty && feet.y + fShape.max(Direction.Axis.Y) - player.y > 0.6) return false
            val head = feet.above()
            if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty) return false
            val below = feet.below()
            val below2 = below.below()
            if (level.getBlockState(below).getCollisionShape(level, below).isEmpty && level.getBlockState(below2).getCollisionShape(level, below2).isEmpty && fShape.isEmpty) return false
        }
        return true
    }

    /** How far above the feet the block 0.65 ahead (feet level, looking along [yawDeg]) reaches; 0 when there is none. */
    private fun riseAhead(player: net.minecraft.client.player.LocalPlayer, yawDeg: Float): Double {
        val level = mc.level ?: return 0.0
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pos = BlockPos.containing(player.x - Math.sin(yaw) * 0.65, player.y + 0.01, player.z + Math.cos(yaw) * 0.65)
        val shape = level.getBlockState(pos).getCollisionShape(level, pos)
        if (shape.isEmpty) return 0.0
        return pos.y + shape.max(Direction.Axis.Y) - player.y
    }

    /** There is a block in front, 0.6 to 1.3 high above the feet: too high to walk up, low enough to jump onto. */
    private fun stepAhead(player: net.minecraft.client.player.LocalPlayer): Boolean {
        val rise = riseAhead(player, player.yRot)
        return rise > 0.62 && rise <= 1.3
    }

    /** Back to the item held before, once a stretch of walking starts (not between two teleports in a row). */
    private fun restoreSlotIfIdle() {
        if (savedSlot >= 0 && index < route.size && route[index].kind == PathNode.Kind.WALK &&
            (index + 1 >= route.size || route[index + 1].kind == PathNode.Kind.WALK)
        ) restoreSlot()
    }

    // ---------------------------------------------------------------- teleporting

    private fun teleportTo(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.world.level.Level, next: PathNode) {
        val o = mc.options
        o.keyUp.setDown(false); o.keySprint.setDown(false); o.keyJump.setDown(false)
        val dest = next.center()
        val isEther = next.kind == PathNode.Kind.ETHER

        if (tpStartNanos == 0L) { tpStartNanos = System.nanoTime(); aimOvershoot = Random.nextFloat() * 2.2f }

        // Landed? A plain teleport always goes the full range along the look direction, so it can end a little past
        // the planned block; an etherwarp lands exactly on its block.
        val tolH = if (isEther) 1.6 else 3.2
        val tolV = if (isEther) 2.0 else 3.5
        if (tpClicked && hypot(player.x - dest.x, player.z - dest.z) < tolH && abs(player.y - dest.y) < tolV) {
            Debug.line("Pathfinder", "${next.kind} landed after $tpTicks ticks at ${"%.1f %.1f %.1f".format(player.x, player.y, player.z)} (wanted ${"%.1f %.1f %.1f".format(dest.x, dest.y, dest.z)})")
            decision = "teleport landed"
            repaths = 0
            index++; resetTeleport(); lastTeleportTick = tickNow(); stuckTicks = 0
            o.keyShift.setDown(false)
            return
        }

        if (tpClicked) {
            // The server answered "There are blocks in the way!": no need to wait to see nothing happen.
            if (blockedMsgAt >= lastClickAt && tickNow() - blockedMsgAt <= 40) {
                blockedMsgAt = -1000L
                dumpBlockers(dest)
                say("${next.kind} teleport blocked (the server: there are blocks in the way)")
                val blockedKey = if (isEther) next.aim?.asLong() else BlockPos.asLong(next.x, next.y, next.z)
                if (blockedKey != null) { avoid += blockedKey; PathFailures.strike(blockedKey) }
                // Every retry from the same spot got blocked the same way (the model sees the way as open): leave that spot for the next hop.
                avoidFrom += BlockPos.asLong(Mth.floor(clickPos.x), Mth.floor(clickPos.y + 0.01), Mth.floor(clickPos.z))
                searchedHarder = false
                o.keyShift.setDown(false)
                return replan("the server says there are blocks in the way")
            }
            tpTicks++
            idleReason = "waiting for the teleport to land ($tpTicks ticks)"
            decision = "teleport: waiting to land ($tpTicks ticks)"
            // Turn to the next hop while this one is still in flight (a chain of plain teleports): the landing is where this
            // node says, so the next aim can be worked out from there, and the click goes out the moment it lands.
            val after = route.getOrNull(index + 1)
            if (after != null && after.kind == PathNode.Kind.INSTANT && !isEther) {
                val fromEye = Vec3(dest.x, dest.y + Planner.STAND_EYE, dest.z)
                val toEye = after.center().add(0.0, Planner.STAND_EYE, 0.0)
                val dd = toEye.subtract(fromEye)
                humanAim(player,
                    Mth.wrapDegrees(Math.toDegrees(atan2(-dd.x, dd.z)).toFloat()),
                    Math.toDegrees(-atan2(dd.y, hypot(dd.x, dd.z))).toFloat().coerceIn(-90f, 90f))
            }
            val moved = player.position().distanceTo(clickPos)
            // Sideways only: falling also moves you, and is not a teleport.
            val flat = hypot(player.x - clickPos.x, player.z - clickPos.z)
            // Teleported, but not where the plan said: plan again from where it actually ended up.
            if (tpTicks >= 3 && flat > 3.5) {
                say("${next.kind} teleport landed elsewhere: ${"%.1f %.1f %.1f".format(player.x, player.y, player.z)} instead of ${"%.1f %.1f %.1f".format(dest.x, dest.y, dest.z)} (clicked at ${"%.1f %.1f %.1f".format(clickPos.x, clickPos.y, clickPos.z)}, moved ${"%.1f".format(moved)}, sneaking $clickSneak)")
                // Only an etherwarp has an exact target to blame; a plain teleport is not a failure, just somewhere else.
                if (isEther) { next.aim?.let { avoid += it.asLong() }; tpMisses++ }
                o.keyShift.setDown(false)
                return replan("landed in a different spot")
            }
            if (tpTicks > 24) { // usually within ~7 ticks, but lag spikes of 13-18 were seen: the late landing arrived after a shorter wait had given up
                say("Teleport did nothing (${next.kind.name.lowercase()})")
                val failedKey = if (isEther) next.aim?.asLong() else BlockPos.asLong(next.x, next.y, next.z)
                if (failedKey != null) { avoid += failedKey; PathFailures.strike(failedKey) } // for later walks too, once it has missed twice (one miss can be lag)
                searchedHarder = false // new information: worth another thorough look
                tpMisses++
                o.keyShift.setDown(false)
                return replan("a teleport did not work")
            }
            return
        }

        // The Aspect of the Void in hand.
        val slot = (0..8).firstOrNull { player.inventory.getItem(it).skyblockId in ITEMS }
            ?: run {
                // Lost it mid-walk (moved, dropped): carry on without teleports.
                tpMisses = MAX_TP_MISSES
                o.keyShift.setDown(false)
                return replan("no Aspect of the Void in the hotbar, walking instead")
            }
        if (player.inventory.selectedSlot != slot) {
            if (savedSlot < 0) savedSlot = player.inventory.selectedSlot
            player.inventory.setSelectedSlot(slot)
            decision = "teleport: switching to slot $slot"
            return
        }

        // Where to look.
        val eye = Vec3(player.x, player.y + (if (isEther) Planner.SNEAK_EYE else Planner.STAND_EYE), player.z)
        val aimPoint: Vec3 = if (isEther) {
            val k = next.aim ?: return replan("etherwarp without a target")
            etherAim(level, eye, k, etherReach()) ?: run {
                avoid += k.asLong()
                return replan("cannot aim at the etherwarp block")
            }
        } else {
            Vec3(dest.x, dest.y + Planner.STAND_EYE, dest.z)
        }

        val d = aimPoint.subtract(eye)
        val wantYaw = Mth.wrapDegrees(Math.toDegrees(atan2(-d.x, d.z)).toFloat())
        val wantPitch = Math.toDegrees(-atan2(d.y, hypot(d.x, d.z))).toFloat().coerceIn(-90f, 90f)
        humanAim(player, wantYaw, wantPitch)
        idleReason = "aiming / sneaking for the ${next.kind.name.lowercase()} teleport"
        dbgWantYaw = wantYaw; dbgDiff = Mth.wrapDegrees(wantYaw - player.yRot); dbgDist = aimPoint.distanceTo(eye)
        decision = "teleport: aiming ${next.kind} (yaw off ${"%.1f".format(dbgDiff)}, pitch off ${"%.1f".format(wantPitch - player.xRot)}, sneakTicks $sneakTicks, aimedTicks $aimedTicks)"
        logRot("aim", player, wantYaw, wantPitch, "${next.kind} to ${next.x} ${next.y} ${next.z}, ${"%.1f".format(aimPoint.distanceTo(eye))} away")

        o.keyShift.setDown(isEther)
        sneakTicks = if (isEther && player.isCrouching) sneakTicks + 1 else if (isEther) 0 else 4

        // An etherwarp needs a steady aim; a plain teleport lands within a block whatever, so it is quicker (a person chains them every 3-4 ticks).
        val tol = if (isEther) 0.8f else 2.5f
        val aimed = abs(Mth.wrapDegrees(wantYaw - player.yRot)) < tol && abs(wantPitch - player.xRot) < tol
        aimedTicks = if (aimed) aimedTicks + 1 else 0
        // A short, uneven pause on target before the click, not the same tick every time.
        if (aimed && aimedTicks >= (if (isEther) clickDelay else 1) && sneakTicks >= 4 && tickNow() - lastClickAt >= 3) {
            mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
            clickPos = player.position()
            lastClickAt = tickNow()
            clickSneak = player.isCrouching
            tpClicked = true
            decision = "teleport: CLICKED ${next.kind}"
            traceEvent("click ${next.kind} for ${next.x} ${next.y} ${next.z} from ${"%.2f %.2f %.2f".format(player.x, player.y, player.z)} yaw ${"%.1f".format(player.yRot)} pitch ${"%.1f".format(player.xRot)} sneaking ${player.isCrouching}")
            tpTicks = 0
            aimedTicks = 0
            clickDelay = Random.nextInt(2, 6)
            Debug.line("Pathfinder", "${next.kind} prep took ${(System.nanoTime() - tpStartNanos) / 1_000_000} ms")
            if (debugLog.value) Debug.line("Pathfinder", "${next.kind} click at yaw ${"%.1f".format(wantYaw)} pitch ${"%.1f".format(wantPitch)} for ${next.x} ${next.y} ${next.z}")
        }
    }

    private var tickCount = 0L
    private fun tickNow(): Long = tickCount

    init { ClientTickEvents.START_CLIENT_TICK.register { tickCount++ } }

    /** A point on [k] (the etherwarp block) that the warp ray, from [eye], really ends on. */
    private fun etherAim(level: net.minecraft.world.level.Level, eye: Vec3, k: BlockPos, range: Double): Vec3? {
        val top = k.y + level.getBlockState(k).getCollisionShape(level, k).max(Direction.Axis.Y)
        val ys = doubleArrayOf(top - 0.05, k.y + 0.5)
        val xz = arrayOf(0.5 to 0.5, 0.3 to 0.3, 0.7 to 0.3, 0.3 to 0.7, 0.7 to 0.7, 0.5 to 0.2, 0.5 to 0.8, 0.2 to 0.5, 0.8 to 0.5)
        for (y in ys) for ((ox, oz) in xz) {
            val p = Vec3(k.x + ox, y, k.z + oz)
            val dir = p.subtract(eye)
            if (dir.length() < 1.0e-3) continue
            val hit = EtherwarpRules.cast(level, eye, dir.normalize(), range + 2.0) ?: continue
            if (hit.ok && hit.pos == k) return p
        }
        return null
    }

    // ---------------------------------------------------------------- rendering

    private fun render(context: net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext) {
        if (!isEnabled || !renderPath.value || goal == null) return
        val nodes = route
        if (index >= nodes.size) return
        val cam = context.gameRenderer().mainCamera().position()
        val player = mc.player ?: return

        fun at(n: PathNode) = Vec3(n.x + 0.5 - cam.x, n.y + n.standY + 0.1 - cam.y, n.z + 0.5 - cam.z)
        fun cube(c: Vec3, r: Double) = AABB(c.x - r, c.y - r, c.z - r, c.x + r, c.y + r, c.z + r)

        val start = Vec3(player.x - cam.x, player.y + 0.1 - cam.y, player.z - cam.z)
        // Corner cutting (Chaikin) on every plain walking stretch, so the line is the curve that is walked, not the stop-to-stop zigzag.
        // A teleport stop ends a stretch and stays exactly where it is.
        fun rounded(p: List<Vec3>): List<Vec3> {
            var cur = p
            if (!smoothLine.value) return cur
            repeat(2) {
                if (cur.size < 3) return cur
                val out = ArrayList<Vec3>()
                out += cur.first()
                for (i in 0 until cur.size - 1) {
                    val a = cur[i]; val b = cur[i + 1]
                    if (i > 0) out += a.lerp(b, 0.25)
                    if (i < cur.size - 2) out += a.lerp(b, 0.75)
                }
                out += cur.last()
                cur = out
            }
            return cur
        }
        context.geometry(CustomRenderType.LINES_ESP) { pose, buf ->
            var run = ArrayList<Vec3>().also { it += start }
            for (i in index until nodes.size) {
                val n = nodes[i]
                val p = at(n)
                run += p
                val last = i == nodes.size - 1
                if (n.kind != PathNode.Kind.WALK || last) {
                    LineBox.polyline(pose, buf, rounded(run), 0.3f, 0.9f, 1f, 1f)
                    LineBox.draw(pose, buf, cube(p, 0.22), 0.3f, 0.9f, 1f, 1f)
                    run = ArrayList<Vec3>().also { it += p }
                }
            }
        }

        // The teleport landings and the end, as solid blocks you can see through walls.
        context.geometry(CustomRenderType.FILLED_ESP) { pose, buf ->
            for (i in index until nodes.size) {
                val n = nodes[i]
                if (n.kind == PathNode.Kind.WALK && i != nodes.size - 1) continue
                val box = AABB(n.x.toDouble(), n.y + n.standY, n.z.toDouble(), n.x + 1.0, n.y + n.standY + 1.0, n.z + 1.0).move(-cam.x, -cam.y, -cam.z)
                val c = when (n.kind) { PathNode.Kind.ETHER -> floatArrayOf(0.2f, 0.55f, 1f); PathNode.Kind.INSTANT -> floatArrayOf(0.6f, 0.4f, 1f); else -> floatArrayOf(0.3f, 0.9f, 1f) }
                FilledBox.draw(pose, buf, box, c[0], c[1], c[2], if (i == index) 0.35f else 0.2f)
            }
        }
    }

    // ---------------------------------------------------------------- command

    fun commandTree(): LiteralArgumentBuilder<FabricClientCommandSource> {
        fun say(ctx: com.mojang.brigadier.context.CommandContext<FabricClientCommandSource>, msg: String) =
            ctx.source.sendFeedback(Component.literal("§8[§dCC§8] §7$msg"))
        return ClientCommands.literal("path")
            .then(
                ClientCommands.literal("goto").then(
                    ClientCommands.argument("x", IntegerArgumentType.integer()).then(
                        ClientCommands.argument("y", IntegerArgumentType.integer()).then(
                            ClientCommands.argument("z", IntegerArgumentType.integer()).executes { ctx ->
                                if (!isEnabled) {
                                    say(ctx, "Turn Pathfinder on in the ClickGUI first.")
                                } else {
                                    goTo(BlockPos(IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z")))
                                }
                                1
                            }
                        )
                    )
                )
            )
            .then(
                ClientCommands.literal("record")
                    .then(
                        ClientCommands.literal("start")
                            .executes { ctx -> PathRecorder.start(null); say(ctx, "Recording your walk. /cedclient path record stop to save it."); 1 }
                            .then(ClientCommands.argument("name", StringArgumentType.word()).executes { ctx ->
                                PathRecorder.start(StringArgumentType.getString(ctx, "name")); say(ctx, "Recording your walk. /cedclient path record stop to save it."); 1
                            })
                    )
                    .then(ClientCommands.literal("stop").executes { ctx ->
                        val f = PathRecorder.stop()
                        say(ctx, if (f == null) "Not recording." else "Saved ${f.name} to ${f.parentFile.path}")
                        1
                    })
                    .then(ClientCommands.literal("mark").then(ClientCommands.argument("text", StringArgumentType.greedyString()).executes { ctx ->
                        PathRecorder.mark(StringArgumentType.getString(ctx, "text")); say(ctx, "Marked."); 1
                    }))
            )
            .then(
                ClientCommands.literal("slayer")
                    .then(
                        ClientCommands.literal("start")
                            .executes { ctx -> SlayerRecorder.start(null); say(ctx, "Recording your slayer fight. /cedclient path slayer stop to save it."); 1 }
                            .then(ClientCommands.argument("name", StringArgumentType.word()).executes { ctx ->
                                SlayerRecorder.start(StringArgumentType.getString(ctx, "name")); say(ctx, "Recording your slayer fight. /cedclient path slayer stop to save it."); 1
                            })
                    )
                    .then(ClientCommands.literal("stop").executes { ctx ->
                        val f = SlayerRecorder.stop()
                        say(ctx, if (f == null) "Not recording." else "Saved ${f.name} to ${f.parentFile.path}")
                        1
                    })
                    .then(ClientCommands.literal("mark").then(ClientCommands.argument("text", StringArgumentType.greedyString()).executes { ctx ->
                        SlayerRecorder.mark(StringArgumentType.getString(ctx, "text")); say(ctx, "Marked."); 1
                    }))
            )
            .then(ClientCommands.literal("probe").executes { ctx ->
                val p = mc.player
                val level = mc.level
                if (p == null || level == null) {
                    say(ctx, "Not in a world.")
                } else {
                    val lines = Planner.probe(level, BlockPos.containing(p.x, p.y + 0.01, p.z), maxDrop.value.toInt())
                    lines.forEach { say(ctx, it) }
                    lines.forEach { Debug.line("Pathfinder", "probe: $it") }
                }
                1
            })
            .then(ClientCommands.literal("forget").executes { ctx ->
                say(ctx, "Forgot ${PathFailures.clear()} remembered failed teleports.")
                1
            })
            .then(ClientCommands.literal("stop").executes { ctx -> stop(false); say(ctx, "Stopped."); 1 })
            .then(ClientCommands.literal("status").executes { ctx ->
                val g = goal
                say(ctx, if (g == null) "Not walking." else "Walking to ${g.x} ${g.y} ${g.z}, stop $index of ${route.size - 1}.")
                1
            })
    }
}
//#endif
