package cedclient.mixin;

import ced.cedclient.events.RenderEvent;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.Camera;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    // 26.2: renderLevel was renamed to render, and no longer takes in the
    // ChunkSectionsToRender parameter (chunk-to-render tracking moved elsewhere
    // as part of the LevelRenderer/LevelExtractor split).
    @Inject(method = "render", at = @At("TAIL"))
    private void cedclient$onRenderLevel(
            GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker, boolean renderOutline, CameraRenderState cameraState, Matrix4fc modelViewMatrix, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, CallbackInfo ci
    ) {
        float tickDelta = deltaTracker.getGameTimeDeltaPartialTick(false);
        new RenderEvent(tickDelta).post();
    }
}