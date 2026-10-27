package ced.cedclient.mixin.render;

import ced.cedclient.mixin.accessor.ClockInstanceAccess;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom Sky: tags each client clock instance with its world clock, so its ticks can be overridden (see ClientClockInstanceMixin). */
@Mixin(ClientClockManager.class)
public class ClientClockManagerMixin {

    @Inject(method = "getInstance(Lnet/minecraft/core/Holder;)Lnet/minecraft/client/ClientClockManager$ClientClockInstance;", at = @At("RETURN"))
    private void cedclient$tag(Holder<WorldClock> definition, CallbackInfoReturnable<?> cir) {
        ((ClockInstanceAccess) cir.getReturnValue()).cedclient$setDefinition(definition);
    }
}
