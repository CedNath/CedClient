package ced.cedclient.state

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents

/**
 * Reads the Catacombs map in hotbar slot 9: the
 * entrance room's green (color 30) run gives the room size (16 or 18 px) and the grid's top-left corner, then each
 * of the 11x11 grid cells is sampled at its center. A cell is a door when its center has a door color and the pixel
 * 4 px to the side of the passage is empty (otherwise it's a room joining two tiles).
 *
 * [discoveredDoors] only grows during a run: a door the map showed once counts as discovered.
 * Call [update] from a tick, [reset] on world change.
 */
object HotbarDungeonMap {
    private const val START = -185
    /** Map colors of doors: blood 18, entrance 30, wither 119, normal doors 74 / 82 / 66 / 62 / 85 / 63. */
    private val DOOR_COLORS = setOf(18, 30, 119, 74, 82, 66, 62, 85, 63)

    /** True once the map lined up with the grid; that counts as the run having started. */
    var calibrated = false
        private set

    /** World (x, z) of every door the map shows. */
    val discoveredDoors = HashSet<Pair<Int, Int>>()

    private var cornerX = 0
    private var cornerZ = 0
    private var roomSize = 16

    fun reset() {
        calibrated = false
        discoveredDoors.clear()
    }

    fun update(level: ClientLevel, player: LocalPlayer) {
        val id = player.inventory.getItem(8).get(DataComponents.MAP_ID) ?: return
        val colors = level.getMapData(id)?.colors ?: return
        if (colors.size < 128 * 128) return
        if (!calibrated && !calibrate(colors)) return

        val halfRoom = roomSize / 2
        val halfTile = halfRoom + 2
        for (i in 0..10) for (j in 0..10) {
            if ((i % 2 == 1) == (j % 2 == 1)) continue // room tile or pillar, not a door spot
            val mapX = cornerX + halfRoom + i * halfTile
            val mapZ = cornerZ + halfRoom + j * halfTile
            if (mapX >= 128 || mapZ >= 128) continue
            val center = colors[mapZ * 128 + mapX].toInt()
            // Sideways from the passage: a door is narrow (empty there), a room joining two tiles is not.
            val side = colors.getOrNull(if (j % 2 == 1) mapZ * 128 + mapX - 4 else (mapZ - 4) * 128 + mapX)?.toInt() ?: 0
            if (center in DOOR_COLORS && side == 0) discoveredDoors += (START + 16 * i) to (START + 16 * j)
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
        return true
    }
}
