package ced.cedclient.pathfinding

import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.PriorityQueue
import kotlin.math.sqrt

/**
 * Cost of moving INTO a given block column (feet position). Return null if the
 * position is impassable. This is the pluggable part — different features can
 * supply different profiles (e.g. avoid water vs. treat water as normal, avoid
 * lava always, prefer roads, etc.) without touching the search algorithm itself.
 */
fun interface PathCostFunction {
    fun costOf(level: Level, pos: BlockPos): Double?

    /**
     * Whether this column is safe to pass THROUGH while falling — feet+head
     * clear, no lava — even if there's nothing to stand on yet. This is what
     * lets the search "fall" through open air to find a landing spot below a
     * ledge, instead of treating "no floor here" as a dead end.
     *
     * Default is conservative (same as costOf succeeding) so a custom cost
     * function supplied as a plain lambda doesn't suddenly get fall-through
     * behavior it didn't ask for. DefaultTerrainCost overrides this properly.
     */
    fun isOpenAir(level: Level, pos: BlockPos): Boolean = costOf(level, pos) != null
}

/**
 * Default terrain profile: solid ground with 2 blocks of headroom = normal cost,
 * water = surface-aware costs (prefer surface, penalize deep water),
 * anything else (solid block in the way, no floor, lava) = impassable.
 *
 * Grass, kelp, seagrass, and similar "decoration" blocks are treated as passable
 * even if their collision shape looks solid client-side (common on modded/skyblock
 * servers), via the vanilla REPLACEABLE tag plus an explicit override list below.
 */
object DefaultTerrainCost : PathCostFunction {

    // Server-specific or mistagged blocks you know are actually walkable —
    // add to this if a particular reskinned "seaweed"/"grass" block on your
    // server still blocks pathing after the tag check below.
    private val forcedPassable: Set<Block> = setOf(
        Blocks.SEAGRASS,
        Blocks.TALL_SEAGRASS,
        Blocks.KELP,
        Blocks.KELP_PLANT
        // add more Blocks.XYZ here as you find them
    )

    override fun costOf(level: Level, pos: BlockPos): Double? {
        if (!isOpenAir(level, pos)) return null // no room to stand here / lava

        val below = level.getBlockState(pos.below())
        val feetFluid = !level.getFluidState(pos).isEmpty
        val belowSolid = !isPassable(level, below, pos.below())
        val belowFluid = !level.getFluidState(pos.below()).isEmpty

        // Surface-aware water handling
        if (feetFluid) {
            val maxSwimDepth = 6
            var depth = 0
            var y = pos.y
            // scan upward to find a headspace (air) or land within maxSwimDepth
            while (depth <= maxSwimDepth) {
                val candidateFeet = BlockPos(pos.x, y, pos.z)
                val candidateHead = candidateFeet.above()
                val headPassable = isPassable(level, level.getBlockState(candidateHead), candidateHead)
                val feetStillFluid = !level.getFluidState(candidateFeet).isEmpty

                // If head is passable, this is a valid surface or swim position
                if (headPassable) {
                    return when (depth) {
                        0 -> 1.2  // already at surface (headspace) — preferred
                        1, 2 -> 1.4 // shallow swim
                        else -> 1.6 // mid-depth swim
                    }
                }

                // If feet are no longer fluid, we've reached land just above water
                if (!feetStillFluid) {
                    return 1.4
                }

                y++; depth++
            }

            // Too deep — discourage or treat as expensive (avoid wandering in deep pools)
            return 2.5
        }

        return when {
            belowSolid -> 1.0 // normal ground
            belowFluid -> 1.4 // standing right above water surface
            else -> null // nothing underneath — this column alone isn't a landing spot
        }
    }

    override fun isOpenAir(level: Level, pos: BlockPos): Boolean {
        val feet = level.getBlockState(pos)
        val head = level.getBlockState(pos.above())

        if (!isPassable(level, feet, pos) || !isPassable(level, head, pos.above())) return false

        val feetIsLava = level.getFluidState(pos).`is`(FluidTags.LAVA)
        if (feetIsLava) return false

        return true
    }

    private fun isPassable(level: Level, state: BlockState, pos: BlockPos): Boolean {
        if (forcedPassable.contains(state.block)) return true
        if (state.`is`(BlockTags.REPLACEABLE)) return true
        return state.getCollisionShape(level, pos).isEmpty
    }
}

data class PathResult(val waypoints: List<Vec3>, val totalCost: Double)

/**
 * Simple A* over a block grid. Bounded to a box around start/goal so it can't
 * run away searching the whole loaded world. Diagonal moves check both adjacent
 * cardinal cells so it can't cut through a wall corner.
 *
 * This only COMPUTES a route — nothing here moves the player. Callers render the
 * result as a line/waypoint trail and the person walks it themselves.
 */
object Pathfinder {

    private data class Node(val pos: BlockPos, val g: Double, val h: Double, val parent: Node?) {
        val f: Double get() = g + h
    }

