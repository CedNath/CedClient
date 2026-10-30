package ced.cedclient.features.impl.funqol

import ced.cedclient.render.geometry
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.render.FilledBox
import ced.cedclient.render.LineBox
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.DungeonScanner
import ced.cedclient.state.DungeonState
import ced.cedclient.state.HotbarDungeonMap
import ced.cedclient.utils.Color
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Door Highlight: boxes the wither doors (coal) and the blood door (red terracotta) during clear (through walls with See Through Walls, Cheat build).
 *
 * The blocks are read straight from the world, so it works without a map scan: every door
 * sits on the fixed dungeon grid (tile centers at -185 + 16 * n, door tiles = exactly one odd index), 3x3 wide
 * and 4 tall from y 69. A door whose blocks are gone (opened) simply stops showing.
 *
 * Which doors: before the run starts (Mort's map line, or
 * the hotbar map lining up) every locked door; after it only the ones the hotbar map shows (HotbarDungeonMap),
 * plus the locked door out of the fairy room while you're in the room before it, unless Highlight All Doors is on.
 * Every shown door of a type goes green while the party holds a key of that type.
 *
 * The tracer and the fairy door come from DungeonScanner's route from your room to blood: the tracer goes to the
 * first still-locked door on it, the fairy door is the (open) door on it into the fairy room, shown until you are
 * in the fairy room or past it. While that route isn't fully loaded yet, the tracer falls back to the nearest door.
 *
 * Keys are counted from the Hypixel pickup / open chat lines (two wither keys = two doors), so "Only With Key"
 * and the key color know whether the party holds a key for that door.
 */
