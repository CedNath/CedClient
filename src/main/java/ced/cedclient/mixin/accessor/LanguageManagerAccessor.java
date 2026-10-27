package ced.cedclient.mixin.accessor;

import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.server.packs.PackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;
import java.util.stream.Stream;

/** Mod Hider: builds the language of just the server's resource packs. */
@Mixin(LanguageManager.class)
public interface LanguageManagerAccessor {
    @Accessor("DEFAULT_LANGUAGE")
    static LanguageInfo ced$defaultLanguage() {
        throw new UnsupportedOperationException();
    }

    @Invoker("extractLanguages")
    static Map<String, LanguageInfo> ced$extractLanguages(Stream<PackResources> packs) {
        throw new UnsupportedOperationException();
    }
}
