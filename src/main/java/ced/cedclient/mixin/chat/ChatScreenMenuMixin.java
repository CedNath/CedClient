package ced.cedclient.mixin.chat;

import ced.cedclient.features.impl.misc.ChatFeatures;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chat Features > Message Clicks: right-click a chat message for copy / reply options, Ctrl + left-click to copy it (ChatFeatures.Menu).
 * Priority below the default 1000 so this HEAD hook runs before other mods' chat clicks (BetterPV opens /pv on names).
 */
@Mixin(value = ChatScreen.class, priority = 500)
public class ChatScreenMenuMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void cedclient$menuClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (ChatFeatures.Menu.onClick((ChatScreen) (Object) this, event.x(), event.y(), event.button(), event.hasControlDown())) cir.setReturnValue(true);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void cedclient$menuRender(GuiGraphicsExtractor g, int mouseX, int mouseY, float a, CallbackInfo ci) {
        ChatFeatures.Menu.render(g, mouseX, mouseY);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void cedclient$menuClose(CallbackInfo ci) {
        ChatFeatures.Menu.close();
    }
}
