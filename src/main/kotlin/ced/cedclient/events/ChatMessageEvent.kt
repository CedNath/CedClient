package ced.cedclient.events

import ced.cedclient.events.core.Event

/**
 * Fired for every incoming system chat message (ClientboundSystemChatPacket),
 * regardless of whether ChatFilter ends up hiding it — see the priority note
 * on the mixin injection that posts this. Not cancellable: this is a
 * read-only tap for features (dungeon split detection, etc.) that need to
 * react to server messages, not a second place to hide/modify chat.
 *
 * [formattedText] keeps any legacy §-color codes the server embedded
 * directly in the message content (Hypixel sends most of its dungeon/event
 * messages this way, as literal text rather than rich Style). [unformattedText]
 * strips those codes for regex matching that shouldn't care about color.
 */
class ChatMessageEvent @JvmOverloads constructor(
    val formattedText: String,
    val unformattedText: String = stripFormatting(formattedText)
) : Event() {
    companion object {
        private val formatCodeRegex = Regex("§[0-9a-fk-or]")
        fun stripFormatting(text: String): String = formatCodeRegex.replace(text, "")
    }
}