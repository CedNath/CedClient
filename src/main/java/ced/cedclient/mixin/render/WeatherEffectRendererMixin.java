package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.RenderOptimizer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Render Optimizer: rain / snow, and the splash particles + sounds rain spawns. */
@Mixin(WeatherEffectRenderer.class)
public class WeatherEffectRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cedclient$noWeather(Vec3 cameraPos, WeatherRenderState state, CallbackInfo ci) {
        if (RenderOptimizer.hideWeather()) ci.cancel();
    }

    @Inject(method = "tickRainParticles", at = @At("HEAD"), cancellable = true)
    private void cedclient$noRainParticles(ClientLevel level, Camera camera, int ticks, ParticleStatus particles, int renderDistance, CallbackInfo ci) {
        if (RenderOptimizer.hideWeather()) ci.cancel();
    }
}
