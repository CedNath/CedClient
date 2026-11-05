//#if CHEAT
package ced.cedclient.pathing

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One stop on a route: the feet block, the height the player stands at in it, and how to get there from the node before. */
class PathNode(
    val x: Int, val y: Int, val z: Int,
    val standY: Double,
    val kind: Kind = Kind.WALK,
    /** For [Kind.ETHER]: the block the warp lands on. */
    val aim: BlockPos? = null,
) {
    enum class Kind { WALK, ETHER, INSTANT }

    fun center() = Vec3(x + 0.5, y + standY, z + 0.5)
}

class Route(val nodes: List<PathNode>, val reached: Boolean, val searched: Int, val millis: Long)

class PlanOptions(
    val maxNodes: Int,
    val maxDrop: Int,
    val timeLimitMs: Long,
    val ether: Boolean,
    val etherRange: Double,
    val etherCost: Double,
    val instant: Boolean,
    val instantRange: Double,
    val instantCost: Double,
    /** Etherwarp target blocks that failed to aim at (packed BlockPos), never planned through again. */
    val avoid: Set<Long>,
    /** The player is in the air at the start: nothing to walk from, only a fall or a teleport. */
    val startInAir: Boolean = false,
    /** A second, harder look after a normal search stalled: less greedy, teleport rays all round and from more blocks. */
    val thorough: Boolean = false,
    /** What jumping up a whole block is worth in blocks of walking: more when a jump boost makes each one a flight. */
    val stepUpCost: Double = 5.0,
    /** Long trips: how far above the higher of start and goal the flight prefers to be (0 = no preference). */
    val cruiseAbove: Int = 0,
    /** Cells (packed feet BlockPos) a teleport was blocked from by the server: no teleport is planned from them or the cells beside them. */
    val avoidFrom: Set<Long> = emptySet(),
    /** Walk cells (packed feet BlockPos) the walker got stuck against with no block the planner could see: never walked into or through again. */
    val blocked: Set<Long> = emptySet(),
) {
    val avoidFromFast = LongOpenHashSet(avoidFrom)

    fun avoidsFrom(x: Int, y: Int, z: Int): Boolean {
        if (avoidFromFast.isEmpty()) return false
        for (dx in -1..1) for (dz in -1..1) for (dy in -1..1) if (avoidFromFast.contains(BlockPos.asLong(x + dx, y + dy, z + dz))) return true
        return false
    }
    /** [avoid] as a primitive set: it is asked about for every teleport ray. */
    val avoidFast = LongOpenHashSet(avoid)
}

/**
 * A* over [Terrain]. Edges: the four sides and diagonals on the flat (no corner cutting), a one block step up,
 * a drop of up to [PlanOptions.maxDrop], and, when enabled, Aspect of the Void teleports: an etherwarp onto a
 * block it can reach (the same ray the server uses, [EtherwarpRules]) and an instant transmission leap of
 * [PlanOptions.instantRange] blocks along the look direction. Costs are in blocks of walking, so a teleport costs
 * what walking that far is not worth.
 *
 * If the goal is not reached inside the node or time budget the route to the explored node closest to it comes
 * back with reached = false, so the walker can go that far and plan again. Straight stretches are pulled tight
 * afterwards so the walker cuts corners instead of tracing every grid cell.
 */
object Planner {
    private class N(val x: Int, val y: Int, val z: Int, val standY: Double) {
        var g = 0.0
        var f = 0.0
        var h = 0.0
        var parent: N? = null
        var kind = PathNode.Kind.WALK
        var aim: BlockPos? = null
        var closed = false
        /** Landed by an instant transmission with nothing underfoot: only teleports (or a fall) lead on from here. */
        var air = false
        /** The teleport hop that led here (length 0 when it was a walk): to keep a flight steady, a turn costs. */
        var hdx = 0.0
        var hdy = 0.0
        var hdz = 0.0
        var hlen = 0.0
    }

    private val CARDINAL = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
    private val DIAGONAL = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
    private const val SQRT2 = 1.41421356
    private const val WALL_COST = 0.45
    private const val CORRIDOR_COST = 1.0
    private const val LAND_WALL_COST = 1.2
    private const val TURN_COST = 4.0
    private const val OFF_LINE_COST = 0.03

