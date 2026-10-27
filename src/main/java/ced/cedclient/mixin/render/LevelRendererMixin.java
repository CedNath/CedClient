package ced.cedclient.mixin.render;

import ced.cedclient.events.RenderEvent;
import net.minecraft.client.renderer.LevelRenderer;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Minecraft;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void cedclient$onRenderLevel(
            GraphicsResourceAllocator resourceAllocator, boolean advanceGameTime, CameraRenderState cameraState, GpuBufferSlice terrainFog, Vector4f fogColor, boolean renderSky, boolean renderOutline, CallbackInfo ci
    ) {
        float tickDelta = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        new RenderEvent(tickDelta).post();
    }
}
