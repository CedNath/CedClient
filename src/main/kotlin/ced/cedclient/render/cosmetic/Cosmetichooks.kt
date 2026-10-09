package ced.cedclient.render.cosmetic

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CrossCollisionBlock
import net.minecraft.world.level.block.EndPortalBlock
import net.minecraft.world.level.block.EndPortalFrameBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.WallSide
import net.minecraft.world.level.material.Fluids

/** Render-time entry points called by the mixins (chunk compile threads). Allocation-free when nothing is active. */
object CosmeticHooks {

    fun interface Getter {
        fun get(x: Int, y: Int, z: Int): BlockState?
    }

    private val updating = ThreadLocal.withInitial { false }
    private val HORIZONTALS = arrayOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)

    /** Is anything at all going on (per-position entries, remaps, lava swap)? Cheap early-out for the mixins. */
    @JvmStatic
    fun anyActive(): Boolean =
        CosmeticConfig.enabled && (CosmeticStore.hasEntries || CosmeticRemap.hasRemaps() || CosmeticConfig.lavaAsWater)

    @JvmStatic
    fun hasActive(): Boolean = CosmeticConfig.enabled && CosmeticStore.hasEntries && CosmeticConfig.gateOpen()

    /** The per-position replacement at x,y,z, or null. Phantom entries (also apply to air) are only used when [originalAir]. */
    @JvmStatic
    fun replacementAt(x: Int, y: Int, z: Int, originalAir: Boolean): BlockState? {
        if (!hasActive()) return null
        val key = BlockPos.asLong(x, y, z)
        val state = CosmeticStore.cache[key] ?: return null
        if (originalAir && !CosmeticStore.phantom.contains(key)) return null
        return state
    }

    /** Per-position replacement first, then a global remap of the original block. */
    @JvmStatic
    fun replacementOrRemap(original: BlockState?, x: Int, y: Int, z: Int, originalAir: Boolean): BlockState? {
        replacementAt(x, y, z, originalAir)?.let { return it }
        if (original == null || original.isAir) return null
        return CosmeticRemap.remapFor(original.block, x, y, z)
    }

    /** Break particles / sounds / step sounds use what the block looks like, not what it is. */
    @JvmStatic
    fun effectReplacement(pos: BlockPos, original: BlockState): BlockState? {
        if (!CosmeticConfig.enabled) return null
        replacementAt(pos.x, pos.y, pos.z, false)?.let { return CosmeticStore.retexture[pos.asLong()] ?: it }
        if (original.isAir) return null
        return CosmeticRemap.remapFor(original.block, pos.x, pos.y, pos.z)
    }

    @JvmStatic
    fun retextureAt(x: Int, y: Int, z: Int): BlockState? {
        if (!hasActive()) return null
        val key = BlockPos.asLong(x, y, z)
        if (!CosmeticStore.cache.containsKey(key)) return null
        // explicit |shape= textures always apply; derived ones only with Keep Block Size
        if (!CosmeticStore.shaped.contains(key) && !CosmeticConfig.keepSize) return null
        return CosmeticStore.retexture[key]
    }

    @JvmStatic
    fun originalShapeAt(x: Int, y: Int, z: Int): BlockState? {
        if (!hasActive() || !CosmeticConfig.keepSize) return null
        val key = BlockPos.asLong(x, y, z)
        if (!CosmeticStore.cache.containsKey(key)) return null
        return CosmeticStore.originalShape[key]
    }

    @JvmStatic
    fun needsModelWrapper(): Boolean = hasActive() && (CosmeticConfig.keepSize || CosmeticStore.shaped.isNotEmpty())

    @JvmStatic
    fun hasAdjacent(x: Int, y: Int, z: Int): Boolean {
        val c = CosmeticStore.cache
        return c.containsKey(BlockPos.asLong(x - 1, y, z)) || c.containsKey(BlockPos.asLong(x + 1, y, z)) ||
                c.containsKey(BlockPos.asLong(x, y, z - 1)) || c.containsKey(BlockPos.asLong(x, y, z + 1))
    }

    /** Lava as Water: lava renders as water of the same level. */
    @JvmStatic
    fun swapFluid(original: BlockState?): BlockState? {
        if (original == null || !CosmeticConfig.enabled || !CosmeticConfig.lavaAsWater || !CosmeticConfig.gateOpen()) return null
        if (original.block !is LiquidBlock) return null
        val fluid = original.fluidState
        if (!fluid.`is`(Fluids.LAVA) && !fluid.`is`(Fluids.FLOWING_LAVA)) return null
        return Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, original.getValue(LiquidBlock.LEVEL))
    }

    @JvmStatic
    fun customWater(): Boolean = CosmeticConfig.enabled && CosmeticConfig.customWater

    @JvmStatic
    fun waterRgb(): Int = CosmeticConfig.waterRgb and 0xFFFFFF

    // ------------------------------------------------------------------
    // Keep Block Size (cb's applyOriginalShape)
    // ------------------------------------------------------------------

    private val baseToStair: Map<String, String> by lazy { buildVariants("_stairs") { it is StairBlock } }
    private val baseToSlab: Map<String, String> by lazy { buildVariants("_slab") { it is SlabBlock } }

    private fun buildVariants(suffix: String, test: (net.minecraft.world.level.block.Block) -> Boolean): Map<String, String> {
        val map = HashMap<String, String>()
        for (key in BuiltInRegistries.BLOCK.keySet()) {
            val block = BuiltInRegistries.BLOCK.getOptional(key).orElse(null) ?: continue
            if (!test(block) || !key.path.endsWith(suffix)) continue
            val base = resolveBase(key.namespace, key.path.removeSuffix(suffix)) ?: continue
            map[base] = "${key.namespace}:${key.path}"
        }
        return map
    }

    private fun resolveBase(ns: String, name: String): String? {
        for (c in listOf("$ns:$name", "$ns:${name}s", "$ns:${name}_planks", "$ns:${name}_block")) {
            if (BuiltInRegistries.BLOCK.getOptional(Identifier.parse(c)).isPresent) return c
        }
        return null
    }

    /**
     * Replacing a stair / slab / fence / wall / portal with a different kind of block: keep the original geometry.
     *  - a matching variant exists (stone -> stone_stairs): use it with the original's facing/half
     *  - the replacement is a full block: draw the original model with the replacement's texture (retexture)
     *  - the replacement is see-through (glass...): draw the replacement's texture on the original geometry
     */
    @JvmStatic
    fun applyOriginalShape(original: BlockState?, replacement: BlockState, x: Int, y: Int, z: Int): BlockState {
        if (!CosmeticConfig.keepSize && !CosmeticConfig.remapKeepShape) return replacement
        if (original == null || original.isAir) return replacement
        val key = BlockPos.asLong(x, y, z)
        if (CosmeticStore.shaped.contains(key)) return replacement

        val ob = original.block
        val rb = replacement.block
        val rid = BuiltInRegistries.BLOCK.getKey(rb).toString()

        fun plain(state: BlockState): BlockState {
            CosmeticStore.retexture.remove(key)
            CosmeticStore.originalShape.remove(key)
            return state
        }

        fun keep(): BlockState {
            return if (!replacement.canOcclude()) {
                CosmeticStore.retexture.remove(key)
                CosmeticStore.originalShape[key] = original
                replacement
            } else {
                CosmeticStore.originalShape.remove(key)
                CosmeticStore.retexture[key] = replacement
                original
            }
        }

        return when {
            ob is StairBlock -> when {
                rb is StairBlock -> plain(CosmeticProps.copyMatching(original, replacement))
                baseToStair[rid] != null -> CosmeticCodec.resolve(baseToStair[rid]!!)?.let { plain(CosmeticProps.copyMatching(original, it)) } ?: keep()
                else -> keep()
            }
            ob is SlabBlock -> when {
                rb is SlabBlock -> plain(CosmeticProps.copyMatching(original, replacement))
                baseToSlab[rid] != null -> CosmeticCodec.resolve(baseToSlab[rid]!!)?.let { plain(CosmeticProps.copyMatching(original, it)) } ?: keep()
                else -> keep()
            }
            ob is CrossCollisionBlock || ob is WallBlock ->
                if (rb is CrossCollisionBlock || rb is WallBlock) plain(CosmeticProps.copyMatching(original, replacement)) else keep()
            ob is EndPortalBlock || ob is EndPortalFrameBlock ->
                if (rb is EndPortalBlock || rb is EndPortalFrameBlock) plain(CosmeticProps.copyMatching(original, replacement)) else keep()
            else -> plain(replacement)
        }
    }

    // ------------------------------------------------------------------
    // Connections
    // ------------------------------------------------------------------

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
