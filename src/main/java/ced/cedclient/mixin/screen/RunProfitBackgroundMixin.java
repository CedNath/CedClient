package ced.cedclient.mixin.screen;

import ced.cedclient.features.impl.funqol.RunProfit;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Run Profit: no chest texture behind the Fancy Menu (RunProfit draws its own backdrop). */
@Mixin(ContainerScreen.class)
public abstract class RunProfitBackgroundMixin {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void ced$runProfitBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        if (RunProfit.INSTANCE.handles((ContainerScreen) (Object) this)) ci.cancel();
    }
}
