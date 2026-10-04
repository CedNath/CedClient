package ced.cedclient.events

import ced.cedclient.events.core.Event

/**
 * Fired for every title line the server sets (ClientboundSetTitleTextPacket).
 * The Rift Dance Room prints its prompts ("Punch!", ...) as titles/subtitles.
 * Read-only tap, same idea as SubtitleEvent / ChatMessageEvent.
 */
class TitleEvent @JvmOverloads constructor(
    val formattedText: String,
    val unformattedText: String = ChatMessageEvent.stripFormatting(formattedText)
) : Event()