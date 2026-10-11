package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.RenderOptimizer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.fog.environment.FogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Render Optimizer: lets the active fog environment run, then clears its fog if that kind is disabled. */
@Mixin(FogRenderer.class)
public class FogRendererMixin {

    @WrapOperation(
            method = "setupFog",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/environment/FogEnvironment;setupFog(Lnet/minecraft/client/renderer/fog/FogData;Lnet/minecraft/client/Camera;Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/client/DeltaTracker;)V")
    )
    private void cedclient$afterEnvironment(FogEnvironment env, FogData data, Camera camera, ClientLevel level,
                                            float renderDistance, DeltaTracker deltaTracker, Operation<Void> original) {
        original.call(env, data, camera, level, renderDistance, deltaTracker);
        RenderOptimizer.afterEnvironmentFog(env, data, renderDistance);
    }

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void cedclient$afterFog(Camera camera, int renderDistance, DeltaTracker deltaTracker, float darkness,
                                    ClientLevel level, CallbackInfoReturnable<FogData> cir) {
        RenderOptimizer.afterFog(cir.getReturnValue());
    }
}
