package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.RenderOptimizer;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Render Optimizer: underwater, fire and in-wall screen overlays. */
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {

    @Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
    private static void cedclient$noWater(CallbackInfo ci) {
        if (RenderOptimizer.hideWaterOverlay()) ci.cancel();
    }

    @Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
    private static void cedclient$noFire(CallbackInfo ci) {
        if (RenderOptimizer.hideFireOverlay()) ci.cancel();
    }

    @Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true)
    private static void cedclient$noWall(CallbackInfo ci) {
        if (RenderOptimizer.hideWallOverlay()) ci.cancel();
    }
}
