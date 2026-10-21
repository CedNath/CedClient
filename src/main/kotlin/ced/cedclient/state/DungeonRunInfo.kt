package ced.cedclient.state

import ced.cedclient.utils.TabListCache
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel

/**
 * Run stats for the Dungeon Map's info lines, read from the tab list (secrets %, crypts, deaths, puzzles), the
 * sidebar ("Cleared: 85%") and chat (bat / mimic / prince, also the lines other mods or teammates post).
 *
 * [score] follows NoammAddons' ScoreTracker: skill (20 + 80 x done / total rooms, -10 per unsolved puzzle, -(2 x deaths - 1)) +
 * exploration (60 x done / total rooms + 40 x secrets / the floor's required %) + speed (time over the floor's limit) +
 * bonus (crypts up to 5, mimic 2, prince 1, bat 1). Starts over in every new world.
 */
object DungeonRunInfo {
    private val CODES = Regex("§.")
    private val SECRETS = Regex("""Secrets Found: ([\d.]+)%""")
    private val SECRETS_FOUND = Regex("""Secrets Found: (\d+)\s*$""")
    private val PARTY = Regex("""(?i)^Party > (?:\[[^]]+] )?(\w+): (.+)$""")
    private const val OWN_BAT = "a bat has been slain. +1 bonus score"
    private val CRYPTS = Regex("""Crypts: (\d+)""")
    private val DEATHS = Regex("""Deaths: \(?(\d+)\)?""")
    private val CLEARED = Regex("""Cleared: (\d+)%""")
    private val ROOMS = Regex("""Completed Rooms: (\d+)""")
    private val ELAPSED = Regex("""Time Elapsed: (?:(\d+)h )?(?:(\d+)m )?(?:(\d+)s)?""")
    private val BAT = Regex("""(?i)\bbat\b.*\b(dead|killed|died|down)\b""")
    private val MIMIC = Regex("""(?i)\bmimic\b.*\b(dead|killed|died|down)\b""")
    private val PRINCE = Regex("""(?i)(a prince falls|\bprince\b.*\b(dead|killed|died|down)\b)""")

    var secretsPct = 0.0; private set
    var crypts = 0; private set
    var deaths = 0; private set
    var clearedPct = 0; private set
    /** Puzzles in the tab list not solved yet (failed or still open). */
    var openPuzzles = 0; private set
    private var completedRooms = 0
    private var elapsed = 0
    private var bloodDone = false
    /** Who killed a bat: your own "A bat has been slain" line, or a teammate's "Bat dead" in party chat (one entry each). */
    private val batKillers = HashSet<String>()
    val batCount get() = batKillers.size
    val bat get() = batKillers.isNotEmpty()
    var secretsFound = 0; private set
    var mimic = false; private set
    var prince = false; private set

    private var level: ClientLevel? = null
    private var tabVersion = -1
    private var sidebarTick = 0

