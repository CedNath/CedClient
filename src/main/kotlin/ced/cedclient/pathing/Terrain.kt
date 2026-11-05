//#if CHEAT
package ced.cedclient.pathing

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks

/**
 * What the planner knows about the loaded world, cached per search (one cell is read many times).
 *
 * A path node is the block the player's feet are in. Blocks that are not full cubes are handled through the
 * height of their collision box: a slab, carpet, trapdoor or snow layer in the feet block (up to 0.6 high) is
 * walked on as part of that block, and a stair, top slab, farmland or soul sand is a floor at its own height.
 * Anything unloaded or harmful (lava, fire, cactus, magma, berry bush, cobweb, powder snow) counts as a wall.
 *
 * Not thread safe: make one per search.
 */
class Terrain(private val level: Level) {
    /** Walk cells the walker got stuck at (see PlanOptions.blocked): the planner treats them as walls. */
    var blocked: it.unimi.dsi.fastutil.longs.LongOpenHashSet = it.unimi.dsi.fastutil.longs.LongOpenHashSet()

    class Cell(val loaded: Boolean, val top: Float, val hazard: Boolean, val water: Boolean, val slow: Boolean)

    private val unloaded = Cell(false, 1f, true, false, false)
    private val pos = BlockPos.MutableBlockPos()
    private val cells = Long2ObjectOpenHashMap<Cell>(16384)

    fun cell(x: Int, y: Int, z: Int): Cell {
        val k = BlockPos.asLong(x, y, z)
        var c = cells.get(k)
        if (c == null) { c = read(x, y, z); cells.put(k, c) }
        return c
    }

    private fun read(x: Int, y: Int, z: Int): Cell {
        pos.set(x, y, z)
        if (y < level.minY || y >= level.maxY || !level.hasChunkAt(pos)) return unloaded
        val state = level.getBlockState(pos)
        val shape = state.getCollisionShape(level, pos)
        val top = if (shape.isEmpty) 0f else shape.max(Direction.Axis.Y).toFloat()
        val fluid = state.fluidState
        val hazard = fluid.`is`(FluidTags.LAVA) || state.`is`(BlockTags.FIRE) || state.`is`(Blocks.COBWEB) ||
            state.`is`(Blocks.SWEET_BERRY_BUSH) || state.`is`(Blocks.POWDER_SNOW) || state.`is`(Blocks.WITHER_ROSE) ||
            state.`is`(Blocks.MAGMA_BLOCK) || state.`is`(Blocks.CACTUS) || state.`is`(Blocks.CAMPFIRE) || state.`is`(Blocks.SOUL_CAMPFIRE)
        return Cell(true, top, hazard, fluid.`is`(FluidTags.WATER), state.`is`(Blocks.SOUL_SAND) || state.`is`(Blocks.HONEY_BLOCK))
    }

    /** Nothing in this block to collide with or be hurt by. */
    fun open(x: Int, y: Int, z: Int): Boolean {
        val c = cell(x, y, z)
        return c.loaded && !c.hazard && c.top <= 0f
    }

    /** A wall at this block: too tall to step onto at feet height, or in the way of the head. */
    private fun isWall(x: Int, y: Int, z: Int): Boolean = cell(x, y, z).top > 0.62f || cell(x, y + 1, z).top > 0f

    /** How many of the four sides of this block have a wall. */
    fun wallSides(x: Int, y: Int, z: Int): Int {
        var n = 0
        if (isWall(x + 1, y, z)) n++
        if (isWall(x - 1, y, z)) n++
        if (isWall(x, y, z + 1)) n++
        if (isWall(x, y, z - 1)) n++
        return n
    }

    /** Walls on both sides of one axis: a gap one block wide, the tightest place to be. */
    fun corridor(x: Int, y: Int, z: Int): Boolean =
        (isWall(x + 1, y, z) && isWall(x - 1, y, z)) || (isWall(x, y, z + 1) && isWall(x, y, z - 1))

    /** Collision height of the block, 0 when empty. */
    fun top(x: Int, y: Int, z: Int): Float = cell(x, y, z).top

    /**
     * How far above the bottom of the feet block the player stands there, or null when the block cannot hold the
     * body (no room, harmful, nothing to stand on). Small negative values are floors a little lower than a full block.
     */
    fun stand(x: Int, y: Int, z: Int): Double? {
        val c = cell(x, y, z)
        if (!c.loaded || c.hazard || c.top > 0.6f) return null
        if (!open(x, y + 1, z)) return null
        if (c.top > 0.2f && !open(x, y + 2, z)) return null
        if (c.top > 0f) return c.top.toDouble() // standing on a low block that fills its own cell
        if (c.water) return 0.0
        val below = cell(x, y - 1, z)
        if (!below.loaded || below.hazard) return null
        return if (below.top in 0.85f..1.01f) (below.top - 1f).coerceAtMost(0f).toDouble() else null
    }

    /** Extra cost per block of moving into this cell: wading and slow blocks are worth walking around. */
    fun moveCost(x: Int, y: Int, z: Int): Double {
        val c = cell(x, y, z)
        return if (c.water) 3.0 else if (cell(x, y - 1, z).slow) 2.0 else 1.0
    }
}
//#endif
