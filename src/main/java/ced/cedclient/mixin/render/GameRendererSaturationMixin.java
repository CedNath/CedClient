package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.Saturation;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Saturation: runs the post chain on the finished world, right before the GUI is drawn. */
@Mixin(GameRenderer.class)
public abstract class GameRendererSaturationMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private CrossFrameResourcePool resourcePool;

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void cedclient$saturation(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        if (minecraft.level == null || !Saturation.INSTANCE.active()) return;
        PostChain chain = minecraft.getShaderManager().getPostChain(Saturation.INSTANCE.getCHAIN(), LevelTargetBundle.MAIN_TARGETS);
        if (chain != null) chain.process(((GameRenderer) (Object) this).mainRenderTarget(), resourcePool);
    }
}
