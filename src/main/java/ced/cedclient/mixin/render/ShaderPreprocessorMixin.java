package ced.cedclient.mixin.render;

import com.google.common.collect.ImmutableMap;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import ced.cedclient.features.impl.render.VanillaTextures;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla Colors: Hypixel's pack hooks a colour-changing include into the core shaders; swap it for a no-op. */
@Mixin(ShaderManager.class)
public abstract class ShaderPreprocessorMixin {

    private static final String NO_OP =
            "vec4 getModifiedVanillaColor(vec4 color) { return color; }\n" +
            "vec4 getModifiedNormalColor(int color, float alpha) { return vec4(0.0); }\n" +
            "vec4 getModifiedShadowColor(int color, float alpha) { return vec4(0.0); }\n";

    @Shadow
    private static Identifier includeShaderLocationToId(Identifier location) {
        throw new AssertionError();
    }

    @Inject(method = "loadInclude", at = @At("HEAD"), cancellable = true)
    private static void cedclient$vanillaColors(Identifier location, Resource resource,
                                                ImmutableMap.Builder<Identifier, ShaderSource.CachedIncludeSource> builder, CallbackInfo ci) {
        if (!location.getPath().endsWith("modify_vanilla_color.glsl") || !VanillaTextures.colorsActive()) return;
        Identifier id = includeShaderLocationToId(location);
        builder.put(id, ShaderSource.CachedIncludeSource.create(id, NO_OP));
        ci.cancel();
    }
}
