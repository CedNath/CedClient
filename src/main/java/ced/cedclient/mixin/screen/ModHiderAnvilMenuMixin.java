package ced.cedclient.mixin.screen;

import ced.cedclient.features.impl.misc.ModHider;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AnvilMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Mod Hider: the anvil rename text is read through ModHider so a translation key cannot reveal a mod. */
@Mixin(AnvilMenu.class)
public class ModHiderAnvilMenuMixin {
    @WrapOperation(method = "createResult", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;"))
    private String cedclient$hiderString(Component instance, Operation<String> original) {
        return ModHider.getString(instance);
    }
}
