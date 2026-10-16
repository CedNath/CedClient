package ced.cedclient.state

import ced.cedclient.events.AreaChangeEvent
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerScoreEntry

/**
 * The scoreboard sidebar, read once every few ticks for everyone: clean lines (no formatting codes or emoji,
 * top to bottom) plus the SkyBlock values on them. Features use this instead of reading the scoreboard
 * themselves. Values are null when the sidebar doesn't show them (outside SkyBlock, loading, other servers).
 */
object Sidebar {
    /** Objective title without formatting, e.g. "SKYBLOCK" / "SKYBLOCK CO-OP". Empty when there is no sidebar. */
    var title: String = ""
        private set

    /** Every line, top to bottom, without formatting codes or emoji. */
    var lines: List<String> = emptyList()
        private set

    /** The same lines with their styling (colours), for features that read colours (Rift effigy hearts). */
    var components: List<Component> = emptyList()
        private set

    /** Area / sub-area from the " ⏣ ..." (or " ф ..." in the Rift) line, e.g. "Dwarven Village". */
    var area: String? = null
        private set

    var purse: Long? = null
        private set
    var bits: Long? = null
        private set
    /** Rift currency. */
    var motes: Long? = null
        private set

    /** SkyBlock date line, e.g. "Late Spring 12th". */
    var date: String? = null
        private set
    /** SkyBlock time line, e.g. "2:30pm". */
    var time: String? = null
        private set

    /** The line under "Objective", if the sidebar shows one. */
    var objective: String? = null
        private set

    private const val INTERVAL_TICKS = 5
    private var ticks = 0

    private val FORMATTING = Regex("§.")
    private val DISPLAY_ORDER = compareByDescending<PlayerScoreEntry> { it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.owner }
    // "⏣ Dwarven Village", "ф Wizard Tower" (Rift): a symbol, a space, the area. Symbol lines that aren't areas:
    // ♲ ironman, ☀ / ☽ time of day, Ⓑ bingo.
    private val AREA = Regex("""^([^\p{L}\p{N}\s]{1,2})\s+(.+)$""") // icon may be a surrogate pair (pack glyph)
    private const val NOT_AREA = "♲☀☽Ⓑ"
    private val PURSE = Regex("""^(?:Purse|Piggy): ([\d,]+)""")
    private val BITS = Regex("""^Bits: ([\d,]+)""")
    private val MOTES = Regex("""^Motes: ([\d,]+)""")
    private val DATE = Regex("""^(?:Early |Late )?(?:Spring|Summer|Autumn|Winter) \d{1,2}(?:st|nd|rd|th)$""")
    private val TIME = Regex("""^(\d{1,2}:\d{2}[ap]m)""")

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (++ticks % INTERVAL_TICKS == 0) update(mc)
        }
    }

    /** Lines matching [regex], first match only; handy for one-off lookups ("Slayer Quest", "Cleared: 100%"...). */
    fun find(regex: Regex): MatchResult? = lines.firstNotNullOfOrNull { regex.find(it) }

    private fun update(mc: Minecraft) {
        val board = mc.level?.scoreboard
        val objective = board?.getDisplayObjective(DisplaySlot.SIDEBAR)
        if (board == null || objective == null) {
            components = emptyList()
            set("", emptyList())
            return
        }
        // listPlayerScores is unordered: sort it like the vanilla sidebar (score high to low, then name), so lines are
        // top to bottom. Hypixel puts each line's text in the team prefix + suffix.
        val read = board.listPlayerScores(objective).sortedWith(DISPLAY_ORDER).mapNotNull { entry ->
            if (entry.isHidden) return@mapNotNull null
            val team = board.getPlayersTeam(entry.owner())
            val styled = if (team != null) Component.empty().append(team.playerPrefix).append(team.playerSuffix) else entry.ownerName()
            clean(styled.string).ifEmpty { null }?.let { it to styled }
        }
        components = read.map { it.second }
        set(clean(objective.displayName.string), read.map { it.first })
    }

    /** No formatting codes, no emoji (surrogate pairs) or private-use icon glyphs, trimmed. */
    fun clean(text: String): String =
        FORMATTING.replace(text, "").filterNot { it.isSurrogate() || it in ''..'' }.trim()

    private fun set(newTitle: String, newLines: List<String>) {
        title = newTitle
        lines = newLines
        // Read off the lines with their icons kept: clean() drops the area icon (⏣ / a pack glyph), which AREA needs.
        val newArea = components.map { FORMATTING.replace(it.string, "").trim() }.firstNotNullOfOrNull { line ->
            AREA.matchEntire(line)?.takeIf { it.groupValues[1] !in NOT_AREA.map(Char::toString) }?.groupValues?.get(2)?.trim()
        }
        purse = number(PURSE)
        bits = number(BITS)
        motes = number(MOTES)
        date = newLines.firstOrNull { DATE.matches(it) }
        time = newLines.firstNotNullOfOrNull { TIME.find(it)?.groupValues?.get(1) }
        objective = newLines.indexOfFirst { it == "Objective" }.takeIf { it >= 0 }?.let { newLines.getOrNull(it + 1) }

        if (newArea != area) {
            val previous = area
            area = newArea
            AreaChangeEvent(newArea, previous).post()
        }
    }

    private fun number(regex: Regex): Long? =
        lines.firstNotNullOfOrNull { regex.find(it)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull() }
}
