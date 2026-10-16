package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.VanillaTextures;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Vanilla Tooltips: drop Hypixel's tooltip style ("hypixel_skyblock:..."), so the default frame is used. */
@Mixin(value = GuiGraphicsExtractor.class, priority = 500)
public class TooltipStyleMixin {

    @ModifyVariable(method = "tooltip", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private Identifier cedclient$vanillaTooltipStyle(Identifier style) {
        if (style == null || !VanillaTextures.tooltipsActive()) return style;
        return style.getNamespace().equals("hypixel_skyblock") ? null : style;
    }
}
