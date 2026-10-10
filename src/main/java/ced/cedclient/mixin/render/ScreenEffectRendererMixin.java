package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.RenderOptimizer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Render Optimizer: underwater, fire and in-wall screen overlays. */
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {

    @Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
    private static void cedclient$noWater(Minecraft mc, PoseStack pose, MultiBufferSource buffers, CallbackInfo ci) {
        if (RenderOptimizer.hideWaterOverlay()) ci.cancel();
    }

    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void cedclient$noFire(PoseStack pose, MultiBufferSource buffers, TextureAtlasSprite sprite, CallbackInfo ci) {
        if (RenderOptimizer.hideFireOverlay()) ci.cancel();
    }

    @Inject(method = "renderTex", at = @At("HEAD"), cancellable = true)
    private static void cedclient$noWall(TextureAtlasSprite sprite, PoseStack pose, MultiBufferSource buffers, CallbackInfo ci) {
        if (RenderOptimizer.hideWallOverlay()) ci.cancel();
    }
}
