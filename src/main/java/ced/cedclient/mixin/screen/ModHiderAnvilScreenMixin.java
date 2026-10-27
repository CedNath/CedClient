package ced.cedclient.mixin.screen;

import ced.cedclient.features.impl.misc.ModHider;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Mod Hider: the anvil screen's name box is read through ModHider. */
@Mixin(AnvilScreen.class)
public class ModHiderAnvilScreenMixin {
    @WrapOperation(method = "slotChanged", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;"))
    private String cedclient$slotChangedString(Component instance, Operation<String> original) {
        return ModHider.getString(instance);
    }

    @WrapOperation(method = "onNameChanged", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;"))
    private String cedclient$nameChangedString(Component instance, Operation<String> original) {
        return ModHider.getString(instance);
    }
}
