package ced.cedclient.mixin.client;

import ced.cedclient.features.impl.funqol.ProtectItem;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ProtectItem: the drop key in-game does nothing while the held item is protected. */
@Mixin(LocalPlayer.class)
public class LocalPlayerDropMixin {
    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void cedclient$protectItem(boolean fullStack, CallbackInfoReturnable<Boolean> cir) {
        if (ProtectItem.blocksHeldDrop()) cir.setReturnValue(false);
    }
}
