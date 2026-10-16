package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.Freecam;
import ced.cedclient.features.impl.render.Zoom;
import ced.cedclient.mixin.accessor.CameraAccessor;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
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

    // Read at HEAD: calculateFov runs inside update(), so a value stored at TAIL would be last frame's
    // (zoom then jumps back a step on frames where a tick happened = stutter with Zoom Speed below 1).
    @Inject(method = "update", at = @At("HEAD"))
    private void ced$beforeUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
        ced$lastPartialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void ced$afterUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
        float partialTick = ced$lastPartialTick;

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
    // Hooked at the call site in update() (where the result is stored into
    // the fov field), not at calculateFov's own RETURN: other mods (FishMod,
    // NoammAddons, Skysoft, Devonian, rfu...) inject into calculateFov, and
    // one cancelling it at HEAD returns early and skips a RETURN hook.
    @ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;calculateFov(F)F"))
    private float ced$applyZoomFov(float original) {
        double divisor = Zoom.INSTANCE.getZoomDivisor(ced$lastPartialTick);
        if (divisor <= 1.0) return original;
        return (float) (original / divisor);
    }

    // Same divisor applied to the HUD/held-item FOV so the hand doesn't
    // stay huge relative to a heavily zoomed-in view.
    @ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;calculateHudFov(F)F"))
    private float ced$applyZoomHudFov(float original) {
        double divisor = Zoom.INSTANCE.getZoomDivisor(ced$lastPartialTick);
        if (divisor <= 1.0) return original;
        return (float) (original / divisor);
    }
}