package ced.cedclient.mixin.screen;

import ced.cedclient.features.impl.funqol.LeapMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leap Menu: no chest texture behind the custom menu (LeapMenu draws its own dimmed backdrop). */
@Mixin(ContainerScreen.class)
public abstract class LeapMenuBackgroundMixin {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void ced$leapBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        if (LeapMenu.INSTANCE.handles((ContainerScreen) (Object) this)) ci.cancel();
    }
}
