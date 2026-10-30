package ced.cedclient.mixin.screen;

import ced.cedclient.features.impl.render.TitleBackground;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Title Background: draw our animation instead of the panorama on the title screen only. */
@Mixin(Screen.class)
public abstract class TitleScreenBackgroundMixin {

    @Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
    private void ced$titleBackground(GuiGraphicsExtractor graphics, float a, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        // title screen and every menu opened from it (options, worlds, servers...): vanilla only draws the panorama while no world is loaded
        if ((self instanceof TitleScreen || Minecraft.getInstance().level == null) && TitleBackground.INSTANCE.isActive()) {
            TitleBackground.INSTANCE.draw(graphics, self.width, self.height);
            ci.cancel();
        }
    }
}
