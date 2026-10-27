//#if CHEAT
package ced.cedclient.mixin.client;

import ced.cedclient.features.impl.funqol.CameraTweaks;
import net.minecraft.client.Camera;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Camera: a custom third person distance, and a camera that does not stop at walls. */
@Mixin(Camera.class)
public abstract class CameraDistanceMixin {

    @Redirect(method = "alignWithEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getAttributeValue(Lnet/minecraft/core/Holder;)D"))
    private double cedclient$distance(LivingEntity entity, Holder<Attribute> attribute) {
        Double custom = CameraTweaks.INSTANCE.customDistance();
        return custom != null ? custom : entity.getAttributeValue(attribute);
    }

    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void cedclient$noClip(float cameraDist, CallbackInfoReturnable<Float> cir) {
        if (CameraTweaks.INSTANCE.getNoClipActive()) cir.setReturnValue(cameraDist);
    }
}
//#endif
