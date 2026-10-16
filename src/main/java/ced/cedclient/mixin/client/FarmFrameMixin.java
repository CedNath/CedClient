package ced.cedclient.mixin.client;

import ced.cedclient.farmhelper.FarmHelperCore;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FarmHelper: eases the macro's turns every frame, right after the mouse turned the player. */
@Mixin(MouseHandler.class)
public class FarmFrameMixin {
    @Inject(method = "turnPlayer", at = @At("TAIL"))
    private void cedclient$farmFrame(double movementTime, CallbackInfo ci) {
        FarmHelperCore.onFrame();
    }
}
