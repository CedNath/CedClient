package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.RenderOptimizer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Render Optimizer: no block break / mining crack particles. Every break (yours, other players' via the level event,
 * and cosmetic blocks, whose particles go through these same two methods) ends up here.
 */
@Mixin(ClientLevel.class)
public class BlockBreakParticlesMixin {

    @Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
    private void cedclient$noDestroyParticles(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (RenderOptimizer.hideBlockBreaking()) ci.cancel();
    }

    @Inject(method = "addBreakingBlockEffect", at = @At("HEAD"), cancellable = true)
    private void cedclient$noCrackParticles(BlockPos pos, Direction side, CallbackInfo ci) {
        if (RenderOptimizer.hideBlockBreaking()) ci.cancel();
    }
}
