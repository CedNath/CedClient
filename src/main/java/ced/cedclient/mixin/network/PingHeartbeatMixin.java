package ced.cedclient.mixin.network;

import ced.cedclient.utils.TpsTracker;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NOTE: handlePing/ClientboundPingPacket are the current Mojmap names for the
 * server's ping heartbeat (ClientCommonPacketListenerImpl, shared by both
 * play and configuration listeners). If this doesn't compile, Navigate >
 * Declaration on ClientCommonPacketListenerImpl in IntelliJ and search for
 * "Ping" to find the actual method/packet name on your mappings.
 *
 * Only feeds TpsTracker -- does NOT cancel the injection, so vanilla still
 * handles the packet (sends its pong) exactly as before.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class PingHeartbeatMixin {

    @Inject(method = "handlePing", at = @At("HEAD"))
    private void cedclient$onPing(ClientboundPingPacket packet, CallbackInfo ci) {
        TpsTracker.recordTick();
    }
}