package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.LavaToWater;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.LavaFogEnvironment;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lava To Water: no lava fog, and the fog colour follows the water (or the tint). */
@Mixin(LavaFogEnvironment.class)
public abstract class LavaFogMixin {
    @Unique
    private static final WaterFogEnvironment CEDCLIENT_WATER_FOG = new WaterFogEnvironment();

    @Inject(method = "setupFog", at = @At("HEAD"), cancellable = true)
    private void cedclient$noLavaFog(FogData fog, Camera camera, ClientLevel level, float renderDistance, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!LavaToWater.INSTANCE.getActive() || !LavaToWater.INSTANCE.getHideFog()) return;
        fog.color.set(fog.color.x, fog.color.y, fog.color.z, 0f);
        fog.environmentalStart = renderDistance;
        fog.environmentalEnd = renderDistance;
        ci.cancel();
    }

    @Inject(method = "getBaseColor", at = @At("HEAD"), cancellable = true)
    private void cedclient$waterFogColor(ClientLevel level, Camera camera, int renderDistance, float partialTicks, CallbackInfoReturnable<Integer> cir) {
        if (!LavaToWater.INSTANCE.getActive()) return;
        cir.setReturnValue(LavaToWater.INSTANCE.getTinted()
                ? LavaToWater.INSTANCE.getTintRgb()
                : CEDCLIENT_WATER_FOG.getBaseColor(level, camera, renderDistance, partialTicks));
    }
}
