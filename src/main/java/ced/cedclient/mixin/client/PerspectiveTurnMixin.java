package ced.cedclient.mixin.client;

import ced.cedclient.features.impl.render.Perspective;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MouseHandler.class)
public class PerspectiveTurnMixin {

    // While Perspective is active the mouse moves the camera only, the player keeps facing where it faced.
    @WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void ced$perspectiveTurn(LocalPlayer player, double dx, double dy, Operation<Void> original) {
        if (Perspective.INSTANCE.isActive()) {
            Perspective.INSTANCE.turn(dx, dy);
            return;
        }
        original.call(player, dx, dy);
    }
}
