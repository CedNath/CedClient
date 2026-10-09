package ced.cedclient.mixin.render;

import ced.cedclient.render.cosmetic.CosmeticHooks;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * RenderSectionRegion is the world view the chunk mesher reads, so swapping the state here keeps the model,
 * culling, ambient occlusion and lighting consistent. Collision / hitboxes / server are untouched.
 */
@Mixin(RenderSectionRegion.class)
public class RenderSectionRegionMixin {

    private static boolean cedclient$reported = false;

    @Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true)
    private void cedclient$cosmeticBlocks(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (!CosmeticHooks.hasActive()) return;

        try {
            BlockState original = cir.getReturnValue();
            boolean isAir = original == null || original.isAir();
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            RenderSectionRegion region = (RenderSectionRegion) (Object) this;

            BlockState replacement = CosmeticHooks.replacementAt(x, y, z, isAir);
            if (replacement != null) {
                cir.setReturnValue(CosmeticHooks.updateConnections(replacement, x, y, z,
                        (nx, ny, nz) -> region.getBlockState(new BlockPos(nx, ny, nz))));
            } else if (!isAir
                    && (original.getBlock() instanceof CrossCollisionBlock || original.getBlock() instanceof WallBlock)
                    && CosmeticHooks.hasAdjacent(x, y, z)) {
                BlockState updated = CosmeticHooks.updateConnections(original, x, y, z,
                        (nx, ny, nz) -> region.getBlockState(new BlockPos(nx, ny, nz)));
                if (updated != original) cir.setReturnValue(updated);
            }
        } catch (Throwable t) {
            // Never let a cosmetic bug take down chunk building: fall back to the real block and log once.
            if (!cedclient$reported) {
                cedclient$reported = true;
                t.printStackTrace();
            }
        }
    }
}