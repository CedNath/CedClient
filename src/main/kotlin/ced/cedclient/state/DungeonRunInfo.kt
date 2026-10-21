package ced.cedclient.state

import ced.cedclient.utils.TabListCache
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel

/**
 * Run stats for the Dungeon Map's info lines, read from the tab list (secrets %, crypts, deaths, puzzles), the
 * sidebar ("Cleared: 85%") and chat (bat / mimic / prince, also the lines other mods or teammates post).
 *
 * [score] is an estimate of the current score: skill (20 + 80 x cleared, -10 per unsolved puzzle, -2 per death) +
 * exploration (60 x cleared + 40 x secrets / the floor's required %) + speed (assumed 100) + bonus (crypts up to 5,
 * mimic 2, prince 1). Starts over in every new world.
 */
object DungeonRunInfo {
    private val CODES = Regex("§.")
    private val SECRETS = Regex("""Secrets Found: ([\d.]+)%""")
    private val CRYPTS = Regex("""Crypts: (\d+)""")
    private val DEATHS = Regex("""Deaths: \(?(\d+)\)?""")
    private val CLEARED = Regex("""Cleared: (\d+)%""")
    private val BAT = Regex("""(?i)\bbat\b.*\b(dead|killed|died|down)\b""")
    private val MIMIC = Regex("""(?i)\bmimic\b.*\b(dead|killed|died|down)\b""")
    private val PRINCE = Regex("""(?i)(a prince falls|\bprince\b.*\b(dead|killed|died|down)\b)""")

    var secretsPct = 0.0; private set
    var crypts = 0; private set
    var deaths = 0; private set
    var clearedPct = 0; private set
    /** Puzzles in the tab list not solved yet (failed or still open). */
    var openPuzzles = 0; private set
    var bat = false; private set
    var mimic = false; private set
    var prince = false; private set

    private var level: ClientLevel? = null
    private var tabVersion = -1

    fun register() {
        ClientReceiveMessageEvents.GAME.register { message, overlay -> if (!overlay) onChat(message.string.replace(CODES, "").trim()) }
        // Party chat (where other mods post "Mimic dead!") comes in as player chat.
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ -> onChat(message.string.replace(CODES, "").trim()) }
    }

    private fun onChat(plain: String) {
        if (!DungeonState.inDungeon) return
        resetIfNewWorld()
        if (BAT.containsMatchIn(plain)) bat = true
        if (MIMIC.containsMatchIn(plain)) mimic = true
        if (PRINCE.containsMatchIn(plain)) prince = true
    }

    private fun resetIfNewWorld() {
        val now = Minecraft.getInstance().level
        if (now === level) return
        level = now
        secretsPct = 0.0; crypts = 0; deaths = 0; clearedPct = 0; openPuzzles = 0
        bat = false; mimic = false; prince = false
        tabVersion = -1
    }

    /** Re-reads the tab list (when it changed) and the sidebar; call from a tick. */
    fun update() {
        resetIfNewWorld()
        Sidebar.lines.firstNotNullOfOrNull { CLEARED.find(it) }?.let { clearedPct = it.groupValues[1].toInt() }
        if (TabListCache.version == tabVersion) return
        tabVersion = TabListCache.version
        var puzzles = 0
        for (e in TabListCache.entries) {
            val line = e.stripped
            SECRETS.find(line)?.let { secretsPct = it.groupValues[1].toDouble() }
            CRYPTS.find(line)?.let { crypts = it.groupValues[1].toInt() }
            DEATHS.find(line)?.let { deaths = it.groupValues[1].toInt() }
            if ("[✖]" in line || "[✦]" in line) puzzles++
        }
        openPuzzles = puzzles
    }

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
        get() = score(clearedPct, secretsPct, requiredSecrets, openPuzzles, deaths, crypts, mimic, prince)

    fun score(cleared: Int, secrets: Double, required: Int, puzzles: Int, deaths: Int, crypts: Int, mimic: Boolean, prince: Boolean): Int {
        val skill = (20 + 80 * cleared / 100 - 10 * puzzles - 2 * deaths).coerceIn(20, 100)
        val explore = 60 * cleared / 100 + (40 * minOf(1.0, secrets / required)).toInt()
        val bonus = minOf(5, crypts) + (if (mimic) 2 else 0) + (if (prince) 1 else 0)
        return skill + explore + 100 + bonus
    }

    init {
        // Perfect F7 clear: 100 + 100 + 100 + 5 crypts + mimic + prince = 308.
        check(score(100, 100.0, 100, 0, 0, 5, mimic = true, prince = true) == 308)
        // Nothing done yet: 20 skill + 0 explore + 100 speed.
        check(score(0, 0.0, 30, 0, 0, 0, mimic = false, prince = false) == 120)
    }
}
