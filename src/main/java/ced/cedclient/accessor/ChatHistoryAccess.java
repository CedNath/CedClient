package ced.cedclient.accessor;

import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Implemented on ChatComponent by ChatHistoryMixin (Chat Features module). */
public interface ChatHistoryAccess {
    /** Every stored chat message, newest first. */
    List<GuiMessage> cedclient$allMessages();

    /** The message drawn at GUI position [x, y] while chat is open, or null. */
    GuiMessage cedclient$messageAt(double x, double y);

    /** Adds [content] straight to the chat (no logging, no chat hooks), shown as [addedTime] old; [receivedAt] (epoch ms) is its timestamp. */
    void cedclient$insert(Component content, int addedTime, long receivedAt);
}
