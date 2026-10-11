package ced.cedclient.mixin.cosmetic;

import ced.cedclient.render.cosmetic.CosmeticHooks;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaced blocks can have different shapes than the originals; don't cull faces unless both sides are full blocks. */
@Mixin(Block.class)
public class BlockFaceCullingMixin {

    @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
    private static void cedclient$fixCulling(BlockState adjacentState, BlockState thisState, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (!CosmeticHooks.hasActive()) return;
        VoxelShape adjShape = thisState.getFaceOcclusionShape(direction.getOpposite());
        VoxelShape selfShape = adjacentState.getFaceOcclusionShape(direction);
        if (adjShape != Shapes.block() || selfShape != Shapes.block()) {
            cir.setReturnValue(true);
        }
    }
}
