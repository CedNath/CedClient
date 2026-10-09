package ced.cedclient.mixin.cosmetic;

import ced.cedclient.render.cosmetic.CosmeticHooks;
import net.minecraft.client.renderer.LevelEventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Block-break sound of the cosmetic block. */
@Mixin(LevelEventHandler.class)
public class LevelEventHandlerMixin {

    @Redirect(method = "levelEvent", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getSoundType()Lnet/minecraft/world/level/block/SoundType;"))
    private SoundType cedclient$breakSound(BlockState state, int type, BlockPos pos, int data) {
        BlockState r = CosmeticHooks.effectReplacement(pos, state);
        return r != null ? r.getSoundType() : state.getSoundType();
    }
}