    // Look directions tried for teleports, relative to the bearing of the goal.
    private val YAW_OFFSETS = intArrayOf(0, 10, -10, 22, -22, 38, -38, 60, -60, 90, -90, 135, -135, 180)
    private val PITCHES = intArrayOf(-62, -50, -35, -22, -12, -4, 4, 12, 22, 35, 50)
    private val YAW_THOROUGH = IntArray(24) { it * 15 - 180 }
    private val PITCH_THOROUGH = intArrayOf(-75, -60, -45, -30, -15, 0, 15, 30, 45)
    // Etherwarp rays are the dear ones (up to 61 steps through the world each), so the thorough fan for them is thinner.
    private val ETHER_YAW_THOROUGH = IntArray(12) { it * 30 - 180 }
    private val ETHER_PITCH_THOROUGH = intArrayOf(-60, -35, -15, 0, 15, 35)

    const val SNEAK_EYE = 1.27
    const val STAND_EYE = 1.62
    private val TELEPORT_SPREAD = doubleArrayOf(-0.4, 0.4)

    fun direction(yawDeg: Double, pitchDeg: Double): Vec3 {
        val yaw = Math.toRadians(yawDeg); val pitch = Math.toRadians(pitchDeg)
        return Vec3(-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch))
    }

    fun plan(level: Level, start: BlockPos, goal: BlockPos, o: PlanOptions): Route {
        val t0 = System.nanoTime()
        val terrain = Terrain(level)
        terrain.blocked = LongOpenHashSet(o.blocked)
        val g = resolveGoal(terrain, goal, o.maxDrop)

        // Primitive-keyed maps and a plain comparator: boxing a Long / Double per lookup and per heap compare was most of the time.
        val cruiseY = max(start.y, g.y) + o.cruiseAbove
        val nodes = Long2ObjectOpenHashMap<N>(8192)
        val coarse = Long2DoubleOpenHashMap()
        val open = PriorityQueue<N>(4096) { a, b -> java.lang.Double.compare(a.f, b.f) }

        fun h(x: Int, y: Int, z: Int): Double {
            val dx = abs(x - g.x); val dz = abs(z - g.z); val dy = abs(y - g.y)
            var best = max(dx, dz) + (SQRT2 - 1.0) * min(dx, dz) + dy * 0.9
            val dist = sqrt((dx * dx + dz * dz + dy * dy).toDouble())
            if (o.ether) best = min(best, teleportBound(dist, o.etherRange, o.etherCost))
            if (o.instant) best = min(best, teleportBound(dist, o.instantRange, o.instantCost))
            // Flying below the cruise height costs a little on every remaining hop, so being low now means paying it all the way:
            // without this the search cannot see that climbing first is worth it, and keeps the lowest route that gets closest.
            if (o.cruiseAbove > 0 && y < cruiseY) {
                val ground = hypot((x - g.x).toDouble(), (z - g.z).toDouble())
                if (ground > 60.0) best += (cruiseY - y) * 0.1 * (ground / 12.0)
            }
            return best
        }

        val startStand = terrain.stand(start.x, start.y, start.z) ?: 0.0
        val first = N(start.x, start.y, start.z, startStand)
        first.h = h(start.x, start.y, start.z)
        // Weighted a little: leans towards the goal. Much more and it returns the first teleport chain it finds even when walking is
        // cheaper (the frontier of walking nodes looks dearer than it is). Greedier when thorough, which is only used when stuck.
        val wt = if (o.thorough) 1.3 else 1.05
        first.f = first.h * wt
        first.air = o.startInAir
        nodes.put(BlockPos.asLong(start.x, start.y, start.z), first)
        open.add(first)

        var best = first
        var expanded = 0

        // The straight line from the start to the goal (sideways only), for keeping a long flight straight.
        val lineX = (g.x - start.x).toDouble(); val lineZ = (g.z - start.z).toDouble()
        val lineLen2 = lineX * lineX + lineZ * lineZ

        fun relax(from: N, x: Int, y: Int, z: Int, stand: Double, step: Double, kind: PathNode.Kind, aim: BlockPos?) {
            // Cramped places cost extra, so routes keep to the open: beside a wall, much more in a gap one block wide, and a
            // teleport landing in a nook is worse than walking through one (the next hop has to start from there).
            if (kind == PathNode.Kind.WALK && terrain.blocked.contains(BlockPos.asLong(x, y, z))) return
            val tight = terrain.wallSides(x, y, z)
            val crowd = if (kind == PathNode.Kind.WALK) WALL_COST * tight + (if (terrain.corridor(x, y, z)) CORRIDOR_COST else 0.0)
                else LAND_WALL_COST * tight + (if (terrain.corridor(x, y, z)) CORRIDOR_COST * 1.5 else 0.0)
            // A teleport flight is kept steady and high: a turn between hops costs, so does drifting off the straight line to the
            // goal, and so does flying low over the ground (more terrain to be in the way, and more to clip while sinking).
            var hdx = 0.0; var hdy = 0.0; var hdz = 0.0; var hlen = 0.0
            var extra = 0.0
            if (kind != PathNode.Kind.WALK) {
                hdx = (x - from.x).toDouble(); hdy = (y - from.y).toDouble(); hdz = (z - from.z).toDouble()
                hlen = sqrt(hdx * hdx + hdy * hdy + hdz * hdz)
                if (hlen > 0.5) {
                    if (lineLen2 > 900.0) {
                        val t = (((x - start.x) * lineX + (z - start.z) * lineZ) / lineLen2).coerceIn(0.0, 1.0)
                        extra += OFF_LINE_COST * hypot(x - (start.x + lineX * t), z - (start.z + lineZ * t))
                    }
                    if (from.hlen > 0.5) {
                        val cos = (hdx * from.hdx + hdy * from.hdy + hdz * from.hdz) / (hlen * from.hlen)
                        extra += TURN_COST * (1.0 - cos)
                    }
                    // Up high there is less in the way: a long flight is pulled up to its cruise height, until it is near the goal.
                    if (o.cruiseAbove > 0 && kind == PathNode.Kind.INSTANT && y < cruiseY) {
                        val gx = (x - g.x).toDouble(); val gz = (z - g.z).toDouble()
                        if (gx * gx + gz * gz > 3600.0) extra += (cruiseY - y) * 0.1
                    }
                    if (kind == PathNode.Kind.INSTANT && terrain.stand(x, y, z) == null) {
                        var agl = 8
                        for (k in 1..8) if (!terrain.open(x, y - k, z)) { agl = k; break }
                        extra += (8 - agl) * 0.3
                    }
                }
            }
            val newG = from.g + step + crowd + extra
            // Thorough search: one teleport landing per 2-block cell is plenty (the cells next to it are reached the same way),
            // and without this the open air fills with near-duplicate nodes and the time runs out.
            if (o.thorough && kind != PathNode.Kind.WALK) {
                val ck = BlockPos.asLong(x shr 1, y shr 1, z shr 1)
                if (coarse.getOrDefault(ck, Double.MAX_VALUE) <= newG + 0.5) return
                coarse.put(ck, newG)
            }
            val nk = BlockPos.asLong(x, y, z)
            var node = nodes.get(nk)
            if (node == null) { node = N(x, y, z, stand); node.h = h(x, y, z); nodes.put(nk, node) }
            if (node === first || node.closed || (node.parent != null && newG >= node.g)) return
            node.g = newG
            node.f = newG + node.h * wt
            node.parent = from
            node.hdx = hdx; node.hdy = hdy; node.hdz = hdz; node.hlen = hlen
            node.kind = kind
            node.aim = aim
            node.air = kind == PathNode.Kind.INSTANT && terrain.stand(x, y, z) == null
            open.add(node)
        }

        while (open.isNotEmpty() && expanded < o.maxNodes) {
            if ((expanded and 255) == 0 && (System.nanoTime() - t0) / 1_000_000 > o.timeLimitMs) break
            val n = open.poll()
            if (n.closed) continue
            n.closed = true
            expanded++

            if (n.x == g.x && n.y == g.y && n.z == g.z) return finish(terrain, n, true, expanded, t0, o.maxDrop)
            if (n.h < best.h || (n.h == best.h && n.g < best.g)) best = n

            walk(terrain, n, o, ::relax)

            if ((o.ether || o.instant) && !o.avoidsFrom(n.x, n.y, n.z) && (n.air || n.h > 12.0) && (n.air || n === first || ((n.x and 1) == 0 && (n.z and 1) == 0))) {
                // Etherwarp rays are dear (61 steps each), so only some nodes cast them; plain teleports are cheap.
                val dense = n === first || n.air || if (o.thorough) ((n.x and 1) == 0 && (n.z and 1) == 0) else ((n.x and 3) == 0 && (n.z and 3) == 0)
                teleports(level, terrain, n, g, o, dense, ::relax)
            }
        }
        return finish(terrain, best, false, expanded, t0, o.maxDrop)
    }

    private fun teleportBound(dist: Double, range: Double, cost: Double): Double {
        if (range <= 0.0) return Double.MAX_VALUE
        val k = floor(dist / range)
        // k teleports and walk the rest, or one more teleport and walk nothing
        return min(max(0.0, dist - k * range) + k * cost, (k + 1) * cost)
    }

    /** The goal, or the standable block nearest below / above it. */
    private fun resolveGoal(terrain: Terrain, goal: BlockPos, maxDrop: Int): BlockPos {
        if (terrain.stand(goal.x, goal.y, goal.z) != null) return goal
        for (k in 1..maxDrop) if (terrain.stand(goal.x, goal.y - k, goal.z) != null) return BlockPos(goal.x, goal.y - k, goal.z)
        for (k in 1..2) if (terrain.stand(goal.x, goal.y + k, goal.z) != null) return BlockPos(goal.x, goal.y + k, goal.z)
        return goal
    }

    /** For `/cedclient path probe`: what the planner makes of the spot you stand on, and where it would let you walk from it. */
    fun probe(level: Level, at: BlockPos, maxDrop: Int): List<String> {
        val terrain = Terrain(level)
        fun f(v: Double?) = if (v == null) "none" else "%.2f".format(v)
        val self = terrain.stand(at.x, at.y, at.z)
        val out = ArrayList<String>()
        out += "Cell ${at.x} ${at.y} ${at.z}: stand ${f(self)}, feet top ${terrain.top(at.x, at.y, at.z)}, below top ${terrain.top(at.x, at.y - 1, at.z)}"
        out += "feet ${level.getBlockState(at)}  below ${level.getBlockState(at.below())}"
        for ((name, d) in listOf("E" to intArrayOf(1, 0), "W" to intArrayOf(-1, 0), "S" to intArrayOf(0, 1), "N" to intArrayOf(0, -1))) {
            val x = at.x + d[0]; val z = at.z + d[1]
            out += "$name: stand at y ${f(terrain.stand(x, at.y, z))}, y-1 ${f(terrain.stand(x, at.y - 1, z))}, y+1 ${f(terrain.stand(x, at.y + 1, z))}; tops y-1/y/y+1 ${terrain.top(x, at.y - 1, z)}/${terrain.top(x, at.y, z)}/${terrain.top(x, at.y + 1, z)}"
        }
        val moves = ArrayList<String>()
        val o = PlanOptions(0, maxDrop, 0, false, 0.0, 0.0, false, 0.0, 0.0, emptySet())
        walk(terrain, N(at.x, at.y, at.z, self ?: 0.0), o) { _, x, y, z, stand, step, _, _ ->
            moves += "${x - at.x},${y - at.y},${z - at.z} (cost ${"%.1f".format(step)}, stand ${f(stand)})"
        }
        out += "Walk moves (dx,dy,dz): ${if (moves.isEmpty()) "none" else moves.joinToString("  ")}"
        return out
    }

    private fun walk(terrain: Terrain, n: N, o: PlanOptions, relax: (N, Int, Int, Int, Double, Double, PathNode.Kind, BlockPos?) -> Unit) {
        val walkKind = PathNode.Kind.WALK
        if (n.air) {
            // Hanging in the air: all that is left is to fall straight down onto something.
            for (k in 1..o.maxDrop + 6) {
                val land = terrain.stand(n.x, n.y - k, n.z)
                if (land != null) { relax(n, n.x, n.y - k, n.z, land, 1.0 + k * 0.8, walkKind, null); break }
                if (!terrain.open(n.x, n.y - k, n.z)) break
            }
            return
        }
        for (d in CARDINAL) {
            val tx = n.x + d[0]; val tz = n.z + d[1]
            val s = terrain.stand(tx, n.y, tz)
            if (s != null) {
                relax(n, tx, n.y, tz, s, terrain.moveCost(tx, n.y, tz), walkKind, null)
            } else if (terrain.open(tx, n.y, tz) && terrain.open(tx, n.y + 1, tz)) {
                // Open air in front: walk off and drop, landing on the first floor within maxDrop.
                for (k in 1..o.maxDrop) {
                    val yy = n.y - k
                    val land = terrain.stand(tx, yy, tz)
                    if (land != null) {
                        // A half block down (a slab step) is just walking; only a real fall costs extra.
                        val fall = (n.y + n.standY) - (yy + land)
                        relax(n, tx, yy, tz, land, 1.0 + max(0.0, fall - 0.62) * 0.8 + terrain.moveCost(tx, yy, tz) - 1.0, walkKind, null)
                        break
                    }
                    if (!terrain.open(tx, yy, tz)) break
                }
            } else {
                val up = terrain.stand(tx, n.y + 1, tz)
                if (up != null) {
                    val rise = (n.y + 1 + up) - (n.y + n.standY)
                    if (rise <= 0.62) {
                        // Half a block up (off a slab onto the block next to it): walked, no jump.
                        relax(n, tx, n.y + 1, tz, up, 1.2 * terrain.moveCost(tx, n.y + 1, tz), walkKind, null)
                    } else if (terrain.open(n.x, n.y + 2, n.z)) {
                        // A whole block: jump onto it if there is room above. Dear on purpose, so routes stay on the ground
                        // and only climb (onto a wall, a roof) when walking round is much longer.
                        relax(n, tx, n.y + 1, tz, up, o.stepUpCost * terrain.moveCost(tx, n.y + 1, tz), walkKind, null)
                    }
                }
            }
        }

        for (d in DIAGONAL) {
            val dx = d[0]; val dz = d[1]
            // Both side blocks must be clear too, or the body would clip the corner.
            if (!terrain.open(n.x + dx, n.y, n.z) || !terrain.open(n.x + dx, n.y + 1, n.z)) continue
            if (!terrain.open(n.x, n.y, n.z + dz) || !terrain.open(n.x, n.y + 1, n.z + dz)) continue
            val tx = n.x + dx; val tz = n.z + dz
            val s = terrain.stand(tx, n.y, tz)
            if (s != null) {
                relax(n, tx, n.y, tz, s, SQRT2 * terrain.moveCost(tx, n.y, tz), walkKind, null)
            } else if (terrain.open(tx, n.y, tz) && terrain.open(tx, n.y + 1, tz)) {
                // Diagonal drop: without it a descent could only go down in straight steps, a staircase of its own.
                for (k in 1..o.maxDrop) {
                    val yy = n.y - k
                    val land = terrain.stand(tx, yy, tz)
                    if (land != null) {
                        val fall = (n.y + n.standY) - (yy + land)
                        relax(n, tx, yy, tz, land, SQRT2 * terrain.moveCost(tx, yy, tz) + max(0.0, fall - 0.62) * 0.8, walkKind, null)
                        break
                    }
                    if (!terrain.open(tx, yy, tz)) break
                }
            }
        }
    }

    private fun teleports(
        level: Level, terrain: Terrain, n: N, goal: BlockPos, o: PlanOptions, denseEther: Boolean,
        relax: (N, Int, Int, Int, Double, Double, PathNode.Kind, BlockPos?) -> Unit,
    ) {
        val bearing = Math.toDegrees(atan2(-(goal.x - n.x).toDouble(), (goal.z - n.z).toDouble()))
        val seen = LongOpenHashSet()
        // After a landing in open air the player keeps sinking until the next click (about half a block at the usual 4-6 ticks), and
        // the server's path check runs from where they really are, so the hops are planned from there.
        val footY = n.y + n.standY - (if (n.air) 0.5 else 0.0)
        val yawOffsets = if (o.thorough) YAW_THOROUGH else YAW_OFFSETS
        val pitches = if (o.thorough) PITCH_THOROUGH else PITCHES
        val etherYaws = if (o.thorough) ETHER_YAW_THOROUGH else YAW_OFFSETS
        val etherPitches = if (o.thorough) ETHER_PITCH_THOROUGH else PITCHES

        if (o.ether && denseEther) {
            val eye = Vec3(n.x + 0.5, footY + SNEAK_EYE, n.z + 0.5)
            for (yo in etherYaws) for (pitch in etherPitches) {
                val hit = EtherwarpRules.cast(level, eye, direction(bearing + yo, pitch.toDouble()), o.etherRange) ?: continue
                if (!hit.ok) continue
                val k = hit.pos
                val key = k.asLong()
                if (o.avoidFast.contains(key) || !seen.add(key)) continue
                val feetY = EtherwarpRules.feetY(level, k)
                if (k.x == n.x && feetY == n.y && k.z == n.z) continue
                relax(n, k.x, feetY, k.z, 0.0, o.etherCost + abs(feetY - n.y) * 0.2, PathNode.Kind.ETHER, k)
            }
        }

        if (o.instant) {
            val eye = Vec3(n.x + 0.5, footY + STAND_EYE, n.z + 0.5)
            for (yo in yawOffsets) for (pitch in pitches) {
                val land = instantLanding(terrain, eye, direction(bearing + yo, pitch.toDouble()), o.instantRange, o.maxDrop) ?: continue
                val key = BlockPos.asLong(land.first.x, land.first.y, land.first.z)
                if (o.avoidFast.contains(key) || !seen.add(key) || (land.first.x == n.x && land.first.y == n.y && land.first.z == n.z)) continue
                relax(n, land.first.x, land.first.y, land.first.z, land.second, o.instantCost + abs(land.first.y - n.y) * 0.2, PathNode.Kind.INSTANT, null)
            }
        }
    }

    /**
     * Where an instant transmission along [dir] from [eye] ends up: [range] blocks on, or just short of the first
     * thing the body would hit. Null when the body would not fit there.
     */
    fun instantLanding(terrain: Terrain, eye: Vec3, dir: Vec3, range: Double, maxDrop: Int): Pair<BlockPos, Double>? {
        var reach = 0.0
        var d = 0.5
        while (d <= range + 1e-6) {
            val px = eye.x + dir.x * d; val py = eye.y + dir.y * d - STAND_EYE; val pz = eye.z + dir.z * d
            val fx = floor(px).toInt(); val fy = floor(py + 0.01).toInt(); val fz = floor(pz).toInt()
            if (!terrain.open(fx, fy, fz) || !terrain.open(fx, fy + 1, fz)) break
            // The player is rarely at the node centre (up to ~0.3 off), so a hairline ray planned from the centre clips a wall from where
            // they really stand: indoors that was a "blocks in the way" on nearly every hop. Keep a body's width clear of walls instead.
            if (!bodyClear(terrain, px, py, pz)) break
            reach = d
            d += 0.5
        }
        if (reach < 1.0) return null
        val lx = eye.x + dir.x * reach; val ly = eye.y + dir.y * reach - STAND_EYE; val lz = eye.z + dir.z * reach
        // Measured on a real AOTV: the landing is the centre of the block the point falls in, with the feet height
        // rounded (not floored), and it does not need ground: a chain of teleports can cross open air.
        val cx = floor(lx).toInt(); val cy = Math.round(ly).toInt(); val cz = floor(lz).toInt()
        if (!terrain.open(cx, cy, cz) || !terrain.open(cx, cy + 1, cz)) return null
        return BlockPos(cx, cy, cz) to (terrain.stand(cx, cy, cz) ?: 0.0)
    }

    private fun bodyClear(terrain: Terrain, px: Double, py: Double, pz: Double): Boolean {
        val fy = floor(py + 0.01).toInt()
        for (ox in TELEPORT_SPREAD) for (oz in TELEPORT_SPREAD) {
            val cx = floor(px + ox).toInt(); val cz = floor(pz + oz).toInt()
            if (!terrain.open(cx, fy, cz) || !terrain.open(cx, fy + 1, cz)) return false
        }
        return true
    }

    private fun finish(terrain: Terrain, end: N, reached: Boolean, expanded: Int, t0: Long, maxDrop: Int): Route {
        val raw = ArrayList<PathNode>()
        var n: N? = end
        while (n != null) { raw += PathNode(n.x, n.y, n.z, n.standY, n.kind, n.aim); n = n.parent }
        raw.reverse()
        return Route(smooth(terrain, raw, maxDrop), reached, expanded, (System.nanoTime() - t0) / 1_000_000)
    }

    /** String pulling: drop the walk nodes between two that can be joined by a straight, supported, unobstructed line. */
    private fun smooth(terrain: Terrain, nodes: List<PathNode>, maxDrop: Int): List<PathNode> {
        if (nodes.size < 3) return nodes
        val out = ArrayList<PathNode>()
        out += nodes[0]
        var i = 0
        while (i < nodes.size - 1) {
            var next = i + 1
            var j = min(nodes.size - 1, i + 24)
            while (j > i + 1) {
                if (lineClear(terrain, nodes, i, j, maxDrop)) { next = j; break }
                j--
            }
            out += nodes[next]
            i = next
        }
        return out
    }

    private fun lineClear(terrain: Terrain, nodes: List<PathNode>, i: Int, j: Int, maxDrop: Int): Boolean {
        val a = nodes[i]
        // Only from a stop with floor under it: a teleport landing in open air has nothing to walk a line from (it falls).
        if (terrain.stand(a.x, a.y, a.z) == null) return false
        for (k in i + 1..j) if (nodes[k].kind != PathNode.Kind.WALK) return false
        val b = nodes[j]
        val len = hypot((b.x - a.x).toDouble(), (b.z - a.z).toDouble())
        val steps = ceil(len / 0.2).toInt()
        // Walk the straight line, following the ground: each step may rise by half a block (a slab, walked without a
        // jump) or drop a little, and the body has to fit and have floor under it at the level it ends up at.
        // Where the path had room (neither end beside a wall) the shortcut has to leave room too: 0.65 from any wall, not just
        // the 0.3 of the body, or it would cut every inside corner tight.
        val margin = if (terrain.wallSides(a.x, a.y, a.z) == 0 && terrain.wallSides(b.x, b.y, b.z) == 0) 0.65 else 0.3
        var cy = a.y
        var surface = a.y + a.standY
        // Down as far as a drop goes (it was 2): a hillside of 3-block drops never straightened, and the route stayed a staircase.
        // The body leaves a ledge over several samples on a diagonal, still level with the ledge's block, so the highest surface of the
        // last stretch (about a block) is what the corners are judged against, not only the previous sample's. Without it every
        // diagonal drop failed and the route stayed a staircase of axis-aligned runs.
        val recent = DoubleArray(6) { surface }
        var ri = 0
        val dys = IntArray(maxDrop + 2) { if (it == 0) 0 else if (it == 1) 1 else -(it - 1) }
        for (s in 1..steps) {
            val t = s.toDouble() / steps
            val px = a.x + 0.5 + (b.x - a.x) * t
            val pz = a.z + 0.5 + (b.z - a.z) * t
            val mx = floor(px).toInt(); val mz = floor(pz).toInt()
            var found = false
            for (dy in dys) {
                val y = cy + dy
                val st = terrain.stand(mx, y, mz) ?: continue
                if (terrain.blocked.contains(BlockPos.asLong(mx, y, mz))) continue
                val surf = y + st
                if (surf - surface > 0.62 || surf - surface < -maxDrop - 0.1) continue
                // The body is 0.6 wide: every block its corners touch must be open (or low enough to step over).
                var clear = true
                for (ox in doubleArrayOf(-margin, margin)) for (oz in doubleArrayOf(-margin, margin)) {
                    val cx = floor(px + ox).toInt(); val cz = floor(pz + oz).toInt()
                    // Measured from the higher of this and the previous step: going down a staircase the body still overlaps the step it came from.
                    if (!terrain.open(cx, y, cz) && terrain.top(cx, y, cz) > (maxOf(surf, recent.max()) - y).toFloat() + 0.62f) clear = false
                    if (!terrain.open(cx, y + 1, cz)) clear = false
                }
                if (!clear) continue
                cy = y; surface = surf; found = true
                recent[ri] = surf; ri = (ri + 1) % recent.size
                break
            }
            if (!found) return false
        }
        return cy == b.y && abs(surface - (b.y + b.standY)) < 0.05
    }
}
//#endif
