package ced.cedclient.mixin.chat;

import ced.cedclient.features.impl.misc.ChatFeatures;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Chat Features > Keep Chat On Disconnect: leaving a server no longer wipes the chat. */
@Mixin(Hud.class)
public class GuiDisconnectMixin {

    @Redirect(method = "onDisconnected", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;clearMessages(Z)V"))
    private void cedclient$keepChat(ChatComponent chat, boolean clearSentMsgHistory) {
        ChatFeatures.onDisconnect();
        if (!ChatFeatures.keepOnDisconnect()) chat.clearMessages(clearSentMsgHistory);
    }
}
