//#if CHEAT
package ced.cedclient.pathing

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BannerBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.BubbleColumnBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.ComparatorBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.NetherWartBlock
import net.minecraft.world.level.block.PressurePlateBlock
import net.minecraft.world.level.block.RedstoneTorchBlock
import net.minecraft.world.level.block.RepeaterBlock
import net.minecraft.world.level.block.SignBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.TripWireHookBlock
import net.minecraft.world.level.block.WallBannerBlock
import net.minecraft.world.level.block.WallSkullBlock
import net.minecraft.world.level.block.WeightedPressurePlateBlock
import net.minecraft.world.level.block.piston.PistonHeadBlock
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Which block an etherwarp lands on, and whether the warp works: a block-grid walk like the server's, with the
 * block lists taken from NoammAddons' EtherwarpHelper (CC0). Shared by the Etherwarp overlay and the pathfinder.
 *
 * [eye] is the eye position of a sneaking player (feet + 1.27).
 */
object EtherwarpRules {
    class Hit(val pos: BlockPos, val ok: Boolean, val distance: Double)

    /** Feet block of an etherwarp onto [target]: on top of it, a whole block up (a slab's top is lower than a full block's). */
    fun feetY(level: Level, target: BlockPos): Int {
        val top = level.getBlockState(target).getCollisionShape(level, target, CollisionContext.empty()).max(Direction.Axis.Y)
        return target.y + max(1, ceil(top).toInt())
    }

    /** The first block along [dir] from [eye] that stops the ray, with whether landing on it works; null if none within [range]. */
    fun cast(level: Level, eye: Vec3, dir: Vec3, range: Double): Hit? {
        val to = eye.add(dir.scale(range))
        var x = floor(eye.x).toInt(); var y = floor(eye.y).toInt(); var z = floor(eye.z).toInt()
        val ex = floor(to.x).toInt(); val ey = floor(to.y).toInt(); val ez = floor(to.z).toInt()
        val dx = to.x - eye.x; val dy = to.y - eye.y; val dz = to.z - eye.z
        val sx = Math.signum(dx).toInt(); val sy = Math.signum(dy).toInt(); val sz = Math.signum(dz).toInt()
        val tdx = if (dx == 0.0) Double.MAX_VALUE else abs(1.0 / dx)
        val tdy = if (dy == 0.0) Double.MAX_VALUE else abs(1.0 / dy)
        val tdz = if (dz == 0.0) Double.MAX_VALUE else abs(1.0 / dz)
        var tx = if (dx == 0.0) Double.MAX_VALUE else (if (sx > 0) x + 1 - eye.x else eye.x - x) * tdx
        var ty = if (dy == 0.0) Double.MAX_VALUE else (if (sy > 0) y + 1 - eye.y else eye.y - y) * tdy
        var tz = if (dz == 0.0) Double.MAX_VALUE else (if (sz > 0) z + 1 - eye.z else eye.z - z) * tdz
        val p = BlockPos.MutableBlockPos()
        repeat(abs(ex - x) + abs(ey - y) + abs(ez - z) + 1) {
            p.set(x, y, z)
            if (!level.hasChunkAt(p)) return null
            if (validLanding(level, p)) return Hit(p.immutable(), true, eye.distanceTo(Vec3.atCenterOf(p)))
            if (!passable(level, p)) return Hit(p.immutable(), false, eye.distanceTo(Vec3.atCenterOf(p)))
            if (x == ex && y == ey && z == ez) return if (level.getBlockState(p).isAir) null else Hit(p.immutable(), false, range)
            if (tx <= ty && tx <= tz) { tx += tdx; x += sx } else if (ty <= tz) { ty += tdy; y += sy } else { tz += tdz; z += sz }
        }
        return null
    }

    // Blocks the ray stops at / passes through (anything else: passable when it has no collision).
    private fun passable(level: Level, p: BlockPos): Boolean {
        val state = level.getBlockState(p)
        return when (state.block) {
            is SignBlock, is BannerBlock, is WallBannerBlock, is TripWireHookBlock, is PressurePlateBlock, is WeightedPressurePlateBlock -> false
            is ButtonBlock, is SkullBlock, is WallSkullBlock, is LadderBlock, is BubbleColumnBlock, is FlowerPotBlock, is PistonHeadBlock,
            is LeverBlock, is NetherWartBlock, is ComparatorBlock, is RedstoneTorchBlock, is RepeaterBlock -> true
            else -> state.getCollisionShape(level, p, CollisionContext.empty()).isEmpty
        }
    }

    private fun noRealHitbox(b: Block) =
        b is SignBlock || b is BannerBlock || b is WallBannerBlock || b is TripWireHookBlock || b is PressurePlateBlock || b is WeightedPressurePlateBlock

    private fun blocksFeet(b: Block) =
        b is SkullBlock || b is WallSkullBlock || b is ComparatorBlock || b is RepeaterBlock || b is FlowerPotBlock || b is LadderBlock

    private fun blocked(level: Level, p: BlockPos): Boolean {
        val b = level.getBlockState(p).block
        return (!noRealHitbox(b) && !passable(level, p)) || blocksFeet(b)
    }

    /** Solid target with room to stand above its top. */
    fun validLanding(level: Level, p: BlockPos): Boolean {
        if (passable(level, p)) return false
        val feetY = feetY(level, p)
        return !blocked(level, BlockPos(p.x, feetY, p.z)) && !blocked(level, BlockPos(p.x, feetY + 1, p.z))
    }
}
//#endif
