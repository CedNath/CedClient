package ced.cedclient.mixin.network;

import ced.cedclient.features.impl.render.HypixelPackCache;
import ced.cedclient.features.impl.render.VanillaTextures;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla Textures: notes whether the server's pack is Hypixel's SkyBlock pack, and with Block Hypixel Pack on
 * answers "accepted, loaded" without loading it. With Keep Hypixel Pack Loaded, answers the same for the pack
 * HypixelPackCache already has loaded.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public class ResourcePackPushMixin {

    @Inject(method = "handleResourcePackPush", at = @At("HEAD"), cancellable = true)
    private void cedclient$hypixelPack(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        String url = packet.url();
        boolean skyblock = url.contains("hypixel.net") && url.contains("SkyBlock");
        VanillaTextures.skyblockPack = skyblock;
        if (!skyblock) return;
        boolean block = VanillaTextures.INSTANCE.isEnabled() && VanillaTextures.INSTANCE.getBlockPack().getValue();
        if (!block && !HypixelPackCache.onPush(packet.id(), url, packet.hash())) return;

        ci.cancel();
        VanillaTextures.skyblockPack = !block;
        ClientCommonPacketListenerImpl self = (ClientCommonPacketListenerImpl) (Object) this;
        self.send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.ACCEPTED));
        if (!block) self.send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.DOWNLOADED));
        self.send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
    }
}