    /**
     * Public helper: if a BlockPos is inside a fluid column, try to find a reachable
     * surface cell (feet position) within maxSwimDepth blocks above. Returns the
     * surface feet BlockPos or null if none found.
     */
    fun findSurfaceCell(level: Level, pos: BlockPos, maxSwimDepth: Int = 6): BlockPos? {
        if (level.getFluidState(pos).isEmpty) return null

        var depth = 0
        var y = pos.y
        while (depth <= maxSwimDepth) {
            val candidateFeet = BlockPos(pos.x, y, pos.z)
            val candidateHead = candidateFeet.above()
            val headPassable = level.getBlockState(candidateHead).getCollisionShape(level, candidateHead).isEmpty
            val feetStillFluid = !level.getFluidState(candidateFeet).isEmpty

            if (headPassable) return candidateFeet
            if (!feetStillFluid) return candidateFeet // reached land just above water
            y++; depth++
        }
        return null
    }

    fun findPath(
        level: Level,
        start: Vec3,
        goal: Vec3,
        costFn: PathCostFunction = DefaultTerrainCost,
        maxIterations: Int = 400000000,
        maxSearchRadius: Double = 1024.0,
        // 20 blocks is just a sane upper cap on how far a single fall is allowed
        // to search downward — fall damage isn't a concern here so this isn't a
        // safety limit, just a bound so the scan doesn't run forever.
        maxFallDistance: Int = 20
    ): PathResult? {
        val rawStartPos = BlockPos(
            kotlin.math.floor(start.x).toInt(),
            kotlin.math.floor(start.y).toInt(),
            kotlin.math.floor(start.z).toInt()
        )
        val rawGoalPos = BlockPos(
            kotlin.math.floor(goal.x).toInt(),
            kotlin.math.floor(goal.y).toInt(),
            kotlin.math.floor(goal.z).toInt()
        )

        // If the goal is in water, try to snap it to a reachable surface cell first.
        val goalSurface = findSurfaceCell(level, rawGoalPos, maxFallDistance)
        val rawGoalToResolve = goalSurface ?: rawGoalPos

        val startPos = resolvePassable(level, rawStartPos, costFn) ?: return null
        val goalPos = resolvePassable(level, rawGoalToResolve, costFn) ?: return null

        if (startPos == goalPos) return PathResult(listOf(start, goal), 0.0)

        val open = PriorityQueue<Node>(compareBy { it.f })
        val bestG = HashMap<BlockPos, Double>()
        val startNode = Node(startPos, 0.0, heuristic(startPos, goalPos), null)
        open.add(startNode)
        bestG[startPos] = 0.0

        var iterations = 0
        var finalNode: Node? = null

        while (open.isNotEmpty() && iterations < maxIterations) {
            iterations++
            val current = open.poll()

            if (current.pos == goalPos) {
                finalNode = current
                break
            }

            // Bail out of branches that have drifted far from BOTH the start and
            // the goal — using 3D distance (not just horizontal) so a search
            // wandering vertically through a deep/wide water body gets pruned
            // too, not just horizontal drift on dry land.
            if (distance3D(current.pos, startPos) > maxSearchRadius &&
                distance3D(current.pos, goalPos) > maxSearchRadius
            ) continue

            for (step in neighbors(current.pos, level, costFn, maxFallDistance)) {
                val neighborPos = step.pos
                val baseMoveCost = costFn.costOf(level, neighborPos) ?: continue

                // Diagonal moves need both adjacent cardinal cells open, or you can
                // cut through a wall corner — classic A* grid gotcha. Only applies
                // at the same/adjacent level; falls are handled by neighbors() itself.
                val dx = neighborPos.x - current.pos.x
                val dz = neighborPos.z - current.pos.z
                if (dx != 0 && dz != 0) {
                    val cardinal1 = BlockPos(current.pos.x + dx, current.pos.y, current.pos.z)
                    val cardinal2 = BlockPos(current.pos.x, current.pos.y, current.pos.z + dz)
                    if (costFn.costOf(level, cardinal1) == null || costFn.costOf(level, cardinal2) == null) continue
                }

                val horizontalDist = if (dx != 0 && dz != 0) 1.414 else 1.0

                // Penalize lateral same-depth water moves to avoid wandering at depth.
                var moveCost = baseMoveCost
                val currentFeetFluid = !level.getFluidState(current.pos).isEmpty
                val neighborFeetFluid = !level.getFluidState(neighborPos).isEmpty
                if (currentFeetFluid && neighborFeetFluid && neighborPos.y == current.pos.y) {
                    moveCost += 0.25 // lateral water penalty
                }

                // Fall damage isn't a concern here, so falls are as cheap as
                // walking — no penalty for dropping further.
                val tentativeG = current.g + moveCost * horizontalDist

                if (tentativeG < (bestG[neighborPos] ?: Double.MAX_VALUE)) {
                    bestG[neighborPos] = tentativeG
                    open.add(Node(neighborPos, tentativeG, heuristic(neighborPos, goalPos), current))
                }
            }
        }

        val result = finalNode ?: return null

        val waypoints = mutableListOf<Vec3>()
        var node: Node? = result
        while (node != null) {
            waypoints.add(0, Vec3(node.pos.x + 0.5, node.pos.y.toDouble(), node.pos.z + 0.5))
            node = node.parent
        }

        return PathResult(waypoints, result.g)
    }

