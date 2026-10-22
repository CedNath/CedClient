package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.Hiders;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hiders > Nearby Particles. */
@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {

    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void cedclient$hideNearby(Particle particle, CallbackInfo ci) {
        if (Hiders.hideParticle(particle)) ci.cancel();
    }
}
