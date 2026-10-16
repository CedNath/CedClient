package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.CustomTooltips;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Custom Tooltips: swaps the tooltip background / frame sprites (vanilla's, a pack's or Hypixel's styled ones) for
 * our own box. Hooked at the sprite draw, not TooltipRenderUtil, because scrollable-tooltip mods draw
 * the sprites themselves.
 */
@Mixin(GuiGraphicsExtractor.class)
public class TooltipBlitMixin {

    @Inject(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V", at = @At("HEAD"), cancellable = true)
    private void cedclient$customTooltip(RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height, CallbackInfo ci) {
        if (!CustomTooltips.INSTANCE.isEnabled() || !sprite.getPath().startsWith("tooltip/")) return;
        String path = sprite.getPath();
        if (path.endsWith("background")) {
            // The sprites are drawn 12 px out from the text on every side (3 padding + 9 sprite border).
            CustomTooltips.draw((GuiGraphicsExtractor) (Object) this, x + 12, y + 12, width - 24, height - 24);
            ci.cancel();
        } else if (path.endsWith("frame")) {
            ci.cancel();
        }
    }
}
