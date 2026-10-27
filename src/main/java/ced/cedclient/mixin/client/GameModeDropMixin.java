package ced.cedclient.mixin.client;

import ced.cedclient.features.impl.funqol.ProtectItem;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ProtectItem: the drop key in-game does nothing while the held item is protected. */
@Mixin(MultiPlayerGameMode.class)
public class GameModeDropMixin {
    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void cedclient$protectItem(LocalPlayer player, boolean fullStack, CallbackInfo ci) {
        if (ProtectItem.blocksHeldDrop()) ci.cancel();
    }
}
