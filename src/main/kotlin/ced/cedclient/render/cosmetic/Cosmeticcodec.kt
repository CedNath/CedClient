package ced.cedclient.render.cosmetic

import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * Block-state <-> string, same format as the cb mod: "minecraft:oak_stairs[facing=east]",
 * with an optional leading "!" meaning "phantom" (also applies where the original block is air).
 */
object CosmeticCodec {

    fun normalizeId(input: String): String {
        val c = input.trim().lowercase()
        return if (':' in c) c else "minecraft:$c"
    }

    fun resolve(raw: String): BlockState? {
        return try {
            val s = if (raw.startsWith("!")) raw.substring(1) else raw
            val bracket = s.indexOf('[')
            val idPart = if (bracket == -1) s else s.substring(0, bracket)
            val block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(idPart)).orElse(null) ?: return null
            var state = block.defaultBlockState()
            if (bracket != -1) {
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
        val player = Minecraft.getInstance().player ?: return null
        val block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(normalizeId(blockId))).orElse(null) ?: return null
        return block.getStateForPlacement(BlockPlaceContext(player, InteractionHand.MAIN_HAND, ItemStack(block.asItem()), hit))
    }
}