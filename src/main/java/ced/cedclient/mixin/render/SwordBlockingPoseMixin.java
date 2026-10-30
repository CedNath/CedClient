package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.SwordBlocking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sword Blocking: third-person pose for the local player (other players' blocking isn't known to the client). */
@Mixin(AvatarRenderer.class)
public abstract class SwordBlockingPoseMixin {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
        at = @At("TAIL")
    )
    private void ced$swordBlockPose(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        if (entity != Minecraft.getInstance().player) return;
        if (!SwordBlocking.INSTANCE.blocking(state.getMainHandItemStack())) return;
        if (state.mainArm == HumanoidArm.RIGHT) state.rightArmPose = HumanoidModel.ArmPose.BLOCK;
        else state.leftArmPose = HumanoidModel.ArmPose.BLOCK;
    }
}
