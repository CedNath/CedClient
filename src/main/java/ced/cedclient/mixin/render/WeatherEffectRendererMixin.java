package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.RenderOptimizer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Render Optimizer: rain / snow. The splash particles + sounds rain spawns are in ClientLevelWeatherMixin. */
@Mixin(WeatherEffectRenderer.class)
public class WeatherEffectRendererMixin {

    @Inject(method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
            at = @At("HEAD"), cancellable = true)
    private void cedclient$noWeather(CallbackInfo ci) {
        if (RenderOptimizer.hideWeather()) ci.cancel();
    }

    @Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
    private void cedclient$noWeatherOit(CallbackInfo ci) {
        if (RenderOptimizer.hideWeather()) ci.cancel();
    }
}
