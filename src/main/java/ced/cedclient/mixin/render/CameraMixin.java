package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.Freecam;
import ced.cedclient.features.impl.render.Zoom;
import ced.cedclient.mixin.accessor.CameraAccessor;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {

    // Stashed from ced$afterUpdate() every frame so calculateFov()/
    // calculateHudFov() below can reuse the same partial tick without
    // needing their own DeltaTracker parameter (calculateFov only takes the
    // base FOV float -- see NOTE below). update() always runs before the
    // renderer asks for FOV within the same frame, so this is fresh by the
    // time it's read.
    private float ced$lastPartialTick = 1.0f;

    @Inject(method = "update", at = @At("TAIL"))
    private void ced$afterUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        ced$lastPartialTick = partialTick;

        if (!Freecam.INSTANCE.isActive()) return;

        CameraAccessor acc = (CameraAccessor) (Object) this;

        // Override camera position
        acc.ced$setPosition(Freecam.INSTANCE.getInterpolatedPos(partialTick));

        // Override camera rotation
        acc.ced$setRotation(
                (float) Freecam.INSTANCE.getYaw(partialTick),
                (float) Freecam.INSTANCE.getPitch(partialTick)
        );
    }

    // Divides the vanilla FOV by Zoom's current interpolated divisor.
    // Applies whenever the divisor is above 1.0, not just while `zooming`
    // is true, so the FOV eases back out to normal over the last few ticks
    // instead of snapping the instant the key is released.
    //
    // NOTE: "calculateFov(F)F" is the mapped signature I'd expect for your
    // MC version (takes the already-computed base FOV, returns the final
    // one) -- confirm the exact name against your mappings before building,
    // same as the other mixins in this file already ask you to.
    @ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
    private float ced$applyZoomFov(float original) {
        double divisor = Zoom.INSTANCE.getZoomDivisor(ced$lastPartialTick);
        if (divisor <= 1.0) return original;
        return (float) (original / divisor);
    }

    // Same divisor applied to the HUD/held-item FOV so the hand doesn't
    // stay huge relative to a heavily zoomed-in view.
    @ModifyReturnValue(method = "calculateHudFov", at = @At("RETURN"))
    private float ced$applyZoomHudFov(float original) {
        double divisor = Zoom.INSTANCE.getZoomDivisor(ced$lastPartialTick);
        if (divisor <= 1.0) return original;
        return (float) (original / divisor);
    }
}