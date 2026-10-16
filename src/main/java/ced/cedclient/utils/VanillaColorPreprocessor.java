package ced.cedclient.utils;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;

/** Wraps the shader preprocessor so Hypixel's modify_vanilla_color.glsl import becomes a no-op (Vanilla Colors). */
public class VanillaColorPreprocessor extends GlslPreprocessor {
    private static final String NO_OP =
            "vec4 getModifiedVanillaColor(vec4 color) { return color; }\n" +
            "vec4 getModifiedNormalColor(int color, float alpha) { return vec4(0.0); }\n" +
            "vec4 getModifiedShadowColor(int color, float alpha) { return vec4(0.0); }\n";

    private final GlslPreprocessor wrapped;

    public VanillaColorPreprocessor(GlslPreprocessor wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public String applyImport(boolean relative, String path) {
        return !relative && path.endsWith("modify_vanilla_color.glsl") ? NO_OP : wrapped.applyImport(relative, path);
    }
}
