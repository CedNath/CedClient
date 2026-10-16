package ced.cedclient.mixin.network;

import ced.cedclient.features.impl.misc.KeepConnection;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keep Connection: vanilla's onPacketError disconnects on any packet that throws while handling.
 * Log it and cancel instead, so the bad packet is just dropped.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class PacketErrorMixin {

    @Unique
    private static final Logger cedclient$LOGGER = LoggerFactory.getLogger("CedClient");

    @Inject(method = "onPacketError", at = @At("HEAD"), cancellable = true)
    private void cedclient$keepConnection(Packet<?> packet, Exception cause, CallbackInfo ci) {
        if (!KeepConnection.INSTANCE.isEnabled()) return;
        cedclient$LOGGER.error("Failed to handle packet {}, staying connected", packet, cause);
        ci.cancel();
    }
}
