//#if CHEAT
package ced.cedclient.features.impl.funqol

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
 * Door ESP: boxes the wither doors (coal) and the blood door (red terracotta) through walls during clear.
 *
 * NoammAddons' version only draws doors its dungeon-map scanner has tied to the room you're standing in, so it
 * shows nothing whenever that scan is off. This reads the blocks straight from the world instead: every door
 * sits on the fixed dungeon grid (tile centers at -185 + 16 * n, door tiles = exactly one odd index), 3x3 wide
 * and 4 tall from y 69. A door whose blocks are gone (opened) simply stops showing.
 *
 * "Next door" and the fairy door come from DungeonScanner's route from your room to blood: the next door is the
 * first still-locked door on it, the fairy door the (open) door on it into the fairy room, shown until you are in
 * the fairy room or past it. While that route isn't fully loaded yet, the next door falls back to the nearest one of the key's type.
 *
 * Key tracking comes from the Hypixel pickup / open chat lines, so "Only With Key" and the key color know
 * whether the party holds a key for that door.
 */
object DoorESP : Module(
    "Door ESP",
    Category.Cheats,
    "Boxes wither doors and the blood door through walls during dungeon clear."
) {
    private val witherDoors = BooleanSetting("Wither Doors", true, "Box the coal (wither key) doors.")
    private val witherColor = ColorSetting("Wither Door Color", Color(255, 40, 40), "Wither door color (all but the next one once a wither key is picked up).")
    private val bloodDoor = BooleanSetting("Blood Door", true, "Box the red (blood key) door.")
    private val bloodColor = ColorSetting("Blood Door Color", Color(255, 40, 40), "Blood door color until the blood key is picked up.")
    private val fairyDoor = BooleanSetting("Fairy Door", true, "Box the open fairy room door on the way to blood (until you walk through). Green once the wither key for the door after the fairy room is picked up.")
    private val fairyColor = ColorSetting("Fairy Door Color", Color(255, 40, 40), "Fairy room door color.")
    private val keyColor = ColorSetting("Next Door Color", Color(60, 255, 60), "Once a key is picked up, the next door on the way to blood gets this color.")
    private val onlyWithKey = BooleanSetting("Only With Key", false, "Only show the next door once its key is picked up (and the fairy door).")
    private val style = DropdownSetting("Style", listOf("Outline", "Filled", "Filled Outline"), "Outline")
    private val tracers = BooleanSetting("Tracers", false, "Draws a line to the next door to open on the way to blood.")
    private val onlyInDungeon = BooleanSetting("Only In Dungeons", true, "Off: also scan outside dungeons (e.g. a copied map).")

    private val CODES = Regex("§.")
    private val keyPickupRegex = Regex("""^(?:(?:\[.+?] )?\w+ has obtained|A) (?<type>Wither|Blood) Key(?: was picked up)?!$""")
    private val witherOpenedRegex = Regex("""^(?:\[.+?] )?\w+ opened a WITHER door!$""")
    private const val BLOOD_OPENED = "The BLOOD DOOR has been opened!"

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
    private var witherKey = false
    private var bloodKey = false
    private var lastLevel: ClientLevel? = null
    private var scanDelay = 0

    private val mc get() = Minecraft.getInstance()

    init {
        addSettings(witherDoors, witherColor, bloodDoor, bloodColor, fairyDoor, fairyColor, keyColor, onlyWithKey, style, tracers, onlyInDungeon)

        ClientTickEvents.END_CLIENT_TICK.register { scan() }

        ClientReceiveMessageEvents.GAME.register { message, _ ->
            val plain = message.string.replace(CODES, "").trim()
            keyPickupRegex.find(plain)?.let { if (it.groups["type"]!!.value == "Wither") witherKey = true else bloodKey = true }
            if (witherOpenedRegex.matches(plain)) witherKey = false
            if (plain == BLOOD_OPENED) bloodKey = false
        }

        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            if (!isEnabled) return@AfterSolidFeatures
            val current = doors
            if (current.isEmpty()) return@AfterSolidFeatures

            val poseStack = context.poseStack()
            val camera = context.gameRenderer().mainCamera
            val cam = camera.position()
            val bufferSource = context.bufferSource()
            val look = Vec3.directionFromRotation(camera.xRot(), camera.yRot())

            // The fairy door counts as the next door while it is ahead; it goes green with the wither key from the room before it.
            val pathNext = DungeonScanner.bloodPath.firstOrNull {
                it.spot == DungeonScanner.Spot.WITHER || it.spot == DungeonScanner.Spot.BLOOD ||
                    (it.intoFairy && it.spot == DungeonScanner.Spot.NORMAL)
            }
            fun nearest(kind: Kind) = current.filter { it.kind == kind }.minByOrNull { it.box.center.distanceToSqr(cam) }
            val nextDoor = if (pathNext != null) current.firstOrNull { it.x == pathNext.x && it.z == pathNext.z }
                else nearest(Kind.WITHER) ?: nearest(Kind.BLOOD)
            val keyForNext = when (nextDoor?.kind) { Kind.WITHER -> witherKey; Kind.BLOOD -> bloodKey; Kind.FAIRY -> witherKey; null -> false }
            val greenDoor = nextDoor?.takeIf { keyForNext }
            val tracerTo = nextDoor

            val shown = current.mapNotNull { door ->
                val isNext = door === greenDoor
                if (onlyWithKey.value && !isNext && door.kind != Kind.FAIRY) return@mapNotNull null
                val c = when {
                    isNext -> keyColor.value
                    door.kind == Kind.FAIRY -> fairyColor.value
                    door.kind == Kind.BLOOD -> bloodColor.value
                    else -> witherColor.value
                }
                Triple(door.box.move(-cam.x, -cam.y, -cam.z), c, door === tracerTo)
            }
            if (style.value != "Outline") {
                val fill = bufferSource.getBuffer(CustomRenderType.FILLED_ESP)
                // See-through fill (40% of the colour's opacity) so the door itself stays visible.
                for ((box, c, _) in shown) FilledBox.draw(poseStack, fill, box, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat * 0.4f)
                bufferSource.endBatch()
            }
            val buffer = bufferSource.getBuffer(CustomRenderType.LINES_ESP)
            for ((box, c, traced) in shown) {
                if (style.value != "Filled") LineBox.draw(poseStack, buffer, box, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat)
                if (tracers.value && traced) LineBox.polyline(poseStack, buffer, listOf(look, box.center), c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat)
            }
            bufferSource.endBatch()
        })
    }

    private fun scan() {
        val level = mc.level
        if (level !== lastLevel) {
            lastLevel = level
            witherKey = false
            bloodKey = false
            DungeonScanner.reset()
        }
        if (!isEnabled || level == null || (onlyInDungeon.value && (!DungeonState.inDungeon || DungeonState.inBoss))) {
            if (doors.isNotEmpty()) doors = emptyList()
            return
        }
        val player = mc.player
        // Every 10 ticks, so the route follows you right after a leap.
        if (player != null && scanDelay-- <= 0) { scanDelay = 10; DungeonScanner.scan(level, player.x, player.z) }
        val pos = BlockPos.MutableBlockPos()
        val found = DOOR_SPOTS.mapNotNull { (x, z) ->
            val state = level.getBlockState(pos.set(x, 69, z))
            when {
                state.`is`(Blocks.RED_TERRACOTTA) -> if (bloodDoor.value) Door(x, z, Kind.BLOOD) else null
                state.`is`(Blocks.COAL_BLOCK) -> if (witherDoors.value) Door(x, z, Kind.WITHER) else null
                else -> null
            }
        }
        val fairy = DungeonScanner.bloodPath.firstOrNull { it.intoFairy && it.spot == DungeonScanner.Spot.NORMAL }
        doors = if (fairy != null && fairyDoor.value) found + Door(fairy.x, fairy.z, Kind.FAIRY) else found
    }
}
//#endif
