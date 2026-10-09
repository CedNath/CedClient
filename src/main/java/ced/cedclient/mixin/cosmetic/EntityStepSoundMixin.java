package ced.cedclient.mixin.cosmetic;

import ced.cedclient.render.cosmetic.CosmeticHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Footsteps sound like the cosmetic block you walk on. */
@Mixin(Entity.class)
public class EntityStepSoundMixin {

    @ModifyVariable(method = "playStepSound", at = @At("HEAD"), argsOnly = true)
    private BlockState cedclient$stepSound(BlockState state, BlockPos pos, BlockState stateArg) {
        Entity self = (Entity) (Object) this;
        if (!self.level().isClientSide()) return state;
        BlockState r = CosmeticHooks.effectReplacement(pos, state);
        return r != null ? r : state;
    }
}