    private data class NeighborStep(val pos: BlockPos)

    /**
     * For each of the 8 horizontal directions, generates same-level, step-up, AND
     * step-down candidates INDEPENDENTLY (not as an exclusive fallback chain).
     *
     * This matters most in water: costOf(sameLevel) succeeds at almost any depth,
     * since any water column is a valid position. If step-down were only tried
     * after same-level failed (the old land-only logic), the search could never
     * move downward through a water body at all — it would just wander sideways
     * forever at whatever depth it started, never reaching something a few blocks
     * below. Checking all three independently lets the search chain multiple
     * single-block drops to actually descend through water or off ledges.
     *
     * Only when NONE of same/up/down-by-1 succeed does it fall back to scanning
     * further down through open air (the land "edge with nothing below" case).
     *
     * This version also adds prioritized vertical swim-up candidates when the
     * current position is in fluid, so surfacing is an explicit, cheap option.
     */
    private fun neighbors(
        pos: BlockPos,
        level: Level,
        costFn: PathCostFunction,
        maxFallDistance: Int
    ): List<NeighborStep> {
        val result = mutableListOf<NeighborStep>()

        val currentIsFluid = !level.getFluidState(pos).isEmpty
        val maxSwimUp = 2

        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue

                val sameLevel = BlockPos(pos.x + dx, pos.y, pos.z + dz)
                val stepUp = BlockPos(pos.x + dx, pos.y + 1, pos.z + dz)
                val stepDown = BlockPos(pos.x + dx, pos.y - 1, pos.z + dz)

                var foundDirect = false
                if (costFn.costOf(level, sameLevel) != null) { result.add(NeighborStep(sameLevel)); foundDirect = true }
                if (costFn.costOf(level, stepUp) != null) { result.add(NeighborStep(stepUp)); foundDirect = true }
                if (costFn.costOf(level, stepDown) != null) { result.add(NeighborStep(stepDown)); foundDirect = true }

                // If current is in fluid, add prioritized swim-up candidates (vertical)
                if (currentIsFluid) {
                    for (up in 1..maxSwimUp) {
                        val swimUp = BlockPos(pos.x + dx, pos.y + up, pos.z + dz)
                        if (costFn.costOf(level, swimUp) != null) {
                            result.add(NeighborStep(swimUp))
                        } else {
                            // if blocked, stop trying higher in this column
                            break
                        }
                    }
                }

                if (!foundDirect) {
                    // Land case: an edge with nothing to stand on within 1 block
                    // either way. Scan further down through open air for a landing.
                    // dy=-1 was already ruled out above, so start at -2.
                    var dy = -2
                    while (dy >= -maxFallDistance) {
                        val candidate = BlockPos(pos.x + dx, pos.y + dy, pos.z + dz)
                        if (costFn.costOf(level, candidate) != null) {
                            result.add(NeighborStep(candidate))
                            break
                        }
                        if (!costFn.isOpenAir(level, candidate)) break // hit a wall/ceiling below, can't fall through
                        dy--
                    }
                }
            }
        }

        return result
    }

    private fun heuristic(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** 3D distance — used for pruning, so vertical wandering (e.g. inside a deep
     *  water body) gets bounded the same way horizontal wandering does. */
    private fun distance3D(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * findStandablePoint (in AxolotlStandPoint) validates goals with a bounding-box
     * check and fractional Y search, which can disagree with this cost function's
     * stricter block-grid check once floored. Rather than failing the whole search
     * over a 1-block mismatch, snap to the nearest cell (small vertical-first scan,
     * since that's the usual source of disagreement — water surfaces, slabs, etc.)
     * that this cost function actually accepts.
     */
    private fun resolvePassable(level: Level, pos: BlockPos, costFn: PathCostFunction): BlockPos? {
        if (costFn.costOf(level, pos) != null) return pos

        // try straight up/down first (most common mismatch: fractional water-surface Y)
        for (dy in -3..3) {
            if (dy == 0) continue
            val candidate = BlockPos(pos.x, pos.y + dy, pos.z)
            if (costFn.costOf(level, candidate) != null) return candidate
        }

        // then a small horizontal ring at the same Y (and one above/below it)
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                for (dy in -1..1) {
                    val candidate = BlockPos(pos.x + dx, pos.y + dy, pos.z + dz)
                    if (costFn.costOf(level, candidate) != null) return candidate
                }
            }
        }

        return null
    }
}
