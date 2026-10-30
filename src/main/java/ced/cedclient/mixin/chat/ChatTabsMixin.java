package ced.cedclient.mixin.chat;

import ced.cedclient.features.impl.misc.ChatTabs;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chat Tabs: the tab strip above the input, clicks on it, and the channel command put in front of what you send. */
@Mixin(value = ChatScreen.class, priority = 500)
public class ChatTabsMixin {
    @Shadow protected EditBox input;

    // A "/" command shows its suggestion list right where the strip is, so the strip steps aside.
    private boolean cedclient$commandTyping() {
        return input != null && input.getValue().startsWith("/");
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void cedclient$tabClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (!cedclient$commandTyping() && ChatTabs.onClick(event.x(), event.y(), ced.cedclient.utils.Keys.mouse(event))) cir.setReturnValue(true);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void cedclient$tabRender(GuiGraphicsExtractor g, int mouseX, int mouseY, float a, CallbackInfo ci) {
        if (!cedclient$commandTyping()) ChatTabs.render(g, mouseX, mouseY);
    }

    @ModifyVariable(method = "handleChatInput", at = @At("HEAD"), argsOnly = true)
    private String cedclient$tabChannel(String text) {
        return ChatTabs.rewrite(text);
    }
}
