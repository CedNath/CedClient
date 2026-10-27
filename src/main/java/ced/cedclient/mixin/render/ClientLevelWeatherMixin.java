package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.RenderOptimizer;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Render Optimizer: the rain splash particles and sounds. */
@Mixin(ClientLevel.class)
public class ClientLevelWeatherMixin {

    @Inject(method = "tickWeatherEffects", at = @At("HEAD"), cancellable = true)
    private void cedclient$noRainEffects(CallbackInfo ci) {
        if (RenderOptimizer.hideWeather()) ci.cancel();
    }
}
