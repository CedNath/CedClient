package ced.cedclient.mixin.client;

import ced.cedclient.features.impl.misc.TexturePackFix;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Texture Pack Fix. */
@Mixin(Pack.class)
public class PackCompatibilityMixin {

    @ModifyReturnValue(method = "getCompatibility", at = @At("RETURN"))
    private PackCompatibility cedclient$alwaysCompatible(PackCompatibility original) {
        return TexturePackFix.INSTANCE.isEnabled() ? PackCompatibility.COMPATIBLE : original;
    }
}
