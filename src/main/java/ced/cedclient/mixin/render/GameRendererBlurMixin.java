package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.misc.Theme;
import ced.cedclient.ui.clickgui.ClickGUI;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While the CedClient menu is open its blur uses Mod Settings > Blur Strength instead of the player's menu blur
 * option (the frame's blur radius is copied from the options here, so the option itself is never changed).
 */
@Mixin(GameRenderer.class)
public class GameRendererBlurMixin {
    @ModifyExpressionValue(method = "extractOptions", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getMenuBackgroundBlurriness()I"))
    private int cedclient$clickGuiBlur(int original) {
        if (Minecraft.getInstance().gui.screen() instanceof ClickGUI gui && Theme.INSTANCE.getMenuBlur().getValue()) return gui.blurRadius();
        return original;
    }
}
