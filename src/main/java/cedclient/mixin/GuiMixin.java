package cedclient.mixin;

import ced.cedclient.input.CedMouseState;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.2: setScreen moved off Minecraft and onto the new Gui class as part of
// the GUI/HUD reorganization (setScreenAndShow stayed on Minecraft — only
// setScreen itself moved), so this one inject moved out of MinecraftMixin
// and into its own mixin targeting Gui instead.
@Mixin(Gui.class)
public class GuiMixin {

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void cedclient$resetMouseState(Screen screen, CallbackInfo ci) {
        CedMouseState.INSTANCE.reset();
    }

}