//#if CHEAT
package ced.cedclient.mixin.client;

import ced.cedclient.features.impl.funqol.CameraTweaks;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Camera: a custom third person distance, and a camera that does not stop at walls. Both go through getMaxZoom, the one place
 * the third person distance is used (a Redirect on the attribute read would clash with other mods that redirect the same call).
 */
@Mixin(Camera.class)
public abstract class CameraDistanceMixin {

    @ModifyVariable(method = "getMaxZoom", at = @At("HEAD"), argsOnly = true)
    private float cedclient$distance(float distance) {
        Double custom = CameraTweaks.INSTANCE.customDistance();
        return custom != null ? custom.floatValue() : distance;
    }

    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void cedclient$noClip(float cameraDist, CallbackInfoReturnable<Float> cir) {
        if (CameraTweaks.INSTANCE.getNoClipActive()) cir.setReturnValue(cameraDist);
    }
}
//#endif
