package ced.cedclient.state

import ced.cedclient.chat.PlayerChatManager.NAME
import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.chat.PartyChatEvent
import ced.cedclient.events.core.on
import ced.cedclient.utils.PlayerUtils
import ced.cedclient.utils.cleanPlayerName

/**
 * Tracks who is in your Hypixel party from chat messages. Port of SkyHanni's
 * PartyApi. [partyMembers] never contains yourself, so party size is
 * `partyMembers.size + 1` while [isInParty].
 *
 * All patterns are anchored and use [NAME] for the player part, so a player
 * typing "Bob joined the party." in all chat can't fake a party event.
 */
object PartyApi {

    // You have joined [MVP+] Throwpo's party!
    private val youJoinedPattern = Regex("""You have joined (?<name>.*)'s? party!""")

    // [MVP+] Throwpo joined the party.
    private val othersJoinedPattern = Regex("""(?<name>${NAME}) joined the party\.""")

    // You'll be partying with: [VIP] FungalBeatle550, [MVP+] Other
    private val partyingWithPattern = Regex("""You'll be partying with: (?<names>.*)""")

    private val otherLeftPattern = Regex("""(?<name>${NAME}) has left the party\.""")
    private val otherKickedPattern = Regex("""(?<name>${NAME}) has been removed from the party\.""")
    private val otherOfflineKickedPattern = Regex("""Kicked (?<name>${NAME}) because they were offline\.""")
    private val otherDisconnectedPattern =
        Regex("""(?<name>${NAME}) was removed from your party because they disconnected\.""")

    // The party was transferred to [MVP+] CalMWolfs because [MVP+] Throwpo left
    private val transferOnLeavePattern =
        Regex("""The party was transferred to (?<newowner>${NAME}) because (?<name>${NAME}) left""")

    // The party was transferred to [MVP+] Throwpo by [MVP+] CalMWolfs
    private val transferVoluntaryPattern =
        Regex("""The party was transferred to (?<newowner>${NAME}) by (?<name>${NAME})""")

    private val disbandedPattern = Regex("""${NAME} has disbanded the party!""")
    private val kickedPattern = Regex("""You have been kicked from the party by .+""")

    // Party Members (2)
    private val partyListStartPattern = Regex("""Party Members \(\d+\)""")

    // Party Leader: [MVP+] CalMWolfs ●      Party Members: [MVP+] Throwpo ● [VIP] Foo ●
    private val partyListPattern = Regex("""Party (?<kind>Leader|Moderators|Members): (?<names>.*)""")

    // Party Finder > Name joined the group! (Combat Level 40)
    private val finderJoinPattern = Regex("""Party Finder > (?<name>${NAME}) joined the group! \(.*Level \d+\)""")

    // Party Finder > Name joined the dungeon group! (Archer Level 9)
    private val dungeonFinderJoinPattern =
        Regex("""Party Finder > (?<name>${NAME}) joined the dungeon group! \(.* Level \d+\)""")

    private val partyEndMessages = setOf(
        "You left the party.",
        "The party was disbanded because all invites expired and the party was empty.",
        "You are not currently in a party.",
        "You are not in a party.",
        "The party was disbanded because the party leader disconnected.",
    )

    val partyMembers = mutableListOf<String>()
    var partyLeader: String? = null
        private set
    var prevPartyLeader: String? = null
        private set

    fun isInParty() = partyMembers.isNotEmpty()

    fun init() {
        on<PartyChatEvent> { addPlayer(it.cleanAuthor) }
        on<ChatMessageEvent> { handleChat(it.unformattedText.trim()) }
    }

    private fun handleChat(message: String) {
        // --- joined ---
        youJoinedPattern.matchEntire(message)?.let { m ->
            val name = m.groups["name"]!!.value.cleanPlayerName()
            partyLeader = name
            addPlayer(name)
            return
        }
        othersJoinedPattern.matchEntire(message)?.let { m ->
            if (partyMembers.isEmpty()) partyLeader = PlayerUtils.getName()
            addPlayer(m.groups["name"]!!.value.cleanPlayerName())
            return
        }
        partyingWithPattern.matchEntire(message)?.let { m ->
            m.groups["names"]!!.value.split(", ").forEach { addPlayer(it.cleanPlayerName()) }
            return
        }
        finderJoinPattern.matchEntire(message)?.let { addPlayer(it.groups["name"]!!.value.cleanPlayerName()); return }
        dungeonFinderJoinPattern.matchEntire(message)?.let { addPlayer(it.groups["name"]!!.value.cleanPlayerName()); return }

        // --- one member removed ---
        for (pattern in listOf(otherLeftPattern, otherKickedPattern, otherOfflineKickedPattern)) {
            pattern.matchEntire(message)?.let { removeWithLeader(it.groups["name"]!!.value.cleanPlayerName()); return }
        }
        otherDisconnectedPattern.matchEntire(message)?.let {
            partyMembers.remove(it.groups["name"]!!.value.cleanPlayerName())
            return
        }
        transferOnLeavePattern.matchEntire(message)?.let { m ->
            partyLeader = m.groups["newowner"]!!.value.cleanPlayerName()
            partyMembers.remove(m.groups["name"]!!.value.cleanPlayerName())
            return
        }
        transferVoluntaryPattern.matchEntire(message)?.let { m ->
            partyLeader = m.groups["newowner"]!!.value.cleanPlayerName()
            prevPartyLeader = m.groups["name"]!!.value.cleanPlayerName()
            return
        }

        // --- party ended ---
        if (disbandedPattern.matches(message) || kickedPattern.matches(message) || message in partyEndMessages) {
            partyLeft()
            return
        }

        // --- /party list ---
        if (partyListStartPattern.matches(message)) {
            partyMembers.clear()
            return
        }
        partyListPattern.matchEntire(message)?.let { m ->
            val isLeader = m.groups["kind"]!!.value == "Leader"
            for (raw in m.groups["names"]!!.value.split("●")) {
                if (raw.isBlank()) continue
                val name = raw.cleanPlayerName()
                addPlayer(name)
                if (isLeader) partyLeader = name
            }
        }
    }

    private fun removeWithLeader(name: String) {
        partyMembers.remove(name)
        if (name == prevPartyLeader) prevPartyLeader = null
    }

    private fun addPlayer(name: String) {
        if (name.isEmpty() || partyMembers.contains(name)) return
        if (name == PlayerUtils.getName()) return
        partyMembers.add(name)
    }

    private fun partyLeft() {
        partyMembers.clear()
        partyLeader = null
        prevPartyLeader = null
    }
}