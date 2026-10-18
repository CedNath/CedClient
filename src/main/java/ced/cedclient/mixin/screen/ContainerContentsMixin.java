//#if CHEAT
package ced.cedclient.mixin.screen;

import ced.cedclient.features.impl.funqol.AutoExperiments;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Slot overlays drawn right after the slots, so the hovered item's tooltip still goes on top of them. */
@Mixin(AbstractContainerScreen.class)
public class ContainerContentsMixin {
    @Inject(method = "extractContents", at = @At("TAIL"))
    private void cedclient$slotOverlays(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        AutoExperiments.INSTANCE.drawOverlays((AbstractContainerScreen<?>) (Object) this, graphics);
    }
}
//#endif
