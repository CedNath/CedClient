package ced.cedclient.mixin.accessor;

import net.minecraft.locale.Language;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Mod Hider: vanilla's language, without anything a mod added. */
@Mixin(Language.class)
public interface LanguageAccessor {
    @Invoker("loadDefault")
    static Language ced$loadDefault() {
        throw new UnsupportedOperationException();
    }
}
