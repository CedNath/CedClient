package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.HideArmor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide Armor: skips the armor piece before it is submitted. */
@Mixin(HumanoidArmorLayer.class)
public class HideArmorLayerMixin {

    @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)
    private void cedclient$hideArmor(PoseStack pose, SubmitNodeCollector collector, ItemStack stack, EquipmentSlot slot,
                                     int light, HumanoidRenderState state, CallbackInfo ci) {
        if (HideArmor.hideSlot(state, slot)) ci.cancel();
    }
}
