package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.CustomSky;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom Sky: the overworld clock reads the time of day the player picked instead of the server's. */
@Mixin(ClientClockManager.class)
public class ClientClockManagerMixin {

    @Inject(method = "getTotalTicks", at = @At("HEAD"), cancellable = true)
    private void cedclient$timeOfDay(Holder<WorldClock> definition, CallbackInfoReturnable<Long> cir) {
        Long ticks = CustomSky.overrideTicks(definition);
        if (ticks != null) cir.setReturnValue(ticks);
    }
}
