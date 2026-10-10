package ced.cedclient.mixin.cosmetic;

import ced.cedclient.render.cosmetic.CosmeticHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium meshes chunks from its own LevelSlice instead of vanilla's RenderSectionRegion, so without this
 * {@link RenderSectionRegionMixin} never runs and no cosmetic block shows up. Same swap, on Sodium's world view.
 * {@code @Pseudo} + {@code require = 0}: silently skipped when Sodium is not installed.
 *
 * LevelSlice.getBlockState(BlockPos) delegates to getBlockState(int, int, int), so both are hooked (in case a
 * version does not delegate) and the BlockPos one skips calls the int one already handled.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.LevelSlice", remap = false)
public class SodiumLevelSliceMixin {

    /** Set by the int handler just before it returns, consumed by the BlockPos handler wrapping it. */
    @Unique
    private static final ThreadLocal<Boolean> cedclient$handled = ThreadLocal.withInitial(() -> false);

    @Unique
    private static boolean cedclient$reported = false;

    @Inject(method = "getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private void cedclient$cosmeticBlocks(int x, int y, int z, CallbackInfoReturnable<BlockState> cir) {
        try {
            if (CosmeticHooks.anyActive()) cedclient$swap(x, y, z, cir);
        } finally {
            cedclient$handled.set(true);
        }
    }

    @Inject(method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private void cedclient$cosmeticBlocksPos(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (cedclient$handled.get()) {
            cedclient$handled.set(false);
            return;
        }
        if (CosmeticHooks.anyActive()) cedclient$swap(pos.getX(), pos.getY(), pos.getZ(), cir);
    }

    /** Mirrors RenderSectionRegionMixin: replacement / remap (+ Keep Block Size + connections), neighbour connections, Lava as Water. */
    @Unique
    private void cedclient$swap(int x, int y, int z, CallbackInfoReturnable<BlockState> cir) {
        try {
            BlockState original = cir.getReturnValue();
            boolean isAir = original == null || original.isAir();
            BlockGetter slice = (BlockGetter) (Object) this;

            BlockState replacement = CosmeticHooks.replacementOrRemap(original, x, y, z, isAir);
            if (replacement != null) {
                BlockState result = CosmeticHooks.applyOriginalShape(original, replacement, x, y, z);
                cir.setReturnValue(CosmeticHooks.updateConnections(result, x, y, z,
                        (nx, ny, nz) -> slice.getBlockState(new BlockPos(nx, ny, nz))));
                return;
            }
            if (!isAir && CosmeticHooks.hasActive()
                    && (original.getBlock() instanceof CrossCollisionBlock || original.getBlock() instanceof WallBlock)
                    && CosmeticHooks.hasAdjacent(x, y, z)) {
                BlockState updated = CosmeticHooks.updateConnections(original, x, y, z,
                        (nx, ny, nz) -> slice.getBlockState(new BlockPos(nx, ny, nz)));
                if (updated != original) {
                    cir.setReturnValue(updated);
                    return;
                }
            }
            BlockState fluid = CosmeticHooks.swapFluid(original);
            if (fluid != null) cir.setReturnValue(fluid);
        } catch (Throwable t) {
            // Never let a cosmetic bug take down Sodium's chunk builder: fall back to the real block and log once.
            if (!cedclient$reported) {
                cedclient$reported = true;
                t.printStackTrace();
            }
        }
    }
}
