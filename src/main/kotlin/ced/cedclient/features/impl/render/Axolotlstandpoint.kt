package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.pathfinding.Pathfinder
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object AxolotlStandPoint : Module(
    "AxolotlStandPoint",
    Category.Render,
    "Shows the optimal spot to stand for max axolotls in range"
) {
    private val mc = Minecraft.getInstance()

    private val rangeBlocks = NumberSetting("Range", 11.0, 4.0, 32.0, 0.5)
    private val nameFilter = NumberSetting("Recompute Interval ticks", 10.0, 1.0, 40.0, 1.0)
    private val localBoxHalfSize = NumberSetting("Cluster Box Half-Size", 10.0, 4.0, 32.0, 1.0)
    private val showBeacon = BooleanSetting("Show Beacon", true)
    private val showHudText = BooleanSetting("Show HUD Text", true)
    private val minClusterSize = NumberSetting("Min Cluster Size", 2.0, 1.0, 10.0, 1.0)
    private val showGuideLine = BooleanSetting("Show Guide Line", true)
    private val usePathfinding = BooleanSetting("Use Pathfinding Guide", true)
    private val lockUntilCoverageDrops = BooleanSetting("Lock Until Coverage Drops", true)

    val showBeaconEnabled: Boolean get() = showBeacon.value
    val showHudTextEnabled: Boolean get() = showHudText.value
    val showGuideLineEnabled: Boolean get() = showGuideLine.value
    val usePathfindingEnabled: Boolean get() = usePathfinding.value

    fun distanceToStandPoint(): Double? {
        val player = mc.player ?: return null
        val point = currentStandPoint ?: return null
        return player.position().distanceTo(point)
    }

    @Volatile
    var currentStandPoint: Vec3? = null
        private set

    @Volatile
    var currentPath: List<Vec3> = emptyList()
        private set

    private var lastPathTarget: Vec3? = null
    private var lastPathComputedTick = 0

    @Volatile
    var coveredCount: Int = 0
        private set

    @Volatile
    var totalCount: Int = 0
        private set

    private var lastCoveredCount = -1
    private var tickCounter = 0

    init {
        listOf(localBoxHalfSize, nameFilter, minClusterSize, usePathfinding, lockUntilCoverageDrops)
            .forEach { it.advanced = true }

        addSettings(
            rangeBlocks, nameFilter, localBoxHalfSize, showBeacon, showHudText,
            minClusterSize, showGuideLine, usePathfinding, lockUntilCoverageDrops
        )


        ClientTickEvents.END_CLIENT_TICK.register {
            if (!isEnabled) return@register

            tickCounter++
            if (tickCounter % 5 != 0) return@register

            recompute()
        }
    }

    private fun recompute() {
        val player = mc.player ?: return
        val playerPos = player.position()
        val half = localBoxHalfSize.value

        val allAxolotls = EntityESP.scannedEntities
            .filter { it.name.contains("Axolotl", ignoreCase = true) }
            .map { it.entity.position() }

        if (allAxolotls.isEmpty()) {
            currentStandPoint = null
            coveredCount = 0
            totalCount = 0
            lastCoveredCount = -1
            currentPath = emptyList()
            lastPathTarget = null
            return
        }

        var bestAnchorIndex = 0
        var bestAnchorCount = -1
        for (i in allAxolotls.indices) {
            val anchor = allAxolotls[i]
            val count = allAxolotls.count { pos ->
                kotlin.math.abs(pos.x - anchor.x) <= half &&
                        kotlin.math.abs(pos.y - anchor.y) <= half &&
                        kotlin.math.abs(pos.z - anchor.z) <= half
            }
            if (count > bestAnchorCount) {
                bestAnchorCount = count
                bestAnchorIndex = i
            }
        }

        val anchor = allAxolotls[bestAnchorIndex]
        val cluster = allAxolotls.filter { pos ->
            kotlin.math.abs(pos.x - anchor.x) <= half &&
                    kotlin.math.abs(pos.y - anchor.y) <= half &&
                    kotlin.math.abs(pos.z - anchor.z) <= half
        }

        totalCount = cluster.size

        if (cluster.size < minClusterSize.value.toInt()) {
            currentStandPoint = null
            coveredCount = 0
            lastCoveredCount = -1
            currentPath = emptyList()
            lastPathTarget = null
            return
        }

        if (lockUntilCoverageDrops.value && currentStandPoint != null) {
            val liveCoverage = cluster.count { it.distanceTo(playerPos) <= rangeBlocks.value }
            if (liveCoverage >= lastCoveredCount && lastCoveredCount != -1) {
                coveredCount = liveCoverage
                updatePath(playerPos, currentStandPoint!!)
                return
            }
        }

        val best = findBestStandPoint(cluster, rangeBlocks.value)
        currentStandPoint = best.point
        coveredCount = best.coveredCount
        lastCoveredCount = best.coveredCount

        updatePath(player.position(), best.point)
    }

    private fun updatePath(from: Vec3, target: Vec3) {
        if (!usePathfinding.value) {
            currentPath = emptyList()
            return
        }

        val targetMoved = lastPathTarget?.let { it.distanceTo(target) > 1.0 } ?: true
        val staleEnough = tickCounter - lastPathComputedTick > 40
        if (!targetMoved && !staleEnough) return

        val level = mc.level ?: return
        val straightLineDist = from.distanceTo(target)
        val radius = (straightLineDist * 1.5 + 16).coerceAtLeast(64.0)
        val iterations = (straightLineDist * 60).toInt().coerceAtLeast(4000).coerceAtMost(20000)

        // If the target is in water, try to snap it to a reachable surface cell first
        val rawTargetPos = net.minecraft.core.BlockPos(
            kotlin.math.floor(target.x).toInt(),
            kotlin.math.floor(target.y).toInt(),
            kotlin.math.floor(target.z).toInt()
        )
        val snapped = Pathfinder.findSurfaceCell(level, rawTargetPos, 6)
        val finalTarget = snapped?.let { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) } ?: target

        val result = ced.cedclient.pathfinding.Pathfinder.findPath(
            level, from, finalTarget,
            maxIterations = iterations,
            maxSearchRadius = radius
        )
        currentPath = result?.waypoints ?: emptyList()
        lastPathTarget = finalTarget
        lastPathComputedTick = tickCounter
    }

    private data class Candidate(val point: Vec3, val coveredCount: Int)

    private fun findStandablePoint(point: Vec3): Vec3? {
        val level = mc.level ?: return point
        val player = mc.player ?: return point

        fun standableAt(y: Double): Boolean {
            val box = AABB(
                point.x - 0.3, y, point.z - 0.3,
                point.x + 0.3, y + 1.8, point.z + 0.3
            )
            if (!level.noCollision(player, box)) return false

            val feetPos = net.minecraft.core.BlockPos(
                kotlin.math.floor(point.x).toInt(),
                kotlin.math.floor(y).toInt(),
                kotlin.math.floor(point.z).toInt()
            )
            val belowPos = feetPos.below()

            val inWater = !level.getFluidState(feetPos).isEmpty
            val solidBelow = !level.getBlockState(belowPos).getCollisionShape(level, belowPos).isEmpty

            return inWater || solidBelow
        }

        // If the raw point is in water, try to snap to the surface using Pathfinder helper
        val rawFeet = net.minecraft.core.BlockPos(
            kotlin.math.floor(point.x).toInt(),
            kotlin.math.floor(point.y).toInt(),
            kotlin.math.floor(point.z).toInt()
        )
        if (!level.getFluidState(rawFeet).isEmpty) {
            val surface = Pathfinder.findSurfaceCell(level, rawFeet, 6)
            if (surface != null) return Vec3(surface.x + 0.5, surface.y.toDouble(), surface.z + 0.5)
        }

        if (standableAt(point.y)) return point

        var offset = 0.5
        while (offset <= 3.0) {
            if (standableAt(point.y + offset)) return Vec3(point.x, point.y + offset, point.z)
            if (standableAt(point.y - offset)) return Vec3(point.x, point.y - offset, point.z)
            offset += 0.5
        }

        return null
    }
