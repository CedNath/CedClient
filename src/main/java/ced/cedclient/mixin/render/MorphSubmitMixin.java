package ced.cedclient.mixin.render;

import ced.cedclient.state.MorphSync;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Morph: a morphed player's AvatarRenderState stays as it is (vanilla needs it, also for the local player) and is drawn by the
 * mob's own renderer here instead of the player model. Same idea as the Walkers mod (MIT).
 */
@Mixin(LivingEntityRenderer.class)
public abstract class MorphSubmitMixin {

    @Inject(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void cedclient$morph(LivingEntityRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        if (state instanceof AvatarRenderState avatar && MorphSync.submitMorph(avatar, pose, collector, camera)) ci.cancel();
    }
}
