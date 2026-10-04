package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.Fullbright;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Turns Fullbright's override off while options.txt is being written, so the boosted gamma is
 * never saved (otherwise vanilla brightness would stay maxed after disabling the module).
 *
 * require = 0 so a rename of save() can't crash the game at startup -- but if this doesn't
 * apply, check options.txt for a huge "gamma:" value.
 */
@Mixin(Options.class)
public abstract class OptionsMixin {

    @Inject(method = "save", at = @At("HEAD"), require = 0)
    private void cedclient$saveStart(CallbackInfo ci) {
        Fullbright.INSTANCE.setSuppress(true);
    }

    @Inject(method = "save", at = @At("RETURN"), require = 0)
    private void cedclient$saveEnd(CallbackInfo ci) {
        Fullbright.INSTANCE.setSuppress(false);
    }
}