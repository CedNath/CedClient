package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.Perspective;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Camera.class)
public abstract class PerspectiveCameraMixin {

    // Camera.alignWithEntity sets the camera rotation from the entity's view angles and then backs the camera off along
    // it for third person, so swapping the angles here moves the whole third-person camera, not just the look direction.
    @ModifyExpressionValue(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
    private float ced$perspectiveYaw(float original) {
        return Perspective.INSTANCE.isActive() ? Perspective.INSTANCE.getYaw() : original;
    }

    @ModifyExpressionValue(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"))
    private float ced$perspectivePitch(float original) {
        return Perspective.INSTANCE.isActive() ? Perspective.INSTANCE.getPitch() : original;
    }
}
