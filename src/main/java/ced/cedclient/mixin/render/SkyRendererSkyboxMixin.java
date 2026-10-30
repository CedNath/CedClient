package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.CustomSky;
import ced.cedclient.features.impl.render.SkyBox;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Custom Sky > Skybox: the picture cube is drawn right after the sky colour, under the sun, moon and stars. */
@Mixin(SkyRenderer.class)
public class SkyRendererSkyboxMixin {

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"))
    private void cedclient$skybox(RenderPass pass, PoseStack poseStack, float a, float b, float c, MoonPhase phase, float d, float e, CallbackInfo ci) {
        String name = CustomSky.skyboxName();
        if (name != null) SkyBox.INSTANCE.render(pass, name);
    }
}
