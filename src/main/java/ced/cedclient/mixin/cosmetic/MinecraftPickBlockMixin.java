package ced.cedclient.mixin.cosmetic;

import ced.cedclient.features.impl.render.CosmeticBlocks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In Cosmetic Blocks edit mode, middle-click picks the brush (handled by the module) instead of vanilla pick-block. */
@Mixin(Minecraft.class)
public class MinecraftPickBlockMixin {

    @Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
    private void cedclient$pickCosmetic(CallbackInfo ci) {
        if (CosmeticBlocks.interceptPickBlock()) ci.cancel();
    }
}