// Smoothing helpers for rendering a flowing guide line

    private fun douglasPeucker(points: List<Vec3>, epsilon: Double): List<Vec3> {
        if (points.size < 3) return points

        fun perpendicularDistance(a: Vec3, b: Vec3, p: Vec3): Double {
            val abx = b.x - a.x
            val aby = b.y - a.y
            val abz = b.z - a.z
            val apx = p.x - a.x
            val apy = p.y - a.y
            val apz = p.z - a.z
            val abLen2 = abx * abx + aby * aby + abz * abz
            if (abLen2 == 0.0) return Math.sqrt(apx * apx + apy * apy + apz * apz)
            val t = (apx * abx + apy * aby + apz * abz) / abLen2
            val projx = a.x + t * abx
            val projy = a.y + t * aby
            val projz = a.z + t * abz
            val dx = p.x - projx
            val dy = p.y - projy
            val dz = p.z - projz
            return Math.sqrt(dx * dx + dy * dy + dz * dz)
        }

        fun recurse(pts: List<Vec3>, eps: Double): List<Vec3> {
            if (pts.size < 3) return pts
            var maxDist = 0.0
            var index = -1
            val a = pts.first()
            val b = pts.last()
            for (i in 1 until pts.size - 1) {
                val d = perpendicularDistance(a, b, pts[i])
                if (d > maxDist) { maxDist = d; index = i }
            }
            if (maxDist > eps) {
                val left = recurse(pts.subList(0, index + 1), eps)
                val right = recurse(pts.subList(index, pts.size), eps)
                return left.dropLast(1) + right
            } else {
                return listOf(a, b)
            }
        }

        return recurse(points, epsilon)
    }

    private fun smoothPathCatmullRom(points: List<Vec3>, samplesPerSegment: Int = 6): List<Vec3> {
        if (points.size < 2) return points
        val out = mutableListOf<Vec3>()
        // extend endpoints to make endpoints interpolate nicely
        val extended = mutableListOf<Vec3>()
        extended.add(points.first()) // P0 duplicate
        extended.addAll(points)
        extended.add(points.last()) // Pn duplicate

        val n = extended.size
        for (i in 0 until n - 3) {
            val p0 = extended[i]
            val p1 = extended[i + 1]
            val p2 = extended[i + 2]
            val p3 = extended[i + 3]

            for (s in 0 until samplesPerSegment) {
                val t = s.toDouble() / samplesPerSegment.toDouble()
                val t2 = t * t
                val t3 = t2 * t

                // Catmull-Rom basis
                val x = 0.5 * ((2.0 * p1.x) +
                        (-p0.x + p2.x) * t +
                        (2.0 * p0.x - 5.0 * p1.x + 4.0 * p2.x - p3.x) * t2 +
                        (-p0.x + 3.0 * p1.x - 3.0 * p2.x + p3.x) * t3)
                val y = 0.5 * ((2.0 * p1.y) +
                        (-p0.y + p2.y) * t +
                        (2.0 * p0.y - 5.0 * p1.y + 4.0 * p2.y - p3.y) * t2 +
                        (-p0.y + 3.0 * p1.y - 3.0 * p2.y + p3.y) * t3)
                val z = 0.5 * ((2.0 * p1.z) +
                        (-p0.z + p2.z) * t +
                        (2.0 * p0.z - 5.0 * p1.z + 4.0 * p2.z - p3.z) * t2 +
                        (-p0.z + 3.0 * p1.z - 3.0 * p2.z + p3.z) * t3)

                out.add(Vec3(x, y, z))
            }
        }
        // ensure final point is included
        out.add(points.last())
        return out
    }

    private fun findBestStandPoint(points: List<Vec3>, range: Double): Candidate {
        if (points.size == 1) return Candidate(points[0], 1)

        val candidates = mutableListOf<Vec3>()
        candidates.addAll(points)

        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                candidates.add(points[i].add(points[j]).scale(0.5))
            }
        }

        var cx = 0.0; var cy = 0.0; var cz = 0.0
        for (p in points) { cx += p.x; cy += p.y; cz += p.z }
        candidates.add(Vec3(cx / points.size, cy / points.size, cz / points.size))

        var bestPoint = candidates[0]
        var bestCount = -1
        var bestMaxDist = Double.MAX_VALUE

        for (raw in candidates) {
            val c = findStandablePoint(raw) ?: continue

            val covered = points.filter { it.distanceTo(c) <= range }
            val count = covered.size
            val maxDist = covered.maxOfOrNull { it.distanceTo(c) } ?: 0.0

            if (count > bestCount || (count == bestCount && maxDist < bestMaxDist)) {
                bestCount = count
                bestPoint = c
                bestMaxDist = maxDist
            }
        }

        if (bestCount == -1) {
            return Candidate(points[0], points.count { it.distanceTo(points[0]) <= range })
        }

        return Candidate(bestPoint, bestCount)
    }

    private fun Vec3.distanceTo(other: Vec3): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
