package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.RenderOptimizer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Render Optimizer: underwater, fire and in-wall screen overlays.
 * 26.2: renderWater/renderFire/renderTex became submitWater/submitFire/submitBlockSprite (SubmitNodeCollector).
 */
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {

    @Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
    private static void cedclient$noWater(Minecraft mc, PoseStack pose, SubmitNodeCollector collector, CallbackInfo ci) {
        if (RenderOptimizer.hideWaterOverlay()) ci.cancel();
    }

    @Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
    private static void cedclient$noFire(PoseStack pose, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
        if (RenderOptimizer.hideFireOverlay()) ci.cancel();
    }

    @Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true)
    private static void cedclient$noWall(TextureAtlasSprite sprite, PoseStack pose, SubmitNodeCollector collector, int color, CallbackInfo ci) {
        if (RenderOptimizer.hideWallOverlay()) ci.cancel();
    }
}
