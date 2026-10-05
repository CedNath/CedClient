package ced.cedclient.mixin.screen;

import ced.cedclient.input.CedMouseState;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.2: Minecraft#setScreen moved onto the Gui class (the GUI/HUD reorg).
// This is the old MinecraftMixin#setScreen inject, retargeted.
@Mixin(Gui.class)
public class GuiMixin {

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void cedclient$resetMouseState(Screen screen, CallbackInfo ci) {
        CedMouseState.INSTANCE.reset();
    }

}
