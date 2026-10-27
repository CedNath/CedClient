package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.CustomSky;
import ced.cedclient.features.impl.render.SkyBox;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Custom Sky > Skybox: the picture cube is drawn right after the sky colour, under the sun, moon and stars. */
@Mixin(SkyRenderer.class)
public class SkyRendererSkyboxMixin {

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"))
    private void cedclient$skybox(CallbackInfo ci) {
        String name = CustomSky.skyboxName();
        if (name != null) SkyBox.INSTANCE.render(name);
    }
}
