package ced.cedclient.events.chat

import ced.cedclient.events.core.Event
import ced.cedclient.utils.cleanPlayerName

/**
 * A chat line that has a known sender and channel, split out of the raw
 * [ced.cedclient.events.ChatMessageEvent] by [ced.cedclient.chat.PlayerChatManager].
 * Read-only taps, like ChatMessageEvent. The EventBus dispatches on the exact
 * class, so listen to the concrete events below, not this base class.
 *
 * @param author sender as shown in chat, e.g. "[MVP+] lrg89"
 * @param message the message body
 * @param rawText the whole line (color codes stripped)
 */
abstract class AbstractSourcedChatEvent(
    val author: String,
    val message: String,
    val rawText: String,
) : Event() {
    /** Bare username: no rank, no symbols. */
    val cleanAuthor: String get() = author.cleanPlayerName()
}

class PartyChatEvent(author: String, message: String, rawText: String) :
    AbstractSourcedChatEvent(author, message, rawText)

class CoopChatEvent(author: String, message: String, rawText: String) :
    AbstractSourcedChatEvent(author, message, rawText)

class GuildChatEvent(author: String, message: String, val guildRank: String?, rawText: String) :
    AbstractSourcedChatEvent(author, message, rawText)

/** [author] is the other player: the sender when INCOMING, the recipient when OUTGOING. */
class PrivateMessageChatEvent(val direction: Direction, author: String, message: String, rawText: String) :
    AbstractSourcedChatEvent(author, message, rawText)

enum class Direction(val text: String) {
    OUTGOING("To"),
    INCOMING("From"),
    ;

    companion object {
        fun fromString(string: String): Direction =
            entries.firstOrNull { it.text == string } ?: error("Invalid direction string: $string")
    }
}