package ced.cedclient.render.cosmetic

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.CrossCollisionBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.WallSide

/** Render-time entry points called by the mixins (chunk compile threads). Allocation-free when nothing is active. */
object CosmeticHooks {

    fun interface Getter {
        fun get(x: Int, y: Int, z: Int): BlockState?
    }

    private val updating = ThreadLocal.withInitial { false }
    private val HORIZONTALS = arrayOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)

    @JvmStatic
    fun hasActive(): Boolean = CosmeticStore.hasEntries

    /** The replacement for the block at x,y,z, or null. Phantom entries (also apply to air) are only used when [originalAir]. */
    @JvmStatic
    fun replacementAt(x: Int, y: Int, z: Int, originalAir: Boolean): BlockState? {
        if (!CosmeticStore.hasEntries) return null
        val key = BlockPos.asLong(x, y, z)
        val state = CosmeticStore.cache[key] ?: return null
        if (originalAir && !CosmeticStore.phantom.contains(key)) return null
        return state
    }

    @JvmStatic
    fun hasAdjacent(x: Int, y: Int, z: Int): Boolean {
        val c = CosmeticStore.cache
        return c.containsKey(BlockPos.asLong(x - 1, y, z)) || c.containsKey(BlockPos.asLong(x + 1, y, z)) ||
                c.containsKey(BlockPos.asLong(x, y, z - 1)) || c.containsKey(BlockPos.asLong(x, y, z + 1))
    }

    /** Re-derives fence / pane / bar / wall connections from the (possibly replaced) neighbours. */
    @JvmStatic
    fun updateConnections(state: BlockState, x: Int, y: Int, z: Int, getter: Getter?): BlockState {
        val block = state.block
        if (block !is CrossCollisionBlock && block !is WallBlock) return state
        if (updating.get()) return state

        updating.set(true)
        try {
            var result = state
            if (block !is WallBlock) {
                for (dir in HORIZONTALS) {
                    val prop = block.stateDefinition.getProperty(dir.serializedName) as? BooleanProperty ?: continue
                    val n = getter?.get(x + dir.stepX, y, z + dir.stepZ)
                    val connects = n != null && !n.isAir &&
                            (n.block is CrossCollisionBlock || n.block is FenceGateBlock || n.canOcclude() || n.block is WallBlock)
                    result = result.setValue(prop, connects)
                }
            } else {
                for (dir in HORIZONTALS) {
                    val prop = WallBlock.PROPERTY_BY_DIRECTION[dir] ?: continue
                    val n = getter?.get(x + dir.stepX, y, z + dir.stepZ)
                    val connects = n != null && !n.isAir &&
                            (n.block is WallBlock || n.block is CrossCollisionBlock || n.block is FenceGateBlock || n.canOcclude())
                    result = result.setValue(prop, if (connects) WallSide.LOW else WallSide.NONE)
                }
                result = result.setValue(WallBlock.UP, true)
            }
            return result
        } catch (t: Throwable) {
            return state
        } finally {
            updating.set(false)
        }
    }
}