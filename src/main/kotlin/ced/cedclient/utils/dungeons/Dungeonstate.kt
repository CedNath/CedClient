package ced.cedclient.utils.dungeons

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.utils.Debug
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.scores.DisplaySlot
import kotlin.math.floor

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

    enum class DungeonClass { HEALER, MAGE, BERSERK, ARCHER, TANK }

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

    // Confirmed via live log: "You have selected the Mage Dungeon Class!" /
    // "...the Healer Dungeon Class!" -- sent the moment you pick your class in
    // the dungeon queue/hub, which is BEFORE inDungeon flips true (that only
    // happens later, on Mort's map message). This is the primary/authoritative
    // source for playerClass now -- unlike floor, there's no need for a
    // sidebar fallback here since this message is confirmed reliable.
    private val classSelectRegex =
        Regex("""You have selected the (?<name>Healer|Mage|Berserk|Archer|Tank) Dungeon Class!""")

    // Level-only -- your own tab list entry shows "(Mage XLVII)" etc. next to
    // your name (confirmed via screenshot -- roman numeral, not arabic
    // digits, readable both in the dungeon hub and mid-run). playerClass
    // itself comes from classSelectRegex above; this fills in the level that
    // message doesn't carry. Hypixel builds tab list entries the same
    // team-prefix/suffix way as the sidebar (see readSidebarLines()), so this
    // reads your own team's suffix rather than needing a new mixin/hook.
    // NOTE: playerClassLevel is now informational only -- it's no longer
    // needed for the Mage cooldown multiplier below, which reads the actual
    // applied percentage straight from chat instead of estimating it from
    // level. Kept around in case it's useful for something else later.
    private val classLevelTagRegex =
        Regex("""\((?<name>Healer|Mage|Berserk|Archer|Tank) (?<level>[IVXLCDM]+)\)""", RegexOption.IGNORE_CASE)

    // Confirmed via live log: "Your Mage stats are doubled because you are
    // the only player using this class!" -- sent once at dungeon start if
    // you're the only one playing your class. Far more reliable than trying
    // to infer this from the tab list (would need every party member's
    // class), and it's literally the exact condition that matters.
    private val uniqueClassRegex =
        Regex("""Your (?:Healer|Mage|Berserk|Archer|Tank) stats are doubled because you are the only player using this class!""")

    // Confirmed via live log: "[Mage] Cooldown Reduction 48% -> 73%" (the
    // "-> 73%" being the boosted value from the unique-class doubling above;
    // without that bonus it'd presumably read as a single percentage with no
    // arrow, e.g. "[Mage] Cooldown Reduction 48%" -- this regex handles both
    // by just taking whichever percent number appears last on the line, so it
    // works either way). This is the actual, exact reduction Hypixel is
    // already applying to your abilities -- reading it directly means the
    // cooldown multiplier no longer needs to approximate anything from class
    // level at all.
    private val cooldownReductionRegex = Regex("""\[Mage] Cooldown Reduction.*?(?<percent>\d+)%\s*$""")

    var inDungeon: Boolean = false
        private set

    var floor: String? = null
        private set

    var playerClass: DungeonClass? = null
        private set

    var playerClassLevel: Int = 0
        private set

    var isUniqueClass: Boolean = false
        private set

    // The exact Mage cooldown-reduction percentage as reported by Hypixel
    // itself (see cooldownReductionRegex above), e.g. 73 for "73%". Null
    // until that chat line has been seen this dungeon -- mageCooldownMultiplier
    // falls back to an estimate from class level/uniqueness if it's still null.
    var mageCooldownReductionPercent: Int? = null
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

            val classMatch = classSelectRegex.find(event.unformattedText)
            if (classMatch != null) {
                val name = classMatch.groups["name"]!!.value
                playerClass = runCatching { DungeonClass.valueOf(name.uppercase()) }.getOrNull()
                playerClassLevel = 0 // level isn't in this message -- picked up separately in tick(), see classLevelTagRegex
                isUniqueClass = false // reset -- reselecting a class means we haven't seen this run's stat messages yet
                mageCooldownReductionPercent = null
                if (Debug.enabled) Debug.log("[DungeonState] class -> $playerClass (from chat: '$name')", interval = 1)
            }

            if (uniqueClassRegex.matches(event.unformattedText.trim())) {
                isUniqueClass = true
                if (Debug.enabled) Debug.log("[DungeonState] isUniqueClass -> true (from chat)", interval = 1)
            }

            val reductionMatch = cooldownReductionRegex.find(event.unformattedText)
            if (reductionMatch != null) {
                val percent = reductionMatch.groups["percent"]?.value?.toIntOrNull()
                if (percent != null) {
                    mageCooldownReductionPercent = percent
                    if (Debug.enabled) Debug.log("[DungeonState] mageCooldownReductionPercent -> $percent% (from chat)", interval = 1)
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
                playerClass = null
                playerClassLevel = 0
                isUniqueClass = false
                mageCooldownReductionPercent = null
                if (Debug.enabled) Debug.log("[DungeonState] inDungeon -> false (level changed)", interval = 1)
            }
        }

        // Unlike floor, this doesn't wait on inDungeon -- the "(Mage XLVII)"
        // tag next to your own name is visible in the tab list in the
        // dungeon hub too, before Mort's map message ever fires.
        if (playerClass != null && playerClassLevel == 0) {
            val tagMatch = readOwnTabListSuffix()?.let { classLevelTagRegex.find(it) }
            val roman = tagMatch?.groups?.get("level")?.value
            val level = roman?.let { romanToInt(it) }
            if (level != null) {
                playerClassLevel = level
                if (Debug.enabled) Debug.log("[DungeonState] classLevel -> $playerClassLevel (from tab list: '$roman')", interval = 1)
            }
        }

        if (!inDungeon) return

        if (floor == null) {
            // Fallback path only -- the chat announcement above should have
            // already set floor well before this would ever run.
            val line = readSidebarLines().firstNotNullOfOrNull { floorLineRegex.find(it) }
            val newFloor = line?.groups?.get("floor")?.value
            if (newFloor != null) {
                floor = newFloor
                if (Debug.enabled) Debug.log("[DungeonState] floor -> $floor (scoreboard fallback)", interval = 1)
            }
        }
    }

    /**
     * Your own tab list team suffix -- Hypixel builds tab list entries the
     * same team-prefix/suffix way as the sidebar (see readSidebarLines()),
     * so the "(Mage XLVII)" tag should live in here for your own entry.
     * NOTE: verify `Player.scoreboardName` is still the right accessor for
     * your MC version -- this is meant to be the same lookup key
     * scoreboard.getPlayersTeam(...) expects, matching how readSidebarLines()
     * already looks up teams by ScoreHolder name below.
     */
    private fun readOwnTabListSuffix(): String? {
        val player = mc.player ?: return null
        val scoreboard = mc.level?.scoreboard ?: return null
        val team = scoreboard.getPlayersTeam(player.scoreboardName) ?: return null
        return team.playerSuffix.string.ifBlank { null }
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

    /**
     * Hypixel's Mage class reduces ability cooldowns while active. Prefers
     * the exact percentage Hypixel itself reports in chat
     * ([mageCooldownReductionPercent], confirmed via live log: "[Mage]
     * Cooldown Reduction 48% -> 73%") over estimating it -- only falls back
     * to SkyHanni's DungeonAPI formula (25% base reduction, 50% if unique,
     * plus 1% more per 2 class levels) if that chat line hasn't been seen
     * yet this dungeon. Returns 1.0 (no change) outside a dungeon, off the
     * Mage class, or for abilities that opt out via
     * [ignoreMageCooldownReduction] (e.g. Hyperion-family blades, which
     * Hypixel doesn't reduce).
     */
    fun mageCooldownMultiplier(ignoreMageCooldownReduction: Boolean): Double {
        if (ignoreMageCooldownReduction) return 1.0
        if (!inDungeon) return 1.0
        if (playerClass != DungeonClass.MAGE) return 1.0

        val exactPercent = mageCooldownReductionPercent
        if (exactPercent != null) {
            return (1.0 - exactPercent / 100.0).coerceAtLeast(0.0)
        }

        var multiplier = 1.0
        multiplier -= if (isUniqueClass) 0.5 else 0.25
        multiplier -= 0.01 * floor(playerClassLevel / 2f)
        return multiplier.coerceAtLeast(0.0)
    }
}