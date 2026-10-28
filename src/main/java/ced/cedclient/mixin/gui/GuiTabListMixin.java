package ced.cedclient.mixin.gui;

import ced.cedclient.features.impl.misc.TabListToggle;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Hud.class)
public abstract class GuiTabListMixin {

    // extractTabList's only isDown() call is the player list key, so this decides whether the tab list shows.
    @WrapOperation(method = "extractTabList", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean ced$tabListToggle(KeyMapping key, Operation<Boolean> original) {
        return TabListToggle.shown(original.call(key));
    }
}
