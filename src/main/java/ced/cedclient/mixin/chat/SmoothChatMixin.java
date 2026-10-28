package ced.cedclient.mixin.chat;

import ced.cedclient.features.impl.misc.SmoothChat;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Smooth Chat: scales each line's alpha by how long ago it appeared. */
@Mixin(ChatComponent.class)
public abstract class SmoothChatMixin {

    @ModifyExpressionValue(
            method = "forEachLine",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent$AlphaCalculator;calculate(Lnet/minecraft/client/multiplayer/chat/GuiMessage$Line;)F")
    )
    private float cedclient$fadeIn(float alpha, @Local GuiMessage.Line line) {
        return alpha * SmoothChat.fadeIn(line);
    }
}
