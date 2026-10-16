package ced.cedclient.events

import ced.cedclient.events.core.CancellableEvent

/**
 * Fired for every subtitle line the server sets (ClientboundSetSubtitleTextPacket).
 * Hypixel's Rift Dance Room announces its actions ("Move!", "Sneak!", ...) this way.
 * Cancel to drop the subtitle (Sun Gecko hides its Hazard Zone "get out").
 */
class SubtitleEvent @JvmOverloads constructor(
    val formattedText: String,
    val unformattedText: String = ChatMessageEvent.stripFormatting(formattedText)
) : CancellableEvent()