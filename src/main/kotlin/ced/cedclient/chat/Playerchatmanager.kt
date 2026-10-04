package ced.cedclient.chat

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.chat.CoopChatEvent
import ced.cedclient.events.chat.Direction
import ced.cedclient.events.chat.GuildChatEvent
import ced.cedclient.events.chat.PartyChatEvent
import ced.cedclient.events.chat.PrivateMessageChatEvent
import ced.cedclient.events.core.on

/**
 * Splits raw chat lines into typed, per-channel events so features never
 * regex the raw line themselves. Port of SkyHanni's PlayerChatManager.
 *
 * Works on [ChatMessageEvent.unformattedText] (color codes stripped), because
 * the packet's Component.getString() doesn't contain style colors. Every
 * pattern is anchored and the author must look like a real username, so a
 * player typing "Party > x: y" in all chat (which arrives as "Name: Party > ...")
 * can't spoof a channel.
 */
object PlayerChatManager {

    /** "[MVP+] Throwpo" or "Throwpo". */
    const val NAME = """(?:\[[^\]]+] )?\w{1,16}"""

    // Co-op > [MVP+] nea89o: hallooooo
    private val coopPattern = Regex("""Co-op > (?<author>${NAME}): (?<message>.*)""")

    // Party > [MVP+] lrg89: peee
    private val partyPattern = Regex("""Party > (?<author>${NAME}): (?<message>.*)""")

    // Guild > [MVP+] lrg89 [Iron]: h      Guild > ⚔ [MVP++] RealBacklight: !warp
    private val guildPattern = Regex(
        """Guild > (?<author>(?:\S+ )?${NAME})(?: \[(?<rank>[^\]]+)])?: (?<message>.*)"""
    )

    // To [MVP+] Eisengolem: Boop!      From nea89o: hiii      (not "From stash: Wheat")
    private val privateMessagePattern = Regex(
        """(?!From stash: )(?<direction>From|To) (?<author>${NAME}): (?<message>.*)"""
    )

    fun init() {
        on<ChatMessageEvent> { handle(it.unformattedText) }
    }

    private fun handle(text: String) {
        coopPattern.matchEntire(text)?.let { m ->
            CoopChatEvent(m.groups["author"]!!.value, m.groups["message"]!!.value, text).post()
            return
        }
        partyPattern.matchEntire(text)?.let { m ->
            PartyChatEvent(m.groups["author"]!!.value, m.groups["message"]!!.value, text).post()
            return
        }
        guildPattern.matchEntire(text)?.let { m ->
            GuildChatEvent(
                m.groups["author"]!!.value,
                m.groups["message"]!!.value,
                m.groups["rank"]?.value,
                text,
            ).post()
            return
        }
        privateMessagePattern.matchEntire(text)?.let { m ->
            PrivateMessageChatEvent(
                Direction.fromString(m.groups["direction"]!!.value),
                m.groups["author"]!!.value,
                m.groups["message"]!!.value,
                text,
            ).post()
            return
        }
    }
}