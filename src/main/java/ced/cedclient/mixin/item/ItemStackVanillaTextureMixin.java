package ced.cedclient.mixin.item;

import ced.cedclient.accessor.VanillaTextureCache;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Per-stack cache for Vanilla Textures (see VanillaTextureCache). */
@Mixin(ItemStack.class)
public class ItemStackVanillaTextureMixin implements VanillaTextureCache {
    @Unique private Identifier cedclient$vtModel;
    @Unique private ResolvableProfile cedclient$vtProfile;
    @Unique private int cedclient$vtGeneration;

    @Override public Identifier cedclient$vtModel() { return cedclient$vtModel; }
    @Override public void cedclient$vtModel(Identifier model) { cedclient$vtModel = model; }
    @Override public ResolvableProfile cedclient$vtProfile() { return cedclient$vtProfile; }
    @Override public void cedclient$vtProfile(ResolvableProfile profile) { cedclient$vtProfile = profile; }
    @Override public int cedclient$vtGeneration() { return cedclient$vtGeneration; }
    @Override public void cedclient$vtGeneration(int generation) { cedclient$vtGeneration = generation; }
}