object DoorESP : Module(
    "Door Highlight",
    Category.Skyblock,
    "Boxes wither doors and the blood door during dungeon clear."
) {
    private val witherDoors = BooleanSetting("Wither Doors", true, "Box the coal (wither key) doors.")
    private val witherColor = ColorSetting("Wither Door Color", Color(255, 40, 40), "Wither door color (all but the next one once a wither key is picked up).")
    private val bloodDoor = BooleanSetting("Blood Door", true, "Box the red (blood key) door.")
    private val bloodColor = ColorSetting("Blood Door Color", Color(255, 40, 40), "Blood door color until the blood key is picked up.")
    private val fairyDoor = BooleanSetting("Fairy Door", true, "Box the open fairy room door on the way to blood (until you walk through). Green once the wither key for the door after the fairy room is picked up.")
    private val fairyColor = ColorSetting("Fairy Door Color", Color(255, 40, 40), "Fairy room door color.")
    private val keyColor = ColorSetting("Has Key Color", Color(60, 255, 60), "Doors of a type the party holds a key for get this color.")
    private val onlyWithKey = BooleanSetting("Only With Key", false, "Only show doors whose key the party holds (and the fairy door).")
    private val highlightAll = BooleanSetting("Highlight All Doors", false, "After the run starts, still box every locked door instead of only the ones the dungeon map shows.")
    private val style = DropdownSetting("Style", listOf("Outline", "Filled", "Filled Outline"), "Outline")
//#if CHEAT
    private val throughWalls = BooleanSetting("See Through Walls", true, "Show the boxes through blocks (ESP). Off: they hide behind walls like normal.")
    private val tracers = BooleanSetting("Tracers", false, "Draws a line to the next door to open on the way to blood.")
    private val xray get() = throughWalls.value
    private val tracing get() = tracers.value
//#else
//$    private val xray get() = false
//$    private val tracing get() = false
//#endif
    private val onlyInDungeon = BooleanSetting("Only In Dungeons", true, "Off: also scan outside dungeons (e.g. a copied map).")

    private val CODES = Regex("§.")
    private val keyPickupRegex = Regex("""^(?:(?:\[.+?] )?\w+ has obtained|A) (?<type>Wither|Blood) Key(?: was picked up)?!$""")
    private val witherOpenedRegex = Regex("""^(?:\[.+?] )?\w+ opened a WITHER door!$""")
    private const val BLOOD_OPENED = "The BLOOD DOOR has been opened!"
    private const val RUN_STARTED = "[NPC] Mort: Here, I found this map when I first entered the dungeon."

    /** Door tile centers (x, z) on the 11x11 dungeon grid. */
    private val DOOR_SPOTS: List<Pair<Int, Int>> = (0..10).flatMap { i -> (0..10).map { j -> i to j } }
        .filter { (i, j) -> (i % 2 == 1) != (j % 2 == 1) }
        .map { (i, j) -> -185 + 16 * i to -185 + 16 * j }

    init {
        check(DOOR_SPOTS.size == 60) { "door grid" }
    }

    private enum class Kind { WITHER, BLOOD, FAIRY }

    private class Door(val x: Int, val z: Int, val kind: Kind) {
        val box = AABB(x - 1.0, 69.0, z - 1.0, x + 2.0, 73.0, z + 2.0)
    }

    @Volatile private var doors: List<Door> = emptyList()
    private var witherKeys = 0
    private var bloodKeys = 0
    private var runStarted = false
    /** World (x, z) of the door out of the fairy room while the fairy exception applies, else null. */
    private var fairyExit: Pair<Int, Int>? = null
    private var lastLevel: ClientLevel? = null
    private var scanDelay = 0

    private val mc get() = Minecraft.getInstance()

    init {
//#if CHEAT
        addSettings(witherDoors, witherColor, bloodDoor, bloodColor, fairyDoor, fairyColor, keyColor, onlyWithKey, highlightAll, style, throughWalls, tracers, onlyInDungeon)
//#else
//$        addSettings(witherDoors, witherColor, bloodDoor, bloodColor, fairyDoor, fairyColor, keyColor, onlyWithKey, highlightAll, style, onlyInDungeon)
//#endif

        ClientTickEvents.END_CLIENT_TICK.register { scan() }

        ClientReceiveMessageEvents.GAME.register { message, _ ->
            val plain = message.string.replace(CODES, "").trim()
            keyPickupRegex.find(plain)?.let { if (it.groups["type"]!!.value == "Wither") witherKeys++ else bloodKeys++ }
            if (witherOpenedRegex.matches(plain)) witherKeys = maxOf(0, witherKeys - 1)
            if (plain == BLOOD_OPENED) bloodKeys = maxOf(0, bloodKeys - 1)
            if (plain == RUN_STARTED) {
                runStarted = true
                ced.cedclient.utils.Debug.line("Door Highlight", "run started, keys reset")
            }
        }

        LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { context ->
            if (!isEnabled) return@CollectSubmits
            val current = doors
            if (current.isEmpty()) return@CollectSubmits

            val camera = context.gameRenderer().mainCamera()
            val cam = camera.position()
            val look = Vec3.directionFromRotation(camera.xRot(), camera.yRot())

            // The fairy door counts as the next door while it is ahead; it goes green with the wither key from the room before it.
            val fairy = neededFairyDoor()
            val pathNext = DungeonScanner.bloodPath.firstOrNull { it === fairy || it.locked }
            fun nearest(kind: Kind) = current.filter { it.kind == kind }.minByOrNull { it.box.center.distanceToSqr(cam) }
            val tracerTo = if (pathNext != null) current.firstOrNull { it.x == pathNext.x && it.z == pathNext.z }
                else nearest(Kind.WITHER) ?: nearest(Kind.BLOOD)
            val hideUndiscovered = (runStarted || HotbarDungeonMap.calibrated) && !highlightAll.value
            // Fairy exception: the door between the fairy room and the room after it shows early while you're
            // in the room before the fairy room (route starts with the door into fairy) or have just walked from there
            // into the fairy room (route now starts with that same door).
            val path = DungeonScanner.bloodPath
            if (path.firstOrNull()?.intoFairy == true) fairyExit = path.getOrNull(1)?.let { it.x to it.z }
            else if (path.firstOrNull()?.let { it.x to it.z } != fairyExit) fairyExit = null
            val pastFairy = fairyExit

            val shown = current.mapNotNull { door ->
                // The fairy door goes green with the wither key for the door after the fairy room.
                val hasKey = if (door.kind == Kind.BLOOD) bloodKeys > 0 else witherKeys > 0
                if (onlyWithKey.value && !hasKey && door.kind != Kind.FAIRY) return@mapNotNull null
                if (hideUndiscovered && door.kind != Kind.FAIRY && (door.x to door.z) !in HotbarDungeonMap.discoveredDoors &&
                    (door.x to door.z) != pastFairy) return@mapNotNull null
                val c = when {
                    hasKey -> keyColor.value
                    door.kind == Kind.FAIRY -> fairyColor.value
                    door.kind == Kind.BLOOD -> bloodColor.value
                    else -> witherColor.value
                }
                Triple(door.box.move(-cam.x, -cam.y, -cam.z), c, door === tracerTo)
            }
            if (style.value != "Outline") {
                // See-through fill (40% of the colour's opacity) so the door itself stays visible.
                context.geometry(if (xray) CustomRenderType.FILLED_ESP else CustomRenderType.FILLED) { pose, fill ->
                    for ((box, c, _) in shown) FilledBox.draw(pose, fill, box, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat * 0.4f)
                }
            }
            context.geometry(if (xray) CustomRenderType.LINES_ESP else CustomRenderType.LINES_DEPTH) { pose, buffer ->
                for ((box, c, traced) in shown) {
                    if (style.value != "Filled") LineBox.draw(pose, buffer, box, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat)
                    if (tracing && traced) LineBox.polyline(pose, buffer, listOf(look, box.center), c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat)
                }
            }
        })
    }

    private val DungeonScanner.PathDoor.locked get() = spot == DungeonScanner.Spot.WITHER || spot == DungeonScanner.Spot.BLOOD

    /**
     * The open door into the fairy room on the route to blood, but only while a locked door still follows it: with
     * everything behind the fairy room open (walking back after blood), the route passing through fairy needs no box.
     */
    private fun neededFairyDoor(): DungeonScanner.PathDoor? {
        val path = DungeonScanner.bloodPath
        val i = path.indexOfFirst { it.intoFairy && it.spot == DungeonScanner.Spot.NORMAL }
        return path.getOrNull(i)?.takeIf { path.drop(i + 1).any { it.locked } }
    }

    private fun scan() {
        val level = mc.level
        if (level !== lastLevel) {
            lastLevel = level
            witherKeys = 0
            bloodKeys = 0
            runStarted = false
            fairyExit = null
            DungeonScanner.reset()
            HotbarDungeonMap.reset()
        }
        if (!isEnabled || level == null || (onlyInDungeon.value && (!DungeonState.active || DungeonState.inBoss))) {
            if (doors.isNotEmpty()) doors = emptyList()
            return
        }
        val player = mc.player
        // Every 10 ticks, so the route follows you right after a leap.
        if (player != null && scanDelay-- <= 0) {
            scanDelay = 10
            DungeonScanner.scan(level, player.x, player.z)
            HotbarDungeonMap.update(level, player)
        }
        val pos = BlockPos.MutableBlockPos()
        val found = DOOR_SPOTS.mapNotNull { (x, z) ->
            val state = level.getBlockState(pos.set(x, 69, z))
            // I Hate Doors swaps the door for glass on screen; it remembers what the door was.
//#if CHEAT
            val original = IHateDoors.originalOf(x, z, state)
//#else
//$            val original: net.minecraft.world.level.block.Block? = null
//#endif
            when {
                state.`is`(Blocks.DYED_TERRACOTTA.red()) || original == Blocks.DYED_TERRACOTTA.red() -> if (bloodDoor.value) Door(x, z, Kind.BLOOD) else null
                state.`is`(Blocks.COAL_BLOCK) || original == Blocks.COAL_BLOCK -> if (witherDoors.value) Door(x, z, Kind.WITHER) else null
                else -> null
            }
        }
        val fairy = neededFairyDoor()
        doors = if (fairy != null && fairyDoor.value) found + Door(fairy.x, fairy.z, Kind.FAIRY) else found
    }
}
