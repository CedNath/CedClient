package ced.cedclient.mixin.screen;

import ced.cedclient.features.impl.misc.ModHider;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Mod Hider: the text a sign starts out with is read through ModHider. A wrap (not a redirect) so it can sit next to
 * another mod doing the same (NoammAddons redirects this call, and two redirects crash); require = 0 so that mod
 * winning the call just leaves its hider in charge.
 */
@Mixin(AbstractSignEditScreen.class)
public abstract class ModHiderSignEditMixin {
    @WrapOperation(method = "<init>(Lnet/minecraft/world/level/block/entity/SignBlockEntity;ZZLnet/minecraft/network/chat/Component;)V", at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;map(Ljava/util/function/Function;)Ljava/util/stream/Stream;"), require = 0)
    private Stream<String> cedclient$signText(Stream<Component> instance, Function<Component, String> function, Operation<Stream<String>> original) {
        return instance.map(ModHider::getString);
    }
}
