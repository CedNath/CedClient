package ced.cedclient.state

import ced.cedclient.render.cosmetic.CosmeticRooms
import ced.cedclient.render.cosmetic.RoomInfo
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks

/**
 * Dungeon layout from the world blocks. The Catacombs grid is 11x11 cells 16 blocks apart
 * from -185: both indices even = a room tile (identified by its core via CosmeticRooms), exactly one odd = the
 * spot between two room tiles. A spot whose top block is at a door roof height (73 / 74 / 81 / 82) is a door,
 * typed by the block at y 69; any other top block means both tiles are one room; nothing there = a wall.
 *
 * From that it finds the route from the room you stand in to blood ([bloodPath]), so doors behind you (even ones
 * opened while unloaded, still cached as locked) never count after a leap. Results stay cached for the run, so a
 * part of the dungeon that unloads later keeps its last known state. Call [scan] from a tick; a new world resets it.
 */
object DungeonScanner {
    enum class Spot { SEPARATOR, WITHER, BLOOD, ENTRANCE, NORMAL }

    /** A door on the route: [intoFairy] = it leads into the fairy room (no key, but you must pass it). */
    class PathDoor(val x: Int, val z: Int, val spot: Spot, val intoFairy: Boolean)

    private const val START = -185
    private val DOOR_HEIGHTS = setOf(73, 74, 81, 82)
    private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private val rooms = arrayOfNulls<RoomInfo>(36)
    private val spots = arrayOfNulls<Spot>(121)

    /** Doors from your room (outside the grid: the entrance) to the blood room, in order. Empty until the route is loaded. */
    var bloodPath: List<PathDoor> = emptyList()
        private set

    /** Bumped whenever a room or spot changes, so callers can cache what they derive. */
    var version = 0
        private set

    private val pos = BlockPos.MutableBlockPos()
    private var lastLevel: ClientLevel? = null

    /** Identified room on tile ([i], [j]) of the 6x6 grid (i along x, j along z), null = not seen yet. */
    fun room(i: Int, j: Int): RoomInfo? = if (i in 0..5 && j in 0..5) rooms[i * 6 + j] else null // a player outside the grid (boss room, entrance, lobby) is not a room

    /** What sits between room tiles on the 11x11 grid spot ([i], [j]) (exactly one index odd), null = wall / not seen. */
    fun spot(i: Int, j: Int): Spot? = if (i in 0..10 && j in 0..10) spots[i * 11 + j] else null

    fun reset() {
        rooms.fill(null)
        spots.fill(null)
        bloodPath = emptyList()
        version++
        lastScanTick = Long.MIN_VALUE
    }

    private var lastScanTick = Long.MIN_VALUE

    fun scan(level: ClientLevel, playerX: Double, playerZ: Double) {
        if (level !== lastLevel) { lastLevel = level; reset() }
        // Door ESP and the map both scan every 10 ticks: the second caller within 4 ticks would only repeat the work.
        val now = level.gameTime
        if (now - lastScanTick in 0..3) return
        lastScanTick = now
        for (i in 0..10) for (j in 0..10) {
            val x = START + 16 * i
            val z = START + 16 * j
            if (i % 2 == 0 && j % 2 == 0) {
                val r = (i / 2) * 6 + j / 2
                if (rooms[r] == null) {
                    rooms[r] = CosmeticRooms.roomInfoAt(level, x, z)
                    if (rooms[r] != null) version++
                }
            } else if ((i % 2 == 1) != (j % 2 == 1)) {
                // Two tiles of one room stay joined for the run: no need to look again.
                if (spots[i * 11 + j] == Spot.SEPARATOR) continue
                val top = highestY(level, x, z)
                if (top == 0) continue // wall, or not loaded yet: keep what was seen before
                val spot = if (top !in DOOR_HEIGHTS) Spot.SEPARATOR else {
                    val state = level.getBlockState(pos.set(x, 69, z))
                    // I Hate Doors swaps the door for glass on screen; it remembers what the door was.
//#if CHEAT
                    val original = ced.cedclient.features.impl.funqol.IHateDoors.originalOf(x, z, state)
//#else
//$                    val original: net.minecraft.world.level.block.Block? = null
//#endif
                    when {
                        state.`is`(Blocks.COAL_BLOCK) || original == Blocks.COAL_BLOCK -> Spot.WITHER
                        state.`is`(Blocks.DYED_TERRACOTTA.red()) || original == Blocks.DYED_TERRACOTTA.red() -> Spot.BLOOD
                        state.`is`(Blocks.INFESTED_CHISELED_STONE_BRICKS) -> Spot.ENTRANCE
                        else -> Spot.NORMAL
                    }
                }
                if (spots[i * 11 + j] != spot) { spots[i * 11 + j] = spot; version++ }
            }
        }
        val ri = Math.floorDiv(playerX.toInt() - START + 16, 32)
        val rj = Math.floorDiv(playerZ.toInt() - START + 16, 32)
        bloodPath = findPath(if (ri in 0..5 && rj in 0..5) ri * 6 + rj else rooms.indexOfFirst { it?.type == "ENTRANCE" })
    }

    /** Breadth-first from room tile [start] to a blood tile over doors and same-room joins. */
    private fun findPath(start: Int): List<PathDoor> {
        if (start < 0 || rooms.none { it?.type == "BLOOD" }) return emptyList()
        val parent = IntArray(36) { -1 }
        parent[start] = start
        val queue = ArrayDeque(listOf(start))
        var end = -1
        while (queue.isNotEmpty()) {
            val r = queue.removeFirst()
            if (rooms[r]?.type == "BLOOD") { end = r; break }
            for ((di, dj) in NEIGHBOURS) {
                val ni = r / 6 + di; val nj = r % 6 + dj
                if (ni !in 0..5 || nj !in 0..5) continue
                val n = ni * 6 + nj
                if (parent[n] >= 0 || spots[spotIndex(r, n)] == null) continue
                parent[n] = r
                queue.addLast(n)
            }
        }
        if (end < 0) return emptyList()

        val doors = ArrayList<PathDoor>()
        var r = end
        while (r != start) {
            val from = parent[r]
            val spot = spots[spotIndex(from, r)]!!
            if (spot != Spot.SEPARATOR) {
                // The spot's grid index is the sum of the two room indices on each axis.
                doors += PathDoor(START + 16 * (from / 6 + r / 6), START + 16 * (from % 6 + r % 6), spot, rooms[r]?.type == "FAIRY")
            }
            r = from
        }
        return doors.asReversed()
    }

    /** Grid index of the spot between neighbouring room tiles [a] and [b] (room index = ri * 6 + rj). */
    private fun spotIndex(a: Int, b: Int): Int = (a / 6 + b / 6) * 11 + a % 6 + b % 6

    /** Top block y of a column (air and gold skipped, same as the room cores), 0 = nothing / not loaded. */
    private fun highestY(level: ClientLevel, x: Int, z: Int): Int {
        for (y in 140 downTo 60) {
            val state = level.getBlockState(pos.set(x, y, z))
            if (!state.isAir && !state.`is`(Blocks.GOLD_BLOCK)) return y
        }
        return 0
    }
}
