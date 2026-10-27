package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.CustomSky;
import ced.cedclient.accessor.ClockInstanceAccess;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom Sky: the overworld clock reads the time of day the player picked instead of the server's. */
@Mixin(targets = "net.minecraft.client.ClientClockManager$ClientClockInstance")
public class ClientClockInstanceMixin implements ClockInstanceAccess {

    @Unique
    private Holder<WorldClock> cedclient$definition;

    @Override
    public void cedclient$setDefinition(Holder<WorldClock> definition) {
        this.cedclient$definition = definition;
    }

    @Override
    public Holder<WorldClock> cedclient$getDefinition() {
        return cedclient$definition;
    }

    @Inject(method = "totalTicks", at = @At("HEAD"), cancellable = true)
    private void cedclient$timeOfDay(CallbackInfoReturnable<Long> cir) {
        if (cedclient$definition == null) return;
        Long ticks = CustomSky.overrideTicks(cedclient$definition);
        if (ticks != null) cir.setReturnValue(ticks);
    }
}
