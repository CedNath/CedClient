package cedclient.mixin;

import ced.cedclient.features.impl.render.Freecam;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {

    @Inject(method = "update", at = @At("TAIL"))
    private void ced$afterUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!Freecam.INSTANCE.isEnabled()) return;

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);

        CameraAccessor acc = (CameraAccessor) (Object) this;

        // Override camera position
        acc.ced$setPosition(Freecam.INSTANCE.getInterpolatedPos(partialTick));

        // Override camera rotation
        acc.ced$setRotation(
                (float) Freecam.INSTANCE.getYaw(partialTick),
                (float) Freecam.INSTANCE.getPitch(partialTick)
        );
    }
}
