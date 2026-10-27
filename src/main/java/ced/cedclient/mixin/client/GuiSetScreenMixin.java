package ced.cedclient.mixin.client;

import ced.cedclient.input.CedMouseState;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class GuiSetScreenMixin {

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void cedclient$resetMouseState(Screen screen, CallbackInfo ci) {
        CedMouseState.INSTANCE.reset();
    }
}
