package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.HideArmor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide Armor > Player Heads: skips the worn head item (skulls, blocks). */
@Mixin(CustomHeadLayer.class)
public class HideCustomHeadMixin {

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;FF)V",
            at = @At("HEAD"), cancellable = true)
    private void cedclient$hideHead(PoseStack pose, SubmitNodeCollector collector, int light, LivingEntityRenderState state,
                                    float yRot, float xRot, CallbackInfo ci) {
        if (HideArmor.hideHead(state)) ci.cancel();
    }
}
