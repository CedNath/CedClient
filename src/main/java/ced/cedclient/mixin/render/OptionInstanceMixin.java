package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.Fullbright;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets Fullbright replace the value the game reads for the Brightness (gamma) option.
 * Only the gamma option is touched; every other OptionInstance passes straight through.
 */
@Mixin(OptionInstance.class)
public abstract class OptionInstanceMixin<T> {

    @SuppressWarnings("unchecked")
    @Inject(method = "get", at = @At("RETURN"), cancellable = true)
    private void cedclient$fullbright(CallbackInfoReturnable<T> cir) {
        Minecraft mc = Minecraft.getInstance();
        // options is still null while Options itself is being constructed
        if (mc == null || mc.options == null) return;
        if ((Object) this != mc.options.gamma()) return;

        Object vanilla = cir.getReturnValue();
        if (!(vanilla instanceof Double)) return;

        Double override = Fullbright.INSTANCE.gammaOverride((Double) vanilla);
        if (override != null) {
            cir.setReturnValue((T) override);
        }
    }
}