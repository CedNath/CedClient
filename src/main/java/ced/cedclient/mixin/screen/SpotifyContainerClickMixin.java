package ced.cedclient.mixin.screen;

import ced.cedclient.features.impl.misc.Spotify;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Music HUD: its seek bar and buttons can be clicked while an inventory / container screen is open (Click In Inventory). */
@Mixin(value = AbstractContainerScreen.class, priority = 500)
public abstract class SpotifyContainerClickMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void ced$spotifyClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (event.button() == 0 && Spotify.onContainerClick(event.x(), event.y())) cir.setReturnValue(true);
    }
}
