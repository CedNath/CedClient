package ced.cedclient.mixin.network;

import ced.cedclient.utils.PingTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds PingTracker when the server answers our ServerboundPingRequestPacket probe.
 * Does not cancel -- vanilla still handles the packet as before.
 *
 * NOTE: if this fails to apply at startup, handlePongResponse has a different name/owner on
 * your mappings: find ClientboundPongResponsePacket's handler in ClientPacketListener (or
 * ClientCommonPacketListenerImpl) in IntelliJ and adjust the method/target class here.
 */
@Mixin(ClientPacketListener.class)
public abstract class PingPongMixin {

    @Inject(method = "handlePongResponse", at = @At("HEAD"))
    private void cedclient$onPong(ClientboundPongResponsePacket packet, CallbackInfo ci) {
        PingTracker.onPong();
    }
}