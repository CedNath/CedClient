package ced.cedclient.events

import ced.cedclient.events.core.CancellableEvent

/**
 * Fired for every title line the server sets (ClientboundSetTitleTextPacket).
 * The Rift Dance Room prints its prompts ("Punch!", ...) as titles/subtitles.
 * Cancel to drop the title (Sun Gecko hides its Hazard Zone title).
 */
class TitleEvent @JvmOverloads constructor(
    val formattedText: String,
    val unformattedText: String = ChatMessageEvent.stripFormatting(formattedText)
) : CancellableEvent()