package ced.cedclient.state

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes

/**
 * Reads the Catacombs map in hotbar slot 9: the
 * entrance room's green (color 30) run gives the room size (16 or 18 px) and the grid's top-left corner, then each
 * of the 11x11 grid cells is sampled at its center. A cell is a door when its center has a door color and the pixel
 * 4 px to the side of the passage is empty (otherwise it's a room joining two tiles).
 *
 * [discoveredDoors] and [roomStates] only grow during a run: whatever the map showed once counts as discovered.
 * A room's state comes from two pixels: its corner (grey 85 = not opened yet) and its centre, where Hypixel draws
 * the checkmark (white 34 = cleared, green 30 = all secrets, red 18 = failed). [markers] are the map's player icons.
 * Call [update] from a tick; a new world resets it.
 */
object HotbarDungeonMap {
    private const val START = -185
    /** Map colors of doors: blood 18, entrance 30, wither 119, normal doors 74 / 82 / 66 / 62 / 85 / 63. */
    private val DOOR_COLORS = setOf(18, 30, 119, 74, 82, 66, 62, 85, 63)

    /** True once the map lined up with the grid; that counts as the run having started. */
    var calibrated = false
        private set

    /** Bumped whenever [calibrated], [roomStates] or [discoveredDoors] change, so callers can cache what they derive. */
    var version = 0
        private set

    /** World (x, z) of every door the map shows. */
    val discoveredDoors = HashSet<Pair<Int, Int>>()

    enum class RoomState { UNEXPLORED, OPENED, CLEARED, GREEN, FAILED }

    /** Room tiles (i, j) on the 6x6 grid (i along x) the map shows anything on, with what the map says about them. */
    val roomStates = HashMap<Pair<Int, Int>, RoomState>()

    /** One player icon on the map: world position, facing (yaw, degrees), [self] = your own (frame) icon. */
    class Marker(val x: Double, val z: Double, val yaw: Float, val self: Boolean, val key: String)

    /** The icon of the [index]th living teammate (self left out): the digit that ends the icon's key, not its place in the list. */
    fun iconFor(index: Int): Marker? = markers.firstOrNull { !it.self && it.key.lastOrNull()?.digitToIntOrNull() == index }

    /** Every player icon on the map, in the map's order (teammates follow the party order), refreshed each [update]. */
    var markers: List<Marker> = emptyList()
        private set

    /** Map pixel of the grid's top-left room corner, and the map's room size (16, or 18 on small floors). */
    var cornerX = 0
        private set
    var cornerZ = 0
        private set
    var roomSize = 16
        private set

    fun reset() {
        calibrated = false
        discoveredDoors.clear()
        roomStates.clear()
        markers = emptyList()
        version++
        lastSample = Long.MIN_VALUE
    }

    private var lastLevel: ClientLevel? = null
    private var lastSample = Long.MIN_VALUE

    fun update(level: ClientLevel, player: LocalPlayer) {
        if (level !== lastLevel) { lastLevel = level; reset() }
        val id = player.inventory.getItem(8).get(DataComponents.MAP_ID) ?: return
        val data = level.getMapData(id) ?: return
        val colors = data.colors
        if (colors.size < 128 * 128) return
        if (!calibrated && !calibrate(colors)) return
        readMarkers(data)
        // Rooms and doors change slowly: sampling the grid every 3rd tick is as good as every tick.
        if (level.gameTime - lastSample in 0..2) return
        lastSample = level.gameTime

        val halfRoom = roomSize / 2
        val halfTile = halfRoom + 2
        for (i in 0..10) for (j in 0..10) {
            if (i % 2 == 1 && j % 2 == 1) continue // pillar between four tiles
            val mapX = cornerX + halfRoom + i * halfTile
            val mapZ = cornerZ + halfRoom + j * halfTile
            if (mapX >= 128 || mapZ >= 128) continue
            if (i % 2 == 0 && j % 2 == 0) {
                val center = colors[mapZ * 128 + mapX].toInt()
                if (center == 0) continue
                val base = colors[(mapZ - halfRoom + 1) * 128 + mapX - halfRoom + 1].toInt()
                val state = when {
                    base == 85 -> RoomState.UNEXPLORED
                    center == 34 -> RoomState.CLEARED
                    center == 30 && base != 30 -> RoomState.GREEN
                    center == 18 && base != 18 -> RoomState.FAILED
                    else -> RoomState.OPENED
                }
                if (roomStates.put(i / 2 to j / 2, state) != state) version++
                continue
            }
            val center = colors[mapZ * 128 + mapX].toInt()
            // Sideways from the passage: a door is narrow (empty there), a room joining two tiles is not.
            val side = colors.getOrNull(if (j % 2 == 1) mapZ * 128 + mapX - 4 else (mapZ - 4) * 128 + mapX)?.toInt() ?: 0
            if (center in DOOR_COLORS && side == 0 && discoveredDoors.add((START + 16 * i) to (START + 16 * j))) version++
        }
    }

    /** Player icons: decoration x / y are half map pixels from the centre; map pixels go back to blocks via the grid. */
    private fun readMarkers(data: net.minecraft.world.level.saveddata.maps.MapItemSavedData) {
        val blocksPerPixel = 32.0 / (roomSize + 4)
        markers = (data as ced.cedclient.mixin.accessor.MapItemSavedDataAccessor).`ced$decorations`().map { (key, d) ->
            val px = (d.x() + 128) / 2.0
            val pz = (d.y() + 128) / 2.0
            Marker(
                START + (px - cornerX - roomSize / 2) * blocksPerPixel, START + (pz - cornerZ - roomSize / 2) * blocksPerPixel,
                // 16 rotation steps, 0 = facing +z like player yaw.
                d.rot() * 22.5f, d.type().value() === MapDecorationTypes.FRAME.value(), key,
            )
        }
    }

    private fun calibrate(colors: ByteArray): Boolean {
        var start = 0
        var length = 0
        for ((index, byte) in colors.withIndex()) {
            if (byte == 30.toByte()) {
                if (length == 0) start = index
                length++
            } else {
                if (length >= 16) break
                length = 0
            }
        }
        if (length != 16 && length != 18) return false
        roomSize = length
        // Small floors sit at fixed corners; the rest is derived from the entrance's spot.
        val (x, z) = when (DungeonState.floor?.drop(1)?.toIntOrNull()) {
            1 -> 22 to 11
            2, 3 -> 11 to 11
            else -> (start and 127) % (roomSize + 4) to (start shr 7) % (roomSize + 4)
        }
        cornerX = x
        cornerZ = z
        calibrated = true
        version++
        return true
    }
}
