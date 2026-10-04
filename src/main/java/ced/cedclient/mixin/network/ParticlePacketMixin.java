package ced.cedclient.mixin.network;

import ced.cedclient.events.ParticleEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Posts a ParticleEvent for every server particle packet. Injected at TAIL (not HEAD) so it
 * only runs on the main thread -- on the netty thread the handler bails out early to
 * re-schedule itself, and we need to touch the level (entity lookups) from the listener.
 * Does not cancel anything.
 *
 * NOTE: if this fails to apply at startup, handleParticleEvent has a different name on your
 * mappings -- search ClientPacketListener for ClientboundLevelParticlesPacket in IntelliJ.
 */
@Mixin(ClientPacketListener.class)
public abstract class ParticlePacketMixin {

    @Inject(method = "handleParticleEvent", at = @At("TAIL"))
    private void cedclient$onParticle(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        new ParticleEvent(packet).post();
    }
}