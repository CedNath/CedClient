package ced.cedclient.render.cosmetic

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/** Dungeon room rotation, same offsets as the cb mod (the corner the blue-terracotta marker sits in). */
enum class RoomRotation(val x: Int, val z: Int) {
    NORTH(15, 15), SOUTH(-15, -15), WEST(15, -15), EAST(-15, 15), NONE(0, 0);

    /** World-relative (to the marker) position -> position as stored (room facing north). */
    fun toNorth(p: BlockPos): BlockPos = when (this) {
        NORTH -> BlockPos(-p.x, p.y, -p.z)
        WEST -> BlockPos(p.z, p.y, -p.x)
        SOUTH, NONE -> p
        EAST -> BlockPos(-p.z, p.y, p.x)
    }

    /** Inverse of [toNorth]. */
    fun fromNorth(p: BlockPos): BlockPos = when (this) {
        NORTH -> BlockPos(-p.x, p.y, -p.z)
        WEST -> BlockPos(-p.z, p.y, p.x)
        SOUTH, NONE -> p
        EAST -> BlockPos(p.z, p.y, -p.x)
    }

    fun toNorth(d: Direction): Direction {
        if (!d.axis.isHorizontal || this == NONE) return d
        val r = toNorth(BlockPos(d.stepX, 0, d.stepZ))
        return Direction.getNearest(r.x, 0, r.z, d) ?: d
    }

    fun fromNorth(d: Direction): Direction {
        if (!d.axis.isHorizontal || this == NONE) return d
        val r = fromNorth(BlockPos(d.stepX, 0, d.stepZ))
        return Direction.getNearest(r.x, 0, r.z, d) ?: d
    }
}

/**
 * Block-state <-> string, same format as the cb mod:
 *   [!]minecraft:oak_stairs[facing=east][|shape=fence|wall|pane]
 * "!" = phantom (also shows where the real block is air), "|shape=" = draw this block's texture on a
 * fence / wall / pane model.
 */
object CosmeticCodec {

    val SHAPES = listOf("fence", "wall", "pane")

    class Raw(val phantom: Boolean, val base: String, val shape: String?)

    fun parseRaw(raw: String): Raw {
        var s = raw
        val phantom = s.startsWith("!")
        if (phantom) s = s.substring(1)
        val idx = s.indexOf("|shape=")
        val shape = if (idx >= 0) s.substring(idx + 7) else null
        val base = if (idx >= 0) s.substring(0, idx) else s
        return Raw(phantom, base, shape)
    }

    fun buildRaw(base: String, phantom: Boolean, shape: String?): String {
        val withShape = if (shape != null) "$base|shape=$shape" else base
        return if (phantom) "!$withShape" else withShape
    }

    fun normalizeId(input: String): String {
        val c = input.trim().lowercase()
        return if (':' in c) c else "minecraft:$c"
    }

    fun shapeState(shape: String): BlockState? = when (shape) {
        "fence" -> Blocks.OAK_FENCE.defaultBlockState()
        "wall" -> Blocks.COBBLESTONE_WALL.defaultBlockState()
        "pane" -> Blocks.GLASS_PANE.defaultBlockState()
        else -> null
    }

    /** The state described by [raw]'s block part (a leading "!" and a "|shape=" suffix are ignored). */
    fun resolve(raw: String): BlockState? {
        return try {
            val s = parseRaw(raw).base
            val bracket = s.indexOf('[')
            val idPart = if (bracket == -1) s else s.substring(0, bracket)
            val block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(idPart)).orElse(null) ?: return null
            var state = block.defaultBlockState()
            if (bracket != -1 && s.endsWith("]")) {
                for (entry in s.substring(bracket + 1, s.length - 1).split(',')) {
                    val eq = entry.indexOf('=')
                    if (eq == -1) continue
                    val prop = block.stateDefinition.getProperty(entry.substring(0, eq)) ?: continue
                    state = CosmeticProps.setByName(state, prop, entry.substring(eq + 1)) ?: state
                }
            }
            state
        } catch (e: Exception) {
            null
        }
    }

    fun serialize(state: BlockState): String = CosmeticProps.serialize(state)

    /** The state vanilla placement would produce for this block against [hit] (stairs facing, slab half, ...). */
    fun placementState(blockId: String, hit: BlockHitResult): BlockState? {
        return try {
            val player = Minecraft.getInstance().player ?: return null
            val id = normalizeId(blockId).substringBefore('[')
            val block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).orElse(null) ?: return null
            block.getStateForPlacement(BlockPlaceContext(player, InteractionHand.MAIN_HAND, ItemStack(block.asItem()), hit))
        } catch (_: Exception) {
            null
        }
    }

    /** "stone deepslate:3 oak_planks" -> [(stone,1),(deepslate,3),(oak_planks,1)]. null if any token is invalid. */
    fun parseWeighted(input: String): List<Pair<BlockState, Int>>? {
        val tokens = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val result = ArrayList<Pair<BlockState, Int>>()
        for (token in tokens) {
            val colon = token.lastIndexOf(':')
            var id = token
            var weight = 1
            if (colon > 0) {
                val tail = token.substring(colon + 1).toIntOrNull()
                if (tail != null) {
                    if (tail <= 0) return null
                    id = token.substring(0, colon)
                    weight = tail
                }
            }
            val state = resolve(normalizeId(id)) ?: return null
            result += state to weight
        }
        return result.ifEmpty { null }
    }

    fun pickWeighted(blocks: List<Pair<BlockState, Int>>): BlockState {
        if (blocks.size == 1) return blocks[0].first
        var roll = (Math.random() * blocks.sumOf { it.second }).toInt()
        for ((state, weight) in blocks) {
            roll -= weight
            if (roll < 0) return state
        }
        return blocks.last().first
    }

    // ------------------------------------------------------------------
    // Room rotation: "facing" and "axis" are rotated so a room's blocks can be stored facing north.
    // ------------------------------------------------------------------

    fun rotateState(state: BlockState, rotation: RoomRotation, toNorth: Boolean): BlockState {
        if (rotation == RoomRotation.NONE || rotation == RoomRotation.SOUTH) return state
        var result = state
        for (p in state.block.stateDefinition.properties) {
            when (p.name) {
                "facing" -> {
                    val dir = Direction.byName(CosmeticProps.valueName(state, p)) ?: continue
                    if (!dir.axis.isHorizontal) continue
                    val rotated = if (toNorth) rotation.toNorth(dir) else rotation.fromNorth(dir)
                    result = CosmeticProps.setByName(result, p, rotated.serializedName) ?: result
                }
                "axis" -> {
                    val name = CosmeticProps.valueName(state, p)
                    if (name != "x" && name != "z") continue
                    // WEST / EAST are quarter turns (x <-> z); NORTH is a half turn (axis unchanged).
                    if (rotation == RoomRotation.WEST || rotation == RoomRotation.EAST) {
                        result = CosmeticProps.setByName(result, p, if (name == "x") "z" else "x") ?: result
                    }
                }
            }
        }
        return result
    }

    /** Re-serializes a raw entry with its block state rotated (phantom flag and shape are kept). */
    fun rotateRaw(raw: String, rotation: RoomRotation, toNorth: Boolean): String {
        if (rotation == RoomRotation.NONE || rotation == RoomRotation.SOUTH) return raw
        val parsed = parseRaw(raw)
        val state = resolve(parsed.base) ?: return raw
        return buildRaw(serialize(rotateState(state, rotation, toNorth)), parsed.phantom, parsed.shape)
    }
}
