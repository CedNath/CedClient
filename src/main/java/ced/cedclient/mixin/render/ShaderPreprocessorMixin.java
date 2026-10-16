package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.VanillaTextures;
import ced.cedclient.utils.VanillaColorPreprocessor;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** Vanilla Colors: Hypixel's pack hooks a colour-changing include into the core shaders; swap it for a no-op. */
@Mixin(ShaderManager.class)
public class ShaderPreprocessorMixin {

    @Inject(method = "createPreprocessor", at = @At("RETURN"), cancellable = true)
    private static void cedclient$vanillaColors(Map<Identifier, Resource> files, Identifier location, CallbackInfoReturnable<GlslPreprocessor> cir) {
        if (VanillaTextures.colorsActive()) cir.setReturnValue(new VanillaColorPreprocessor(cir.getReturnValue()));
    }
}
