package ced.cedclient.mixin.chat;

import ced.cedclient.accessor.ChatHistoryAccess;
import ced.cedclient.features.impl.misc.ChatFeatures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Chat Features: Max Chat Lines instead of the 100-message limit, plus access for saving / restoring
 * messages and finding the message under the mouse (the hit test mirrors ChatComponent's own line layout).
 */
@Mixin(ChatComponent.class)
public abstract class ChatHistoryMixin implements ChatHistoryAccess {

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private List<GuiMessage> allMessages;
    @Shadow @Final private List<GuiMessage.Line> trimmedMessages;
    @Shadow private int chatScrollbarPos;

    @Shadow protected abstract double getScale();
    @Shadow protected abstract int getWidth();
    @Shadow public abstract int getLinesPerPage();
    @Shadow protected abstract void addMessageToDisplayQueue(GuiMessage message);
    @Shadow protected abstract void addMessageToQueue(GuiMessage message);

    @Shadow private boolean newMessageSinceScroll;
    @Shadow public abstract boolean isChatFocused();
    @Shadow public abstract void scrollChat(int amount);

    // Max Chat Lines: these two run in place of vanilla's (same steps, our limit instead of 100) rather than changing
    // the 100 itself -- other mods modify that constant, and two mods modifying it crashes the game.

    @Inject(method = "addMessageToDisplayQueue", at = @At("HEAD"), cancellable = true)
    private void cedclient$displayQueue(GuiMessage message, CallbackInfo ci) {
        if (!ChatFeatures.extendsHistory()) return;
        ci.cancel();
        int maxWidth = Mth.floor(getWidth() / getScale());
        // Timestamps go on the drawn lines only; Line.parent() stays the original message, so copy / sender skip them.
        Component shown = ChatFeatures.withTimestamp(message);
        List<FormattedCharSequence> lines = new GuiMessage(message.addedTime(), shown, message.signature(), message.source(), message.tag())
                .splitLines(minecraft.font, maxWidth);
        boolean chatting = isChatFocused();
        for (int i = 0; i < lines.size(); i++) {
            if (chatting && chatScrollbarPos > 0) {
                newMessageSinceScroll = true;
                scrollChat(1);
            }
            trimmedMessages.addFirst(new GuiMessage.Line(message, lines.get(i), i == lines.size() - 1));
        }
        int limit = ChatFeatures.maxLines(100);
        while (trimmedMessages.size() > limit) trimmedMessages.removeLast();
    }

    @Inject(method = "addMessageToQueue", at = @At("HEAD"), cancellable = true)
    private void cedclient$queue(GuiMessage message, CallbackInfo ci) {
        if (!ChatFeatures.extendsHistory()) return;
        ci.cancel();
        allMessages.addFirst(message);
        int limit = ChatFeatures.maxLines(100);
        while (allMessages.size() > limit) allMessages.removeLast();
    }

    @Unique private float cedclient$drawOffsetX;
    @Unique private float cedclient$drawOffsetY;

    /** Where the open chat is really drawn: its pose translation when ChatScreen renders it. */
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V", at = @At("HEAD"))
    private void cedclient$recordOffset(GuiGraphicsExtractor graphics, Font font, int ticks, int mouseX, int mouseY, ChatComponent.DisplayMode mode, boolean changeCursor, CallbackInfo ci) {
        if (!mode.foreground) return;
        cedclient$drawOffsetX = graphics.pose().m20();
        cedclient$drawOffsetY = graphics.pose().m21();
    }

    @Override
    public List<GuiMessage> cedclient$allMessages() {
        return allMessages;
    }

    @Override
    public GuiMessage cedclient$messageAt(double x, double y) {
        // Undo any shift other mods put on the open chat (e.g. a search bar pushing it up a line).
        x -= cedclient$drawOffsetX;
        y -= cedclient$drawOffsetY;
        float scale = (float) getScale();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int chatBottom = Mth.floor((screenHeight - 40) / scale);
        double spacing = minecraft.options.chatLineSpacing().get();
        int entryHeight = (int) (9 * (spacing + 1.0));
        double localX = x / scale - 4;
        double localY = y / scale;
        int maxWidth = Mth.ceil(getWidth() / scale);
        if (localX < -4 || localX > maxWidth + 8 || entryHeight <= 0) return null;
        int index = Mth.floor((chatBottom - localY) / entryHeight);
        int visible = Math.min(trimmedMessages.size() - chatScrollbarPos, getLinesPerPage());
        if (index < 0 || index >= visible) return null;
        return trimmedMessages.get(index + chatScrollbarPos).parent();
    }

    @Override
    public void cedclient$insert(Component content, int addedTime, long receivedAt) {
        GuiMessage message = new GuiMessage(addedTime, content, null, GuiMessageSource.SYSTEM_CLIENT, null);
        ChatFeatures.stamp(message, receivedAt);
        addMessageToDisplayQueue(message);
        addMessageToQueue(message);
    }
}