    fun register() {
        ClientReceiveMessageEvents.GAME.register { message, overlay -> if (!overlay) onChat(message.string.replace(CODES, "").trim()) }
        // Party chat (where other mods post "Mimic dead!") comes in as player chat.
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ -> onChat(message.string.replace(CODES, "").trim()) }
    }

    private fun onChat(plain: String) {
        if (!DungeonState.inDungeon) return
        resetIfNewWorld()
        val party = PARTY.matchEntire(plain)
        when {
            plain.lowercase() == OWN_BAT -> batKillers += "~self"
            party != null -> if (BAT.containsMatchIn(party.groupValues[2])) batKillers += party.groupValues[1].lowercase()
            BAT.containsMatchIn(plain) -> batKillers += "~other"
        }
        if (MIMIC.containsMatchIn(plain)) mimic = true
        if (PRINCE.containsMatchIn(plain)) prince = true
        if (plain == "[BOSS] The Watcher: You have proven yourself. You may pass.") bloodDone = true
    }

    private fun resetIfNewWorld() {
        val now = Minecraft.getInstance().level
        if (now === level) return
        level = now
        secretsPct = 0.0; crypts = 0; deaths = 0; clearedPct = 0; openPuzzles = 0
        completedRooms = 0; elapsed = 0; bloodDone = false
        batKillers.clear(); secretsFound = 0; mimic = false; prince = false
        tabVersion = -1
    }

    /** Re-reads the tab list (when it changed) and the sidebar; call from a tick. */
    fun update() {
        resetIfNewWorld()
        // The sidebar's numbers move at most once a second: read it every 10 ticks.
        if (sidebarTick++ % 10 == 0) {
            val lines = Sidebar.lines
            lines.firstNotNullOfOrNull { CLEARED.find(it) }?.let { clearedPct = it.groupValues[1].toInt() }
            lines.firstNotNullOfOrNull { ELAPSED.find(it) }?.let { m ->
                val (h, min, sec) = m.groupValues.drop(1).map { it.toIntOrNull() ?: 0 }
                elapsed = h * 3600 + min * 60 + sec
            }
        }
        if (TabListCache.version == tabVersion) return
        tabVersion = TabListCache.version
        var puzzles = 0
        for (e in TabListCache.entries) {
            val line = e.stripped
            SECRETS.find(line)?.let { secretsPct = it.groupValues[1].toDouble() }
            SECRETS_FOUND.find(line)?.let { secretsFound = it.groupValues[1].toInt() }
            ROOMS.find(line)?.let { completedRooms = it.groupValues[1].toInt() }
            CRYPTS.find(line)?.let { crypts = it.groupValues[1].toInt() }
            DEATHS.find(line)?.let { deaths = it.groupValues[1].toInt() }
            if ("[✖]" in line || "[✦]" in line) puzzles++
        }
        openPuzzles = puzzles
    }

    /** Total secrets in the dungeon: found / found %, else what the scanned rooms add up to. */
    val totalSecrets: Int
        get() = if (secretsPct > 0 && secretsFound > 0) Math.round(secretsFound * 100 / secretsPct).toInt()
            else (0..5).sumOf { i -> (0..5).sumOf { j -> DungeonScanner.room(i, j)?.secrets ?: 0 } }

    /** Secrets % the floor needs for full exploration score. */
    val requiredSecrets: Int
        get() {
            val floor = DungeonState.floor ?: return 100
            if (floor.startsWith("M")) return 100
            return when (floor.drop(1).toIntOrNull() ?: 0) {
                0, 1 -> 30
                2 -> 40
                3 -> 50
                4 -> 60
                5 -> 70
                6 -> 85
                else -> 100
            }
        }

    val score: Int
        get() {
            val total = if (clearedPct > 0 && completedRooms > 0) Math.floor(completedRooms / (clearedPct / 100.0) + 0.4) else 0.0
            // The boss room and the blood room count as done rooms the tab list doesn't include yet.
            val done = completedRooms + (if (!bloodDone) 1 else 0) + (if (!DungeonState.inBoss) 1 else 0)
            val ratio = if (total > 0) (done / total).coerceIn(0.0, 1.0) else clearedPct / 100.0
            return score(ratio, secretsPct, requiredSecrets, openPuzzles, deaths, crypts, mimic, prince, bat, speedScore())
        }

    /** Same maths as NoammAddons' ScoreTracker: [ratio] = completed rooms / total rooms. */
    fun score(ratio: Double, secrets: Double, required: Int, puzzles: Int, deaths: Int, crypts: Int, mimic: Boolean, prince: Boolean, bat: Boolean = false, speed: Int = 100): Int {
        val skill = (20 + Math.floor(ratio * 80).toInt() - 10 * puzzles - maxOf(0, 2 * deaths - 1)).coerceIn(20, 100)
        val explore = Math.floor(ratio * 60).toInt() + Math.floor(secrets / required * 40).coerceIn(0.0, 40.0).toInt()
        val bonus = minOf(5, crypts) + (if (mimic) 2 else 0) + (if (prince) 1 else 0) + (if (bat) 1 else 0)
        return skill + explore + speed + bonus
    }

    private val TIME_LIMITS = mapOf(
        "E" to 600, "F1" to 600, "F2" to 600, "F3" to 600, "F4" to 720, "F5" to 600, "F6" to 720, "F7" to 840,
        "M1" to 480, "M2" to 480, "M3" to 480, "M4" to 480, "M5" to 480, "M6" to 600, "M7" to 840,
    )

    private fun speedScore(): Int {
        val limit = TIME_LIMITS[DungeonState.floor] ?: 600
        if (elapsed <= limit) return 100
        // Deduction per percent over the limit: 1/2 for the first 20, 1/3.5 for the next 20, 1/4, 1/5, then 1/6.
        var over = (elapsed - limit) * 100f / limit
        var deduction = 0f
        for ((cap, div) in listOf(20f to 2f, 20f to 3.5f, 10f to 4f, 10f to 5f)) {
            if (over <= 0) break
            deduction += over.coerceAtMost(cap) / div
            over -= cap
        }
        if (over > 0) deduction += over / 6f
        return (100 - deduction).toInt().coerceAtLeast(0)
    }

    init {
        // Perfect F7 clear: 100 + 100 + 100 + 5 crypts + mimic + prince + bat = 309 (Noam counts the bat too).
        check(score(1.0, 100.0, 100, 0, 0, 5, mimic = true, prince = true, bat = true) == 309)
        // Nothing done yet: 20 skill + 0 explore + 100 speed.
        check(score(0.0, 0.0, 30, 0, 0, 0, mimic = false, prince = false) == 120)
    }
}
