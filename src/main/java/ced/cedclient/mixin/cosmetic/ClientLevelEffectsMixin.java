package ced.cedclient.mixin.cosmetic;

import ced.cedclient.render.cosmetic.CosmeticHooks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Break and crack particles use what the block looks like. */
@Mixin(ClientLevel.class)
public class ClientLevelEffectsMixin {

    @ModifyVariable(method = "addDestroyBlockEffect", at = @At("HEAD"), argsOnly = true)
    private BlockState cedclient$destroyParticles(BlockState state, BlockPos pos, BlockState stateArg) {
        BlockState r = CosmeticHooks.effectReplacement(pos, state);
        return r != null ? r : state;
    }

    @Redirect(method = "addBreakingBlockEffects", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState cedclient$crackParticles(ClientLevel level, BlockPos pos, BlockPos posArg, Direction side, boolean fromServer) {
        BlockState world = level.getBlockState(pos);
        BlockState r = CosmeticHooks.effectReplacement(pos, world);
        return r != null ? r : world;
    }
}
