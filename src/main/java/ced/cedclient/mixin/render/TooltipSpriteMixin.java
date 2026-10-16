package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.CustomTooltips;
import ced.cedclient.features.impl.render.VanillaTextures;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla Tooltips: Hypixel's pack also replaces the default tooltip sprites, so while it is loaded the default
 * tooltip is drawn by CedClient instead (VanillaTextures.drawTooltip) rather than from the pack's sprites.
 * Custom Tooltips goes first: on this (vanilla) path another mod wraps the sprite draw, so TooltipBlitMixin alone missed
 * it. TooltipBlitMixin still covers mods that draw the sprites themselves (scrollable-tooltip mods).
 */
@Mixin(TooltipRenderUtil.class)
public class TooltipSpriteMixin {

    @Inject(method = "extractTooltipBackground", at = @At("HEAD"), cancellable = true)
    private static void cedclient$vanillaTooltip(GuiGraphicsExtractor g, int x, int y, int width, int height, Identifier style, CallbackInfo ci) {
        if (CustomTooltips.INSTANCE.isEnabled()) {
            CustomTooltips.draw(g, x, y, width, height);
            ci.cancel();
            return;
        }
        if (style == null && VanillaTextures.skyblockPack && VanillaTextures.tooltipsActive()) {
            VanillaTextures.drawTooltip(g, x, y, width, height);
            ci.cancel();
        }
    }
}
