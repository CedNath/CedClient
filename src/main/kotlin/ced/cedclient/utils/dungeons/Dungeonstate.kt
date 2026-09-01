package ced.cedclient.utils.dungeons

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.utils.Debug
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.scores.DisplaySlot

/**
 * Dungeon presence/floor detection. Two independent signals, kept separate
 * on purpose:
 *
 *  - inDungeon flips on Mort's opening chat line (reliable, seen at the very
 *    start of every run) and flips back off the moment the ClientLevel
 *    instance changes — Hypixel always swaps you to a different world
 *    instance leaving a dungeon (back to the hub, or into the next one),
 *    so this doesn't depend on guessing an exact "you left" message.
 *
 *  - floor is read from the party's "entered The Catacombs, Floor VII!"
 *    chat announcement, seen in the hub BEFORE the level even swaps --
 *    confirmed from a live log dump, so this replaced the original
 *    scoreboard-sidebar plan entirely for normal floors. Master Mode's
 *    exact wording is NOT yet confirmed (this was tested on a normal
 *    floor) -- parseFloorLabel() logs any label it doesn't recognize so
 *    that can get filled in from a real M-mode run. The scoreboard method
 *    is kept as a fallback in case that chat line is ever missed.
 */
object DungeonState {
    private val mc get() = Minecraft.getInstance()

    // Confirmed via live log: Hypixel embeds raw §-codes as literal
    // characters in this message's text content, so ChatMessageEvent's
    // formattedText carries them and unformattedText (stripped) is what
    // actually matches this constant. Comparing formattedText here was the
    // original bug -- inDungeon never flipped because of it.
    private const val DUNGEON_START_MESSAGE =
        "[NPC] Mort: Here, I found this map when I first entered the dungeon."

    // Confirmed via live log for a normal floor: "<player> entered The
    // Catacombs, Floor VII!". Captures whatever's between "Floor " and "!"
    // so parseFloorLabel() can handle both roman-numeral normal floors and
    // (once confirmed) whatever Master Mode's format turns out to be.
    private val floorAnnounceRegex = Regex("""entered The Catacombs, Floor (?<label>[^!]+)!""")

    // Fallback only -- NOT verified against a live server. See
    // readSidebarLines() below; only used if the chat announcement above is
    // ever missed.
    private val floorLineRegex = Regex("""Floor:?\s*(?<floor>M?\d{1,2})""")

    var inDungeon: Boolean = false
        private set

    var floor: String? = null
        private set

    private var lastLevel: ClientLevel? = null

    fun init() {
        on<ChatMessageEvent> { event ->
            if (Debug.enabled) Debug.log("[DungeonState] chat: '${event.unformattedText}'", interval = 1)

            if (event.unformattedText.trim() == DUNGEON_START_MESSAGE) {
                inDungeon = true
                if (Debug.enabled) Debug.log("[DungeonState] inDungeon -> true (matched Mort's line)", interval = 1)
            }

            val floorMatch = floorAnnounceRegex.find(event.unformattedText)
            if (floorMatch != null) {
                val label = floorMatch.groups["label"]!!.value
                val parsed = parseFloorLabel(label)
                if (parsed != null && parsed != floor) {
                    floor = parsed
                    if (Debug.enabled) Debug.log("[DungeonState] floor -> $floor (from chat: '$label')", interval = 1)
                }
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            tick()
        }
    }

    /**
     * "VII" -> "F7", "M7" -> "M7" (already in our shape). Anything else gets
     * logged instead of guessed at -- send me that log line and I'll add the
     * real Master Mode pattern.
     */
    private fun parseFloorLabel(rawLabel: String): String? {
        val trimmed = rawLabel.trim()

        val masterMatch = Regex("""^M(?<num>\d{1,2})$""").matchEntire(trimmed)
        if (masterMatch != null) return "M${masterMatch.groups["num"]!!.value}"

        val romanMatch = Regex("""^[IVXLCDM]+$""").matchEntire(trimmed)
        if (romanMatch != null) {
            val num = romanToInt(trimmed)
            if (num != null) return "F$num"
        }

        if (Debug.enabled) Debug.log("[DungeonState] unrecognized floor label: '$trimmed' -- please report this", interval = 1)
        return null
    }

    private fun romanToInt(roman: String): Int? {
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var result = 0
        var prev = 0
        for (c in roman.reversed()) {
            val v = values[c] ?: return null
            result += if (v < prev) -v else v
            prev = v
        }
        return result.takeIf { it > 0 }
    }

    private fun tick() {
        val currentLevel = mc.level

        if (currentLevel !== lastLevel) {
            lastLevel = currentLevel
            if (inDungeon) {
                // Level swapped out from under us -- run's over (success, fail,
                // or a disconnect), one way or another we're not in that
                // dungeon instance anymore.
                inDungeon = false
                floor = null
                if (Debug.enabled) Debug.log("[DungeonState] inDungeon -> false (level changed)", interval = 1)
            }
        }

        if (!inDungeon || floor != null) return

        // Fallback path only -- the chat announcement above should have
        // already set floor well before this would ever run.
        val line = readSidebarLines().firstNotNullOfOrNull { floorLineRegex.find(it) }
        val newFloor = line?.groups?.get("floor")?.value
        if (newFloor != null) {
            floor = newFloor
            if (Debug.enabled) Debug.log("[DungeonState] floor -> $floor (scoreboard fallback)", interval = 1)
        }
    }

    /**
     * Raw, unformatted sidebar lines top-to-bottom. NOTE: verify this against
     * the decompiled Scoreboard/Objective/ScoreHolder classes in IntelliJ
     * before relying on it -- vanilla's scoreboard API shifted materially
     * around 1.20.3 (score-holder-based rewrite) and may have moved again by
     * 1.21.11. If this doesn't compile as-is, Navigate > Declaration on
     * Scoreboard from here and adjust the method names below.
     */
    private fun readSidebarLines(): List<String> {
        val scoreboard = mc.level?.scoreboard ?: return emptyList()
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return emptyList()

        // listPlayerScores() already comes back in display order (that's the
        // whole point of the 1.20.3+ PlayerScoreEntry rewrite -- the packet
        // itself carries display order, so no client-side sort needed).
        val lines = scoreboard.listPlayerScores(objective)
            .mapNotNull { entry ->
                val team = scoreboard.getPlayersTeam(entry.owner())
                val prefix = team?.playerPrefix?.string ?: ""
                val suffix = team?.playerSuffix?.string ?: ""
                (prefix + suffix).ifBlank { null }
            }

        if (Debug.enabled) Debug.log("[DungeonState] sidebar: $lines", interval = 20)

        return lines
    }
}