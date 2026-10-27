package ced.cedclient.mixin.client;

import ced.cedclient.features.impl.misc.AuctionPriceInput;
import ced.cedclient.features.impl.misc.SearchOverlay;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SearchOverlay / AuctionPriceInput: swap Hypixel's search and price / amount signs for our own input screens. */
@Mixin(LocalPlayer.class)
public class LocalPlayerSignMixin {

    @Inject(method = "openTextEdit", at = @At("HEAD"), cancellable = true)
    private void cedclient$searchOverlay(SignBlockEntity sign, SignTextSlot slot, CallbackInfo ci) {
        boolean isFrontText = slot == SignTextSlot.FRONT;
        if (SearchOverlay.tryOpen(sign, isFrontText) || AuctionPriceInput.tryOpen(sign, isFrontText)) ci.cancel();
    }
}
