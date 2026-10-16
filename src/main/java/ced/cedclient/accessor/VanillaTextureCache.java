package ced.cedclient.accessor;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.ResolvableProfile;

/** Implemented on ItemStack by ItemStackVanillaTextureMixin: Vanilla Textures' resolved look, so it isn't recomputed every frame. */
public interface VanillaTextureCache {
    Identifier cedclient$vtModel();
    void cedclient$vtModel(Identifier model);

    ResolvableProfile cedclient$vtProfile();
    void cedclient$vtProfile(ResolvableProfile profile);

    /** VanillaTextures' generation the cached look belongs to (0 = never resolved). */
    int cedclient$vtGeneration();
    void cedclient$vtGeneration(int generation);
}
