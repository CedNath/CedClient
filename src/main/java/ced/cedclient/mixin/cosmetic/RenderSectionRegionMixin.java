package ced.cedclient.mixin.cosmetic;

//#if CHEAT
import ced.cedclient.features.impl.funqol.SecretHitboxes;
//#endif
import ced.cedclient.render.cosmetic.CosmeticHooks;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
//#if CHEAT
import net.minecraft.world.level.block.Blocks;
//#endif
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * RenderSectionRegion is the world view the chunk mesher reads, so swapping the state here keeps the model,
 * culling, ambient occlusion and lighting consistent. Collision / hitboxes / server are untouched.
 * Order: per-position replacement or global remap (+ Keep Block Size + connections), then neighbour connection
 * fixes, then Lava as Water.
 */
@Mixin(RenderSectionRegion.class)
public class RenderSectionRegionMixin {

    @Unique
    private static boolean cedclient$reported = false;

    @Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true)
    private void cedclient$cosmeticBlocks(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
//#if CHEAT
        if (SecretHitboxes.isHidden(pos.getX(), pos.getY(), pos.getZ())) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
            return;
        }
//#endif
        if (!CosmeticHooks.anyActive()) return;

        try {
            BlockState original = cir.getReturnValue();
            boolean isAir = original == null || original.isAir();
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            RenderSectionRegion region = (RenderSectionRegion) (Object) this;

            BlockState replacement = CosmeticHooks.replacementOrRemap(original, x, y, z, isAir);
            if (replacement != null) {
                BlockState result = CosmeticHooks.applyOriginalShape(original, replacement, x, y, z);
                cir.setReturnValue(CosmeticHooks.updateConnections(result, x, y, z,
                        (nx, ny, nz) -> region.getBlockState(new BlockPos(nx, ny, nz))));
                return;
            }
            if (!isAir && CosmeticHooks.hasActive()
                    && (original.getBlock() instanceof CrossCollisionBlock || original.getBlock() instanceof WallBlock)
                    && CosmeticHooks.hasAdjacent(x, y, z)) {
                BlockState updated = CosmeticHooks.updateConnections(original, x, y, z,
                        (nx, ny, nz) -> region.getBlockState(new BlockPos(nx, ny, nz)));
                if (updated != original) {
                    cir.setReturnValue(updated);
                    return;
                }
            }
            BlockState fluid = CosmeticHooks.swapFluid(original);
            if (fluid != null) cir.setReturnValue(fluid);
        } catch (Throwable t) {
            // Never let a cosmetic bug take down chunk building: fall back to the real block and log once.
            if (!cedclient$reported) {
                cedclient$reported = true;
                t.printStackTrace();
            }
        }
    }
}
