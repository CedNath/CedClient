package ced.cedclient.events

import ced.cedclient.events.core.Event

/**
 * Fired for every subtitle line the server sets (ClientboundSetSubtitleTextPacket).
 * Hypixel's Rift Dance Room announces its actions ("Move!", "Sneak!", ...) this way.
 * Read-only tap, same idea as ChatMessageEvent.
 */
class SubtitleEvent @JvmOverloads constructor(
    val formattedText: String,
    val unformattedText: String = ChatMessageEvent.stripFormatting(formattedText)
) : Event()