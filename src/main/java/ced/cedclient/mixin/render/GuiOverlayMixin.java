package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.RenderOptimizer;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Render Optimizer: HUD camera overlays (vignette, pumpkin, freezing, portal, nausea). */
@Mixin(Hud.class)
public class GuiOverlayMixin {

    @Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
    private void cedclient$noVignette(GuiGraphicsExtractor graphics, Entity entity, CallbackInfo ci) {
        if (RenderOptimizer.hideVignette()) ci.cancel();
    }

    @Inject(method = "extractTextureOverlay", at = @At("HEAD"), cancellable = true)
    private void cedclient$noTextureOverlay(GuiGraphicsExtractor graphics, Identifier texture, float alpha, CallbackInfo ci) {
        if (RenderOptimizer.hideTextureOverlay(texture)) ci.cancel();
    }

    @Inject(method = "extractPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void cedclient$noPortal(GuiGraphicsExtractor graphics, float alpha, CallbackInfo ci) {
        if (RenderOptimizer.hidePortal()) ci.cancel();
    }

    @Inject(method = "extractConfusionOverlay", at = @At("HEAD"), cancellable = true)
    private void cedclient$noNausea(GuiGraphicsExtractor graphics, float strength, CallbackInfo ci) {
        if (RenderOptimizer.hideNausea()) ci.cancel();
    }
}
