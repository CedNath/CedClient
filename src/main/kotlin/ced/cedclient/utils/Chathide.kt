package ced.cedclient.utils

/**
 * Hand-off between ClientPacketListenerMixin (decides a system-chat line should be hidden) and
 * ChatComponentMixin (actually drops it at display time).
 *
 * The line is deliberately NOT cancelled at the top of handleSystemChat any more: cancelling
 * there stops every other mod that also hooks that method (e.g. NoammAddons' chat-based door
 * key / chest detection) from ever seeing the message. Instead the packet is allowed to run
 * its normal course and only the final "add this line to the chat window" step is skipped.
 *
 * Everything runs on the client thread, so a plain flag is enough. It is always cleared at the
 * end of handleSystemChat so it can never leak onto a later message.
 */
object ChatHide {
    @JvmField
    @Volatile
    var pending: Boolean = false
}