package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.SwordBlocking;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sword Blocking: first-person pose, applied just before the held item is drawn. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class SwordBlockingHandMixin {

    @Inject(
        method = "submitArmWithItem",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V", ordinal = 1)
    )
    private void ced$swordBlock(PlayerRenderState state, FirstPersonHandsAndItemsRenderState hands, float a, float b,
                                InteractionHand hand, float c, ItemStack stack, float d, PoseStack pose,
                                SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (hand != InteractionHand.MAIN_HAND || !SwordBlocking.INSTANCE.blocking(stack)) return;
        float side = state.avatarRenderState.mainArm == HumanoidArm.RIGHT ? 1f : -1f;
        pose.translate(side * (-0.14142136f + (float) SwordBlocking.INSTANCE.getOffsetX().getValue().doubleValue()),
            0.08f + (float) SwordBlocking.INSTANCE.getOffsetY().getValue().doubleValue(),
            0.14142136f + (float) SwordBlocking.INSTANCE.getOffsetZ().getValue().doubleValue());
        pose.rotateDegrees(Axis.XP, -102.25f);
        pose.rotateDegrees(Axis.YP, side * 13.365f);
        pose.rotateDegrees(Axis.ZP, side * 78.05f);
    }
}
