package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.RenderOptimizer;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Render Optimizer: the splash particles + sounds rain spawns (26.2 moved them from WeatherEffectRenderer). */
@Mixin(ClientLevel.class)
public class ClientLevelWeatherMixin {

    @Inject(method = "tickWeatherEffects", at = @At("HEAD"), cancellable = true)
    private void cedclient$noRainParticles(CallbackInfo ci) {
        if (RenderOptimizer.hideWeather()) ci.cancel();
    }
}
