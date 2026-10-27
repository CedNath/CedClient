package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.LavaToWater;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lava To Water: lava is drawn with the water model (and optionally a tint). */
@Mixin(FluidStateModelSet.class)
public abstract class FluidStateModelSetMixin {

    @Inject(method = "get", at = @At("RETURN"), cancellable = true)
    private void cedclient$lavaAsWater(FluidState state, CallbackInfoReturnable<FluidModel> cir) {
        LavaToWater.INSTANCE.modelHook((FluidStateModelSet) (Object) this, state, cir);
    }
}
