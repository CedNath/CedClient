package ced.cedclient.mixin.network;

import ced.cedclient.features.impl.render.HypixelPackCache;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.PackReloadConfig;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep Hypixel Pack Loaded (HypixelPackCache): adds the saved pack next to the server packs and saves new ones. */
@Mixin(DownloadedPackSource.class)
public class DownloadedPackSourceMixin {

    @Inject(method = "createRepositorySource", at = @At("RETURN"), cancellable = true)
    private void cedclient$savedHypixelPack(CallbackInfoReturnable<RepositorySource> cir) {
        RepositorySource server = cir.getReturnValue();
        cir.setReturnValue(output -> {
            HypixelPackCache.loadPacks(output);
            server.loadPacks(output);
        });
    }

    @Inject(method = "startReload", at = @At("HEAD"))
    private void cedclient$saveHypixelPack(PackReloadConfig.Callbacks callbacks, CallbackInfo ci) {
        HypixelPackCache.onReload(callbacks.packsToLoad());
    }
}
