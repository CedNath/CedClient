package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.ActionBarHud;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Action Bar HUD > Held Item Name: draws vanilla's held item name where it was dragged to in the HUD editor, and Always Show Item Name keeps it from fading. */
@Mixin(Gui.class)
public class GuiItemNameMixin {

    @Shadow private int toolHighlightTimer;
    @Shadow private ItemStack lastToolHighlight;

    @Unique private boolean cedclient$moved;

    // 10 ticks = full opacity in extractSelectedItemName; held there, the name never fades.
    @Inject(method = "tick()V", at = @At("TAIL"))
    private void cedclient$keepItemName(CallbackInfo ci) {
        if (ActionBarHud.alwaysShowItemName() && !lastToolHighlight.isEmpty()) toolHighlightTimer = Math.max(toolHighlightTimer, 10);
    }

    @Inject(method = "extractSelectedItemName", at = @At("HEAD"))
    private void cedclient$moveItemName(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        cedclient$moved = ActionBarHud.ItemName.transform(graphics);
    }

    @Inject(method = "extractSelectedItemName", at = @At("TAIL"))
    private void cedclient$restoreItemName(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (cedclient$moved) graphics.pose().popMatrix();
        cedclient$moved = false;
    }
}
