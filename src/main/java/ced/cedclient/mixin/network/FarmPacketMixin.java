//#if CHEAT
package ced.cedclient.mixin.network;

import ced.cedclient.farmhelper.FarmHelperCore;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FarmHelper: hands every incoming packet to the port's failsafes on the client thread. Queued here, before the
 * connection passes the packet to its listener, so it runs before vanilla applies it (old block / position still
 * readable). Same hook point as PingHeartbeatMixin; does not cancel anything.
 */
@Mixin(Connection.class)
public abstract class FarmPacketMixin {

    @Inject(
            method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V")
    )
    private void cedclient$farmPacket(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        Minecraft.getInstance().execute(() -> FarmHelperCore.onPacket(packet));
    }
}
//#endif
