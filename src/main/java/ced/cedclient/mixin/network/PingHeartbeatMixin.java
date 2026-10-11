package ced.cedclient.mixin.network;

import ced.cedclient.utils.TpsTracker;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts server ticks from the server's ping heartbeat, the same way FishMod does: hooked where the
 * connection reads a packet off the wire (once per packet, before it's handed to the listener), only
 * pings with a non-zero id, and recorded on the main thread so it stays ordered with chat.
 *
 * Hooking ClientCommonPacketListenerImpl.handlePing instead over-counted: that method also runs for
 * pings unpacked from bundles and once more on the netty thread before being re-queued, which pushed
 * tick time above real time and hid lag.
 *
 * Only feeds TpsTracker -- does NOT cancel anything, so vanilla still handles the packet as before.
 */
@Mixin(Connection.class)
public abstract class PingHeartbeatMixin {

    @Inject(
            method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V")
    )
    private void cedclient$onPacket(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ClientboundPingPacket ping && ping.getId() != 0) {
            Minecraft.getInstance().execute(TpsTracker::recordTick);
        }
    }
}
