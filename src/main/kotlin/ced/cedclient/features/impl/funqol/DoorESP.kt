//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.render.LineBox
import ced.cedclient.render.pipeline.CustomRenderType
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
 * Key tracking comes from the Hypixel pickup / open chat lines, so "Only With Key" and the key color know
 * whether the party holds a key for that door.
 */
object DoorESP : Module(
    "Door ESP",
    Category.Cheats,
    "Boxes wither doors and the blood door through walls during dungeon clear."
) {
    private val witherDoors = BooleanSetting("Wither Doors", true, "Box the coal (wither key) doors.")
    private val witherColor = ColorSetting("Wither Door Color", Color(40, 40, 40), "Wither door color while nobody has a wither key.")
    private val bloodDoor = BooleanSetting("Blood Door", true, "Box the red (blood key) door.")
    private val bloodColor = ColorSetting("Blood Door Color", Color(255, 40, 40), "Blood door color while nobody has the blood key.")
    private val keyColor = ColorSetting("Key Held Color", Color(60, 255, 60), "Door color once the party holds its key.")
    private val onlyWithKey = BooleanSetting("Only With Key", false, "Only show doors the party can open right now (key picked up).")
    private val tracers = BooleanSetting("Tracers", false, "Draws a line to every shown door.")
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

    private class Door(val box: AABB, val blood: Boolean)

    @Volatile private var doors: List<Door> = emptyList()
    private var witherKey = false
    private var bloodKey = false
    private var lastLevel: ClientLevel? = null

    private val mc get() = Minecraft.getInstance()

    init {
        addSettings(witherDoors, witherColor, bloodDoor, bloodColor, keyColor, onlyWithKey, tracers, onlyInDungeon)

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
            val buffer = bufferSource.getBuffer(CustomRenderType.LINES_ESP)
            val look = Vec3.directionFromRotation(camera.xRot(), camera.yRot())

            for (door in current) {
                val hasKey = if (door.blood) bloodKey else witherKey
                if (onlyWithKey.value && !hasKey) continue
                val c = if (hasKey) keyColor.value else if (door.blood) bloodColor.value else witherColor.value
                LineBox.draw(poseStack, buffer, door.box.move(-cam.x, -cam.y, -cam.z), c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat)
                if (tracers.value) {
                    LineBox.polyline(poseStack, buffer, listOf(look, door.box.center.subtract(cam)), c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat)
                }
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
        }
        if (!isEnabled || level == null || (onlyInDungeon.value && (!DungeonState.inDungeon || DungeonState.inBoss))) {
            if (doors.isNotEmpty()) doors = emptyList()
            return
        }
        val pos = BlockPos.MutableBlockPos()
        doors = DOOR_SPOTS.mapNotNull { (x, z) ->
            val block = level.getBlockState(pos.set(x, 69, z)).block
            val blood = when {
                block === Blocks.RED_TERRACOTTA -> true
                block === Blocks.COAL_BLOCK -> false
                else -> return@mapNotNull null
            }
            if (if (blood) !bloodDoor.value else !witherDoors.value) return@mapNotNull null
            Door(AABB(x - 1.0, 69.0, z - 1.0, x + 2.0, 73.0, z + 2.0), blood)
        }
    }
}
//#endif
