package ced.cedclient.mixin.client;

import ced.cedclient.features.impl.funqol.F7SoundCap;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** F7SoundCap: drops repeated boss sounds and caps their volume. */
@Mixin(SoundEngine.class)
public class SoundEngineMixin {

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void cedclient$capF7Sounds(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (F7SoundCap.shouldCancel(instance)) cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
    }

    // Both start volume and per-tick volume updates of playing sounds go through this overload.
    @Inject(method = "calculateVolume(FLnet/minecraft/sounds/SoundSource;)F", at = @At("RETURN"), cancellable = true)
    private void cedclient$capF7Volume(float volume, SoundSource source, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(F7SoundCap.capVolume(cir.getReturnValueF()));
    }
}
