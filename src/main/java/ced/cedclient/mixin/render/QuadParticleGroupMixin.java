package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.CleanView;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Clean View: particles next to the camera are skipped as if they were off-screen. */
@Mixin(QuadParticleGroup.class)
public class QuadParticleGroupMixin {

    @Redirect(
            method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z")
    )
    private boolean cedclient$cleanView(Frustum frustum, double x, double y, double z) {
        return frustum.pointInFrustum(x, y, z) && !CleanView.hideParticle(x, y, z);
    }
}
