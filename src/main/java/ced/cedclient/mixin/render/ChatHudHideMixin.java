package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.Hiders;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hiders > Hide Chat: skips drawing the chat HUD. */
@Mixin(ChatComponent.class)
public class ChatHudHideMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void cedclient$hideChat(GuiGraphicsExtractor graphics, Font font, int tick, int mouseX, int mouseY,
                                    ChatComponent.DisplayMode mode, boolean focused, CallbackInfo ci) {
        if (Hiders.hideChatHud()) ci.cancel();
    }
}
