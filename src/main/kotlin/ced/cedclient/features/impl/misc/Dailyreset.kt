package ced.cedclient.features.impl.misc

import ced.cedclient.config.ConfigManager
import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.state.IslandState
import ced.cedclient.utils.Colors
import com.google.gson.Gson
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * One auto-trackable SkyBlock daily -- a static, code-defined entry, NOT
 * something added at runtime (that's what the manual checklist below is for).
 *
 * ADD A NEW DAILY BY COPYING AN ENTRY IN [DailyReset.dailyDefinitions]. Fields:
 *   - id: short, STABLE, lower_snake_case. This is the persistence key for
 *     both the enable toggle and today's progress -- renaming it after it
 *     ships silently resets both for anyone who already has it saved.
 *   - displayName: shown on the HUD and in `/cc daily list`.
 *   - cooldownLabel: purely cosmetic ("1h", "2h", "24h"...). It does NOT
 *     affect reset logic by itself -- see cooldownHours below for the field
 *     that actually does.
 *   - island: optional. When set, [completionPatterns] are only checked
 *     while IslandState.island equals this -- stops an unrelated island's
 *     chat line from ever false-triggering this entry. Leave null if the
 *     line is unambiguous no matter where you are, or if you don't know yet.
 *   - completionPatterns: chat-line regexes meaning "this happened once",
 *     checked with containsMatchIn() against the color-code-stripped line
 *     (ChatMessageEvent.unformattedText) -- doesn't need to match the WHOLE
 *     line, just contain it somewhere.
 *   - requiredCount: how many times completionPatterns must match TODAY
 *     before this entry counts as done. Defaults to 1 (one match = done).
 *     Set higher for things like Crimson Isle's reputation quests, which
 *     fire the same "+X Reputation" line once per quest turned in but need
 *     several turn-ins before the day's set is actually finished -- the HUD
 *     shows live progress as "(2/5)" for anything with requiredCount > 1.
 *   - dedupeMillis: Hypixel/the client are known to send or process some
 *     completion lines MORE THAN ONCE for the same real-world event (e.g.
 *     Crimson Isle miniboss kills and reputation turn-ins both echo their
 *     line once extra within well under a second, and tree/desert whisper
 *     completions have been observed firing the underlying
 *     ChatMessageEvent twice -- once on the network thread, once on the
 *     render thread -- for one real chat message). A match within this
 *     many ms of the previous ACCEPTED match for this same definition is
 *     treated as an echo and silently ignored -- it doesn't advance
 *     autoProgress and doesn't fire the completion announcement. Defaults
 *     to 0 (off) for definitions that only ever fire once per real event.
 *     IMPORTANT: this is checked against the time of the last accepted
 *     match (see lastMatchMillis below), NOT the time the entry last
 *     became fully done -- for a multi-count entry, every individual match
 *     needs deduping, not just the one that crosses requiredCount.
 *   - cooldownHours: null (default) means this entry, once done, stays
 *     done until the next Hypixel daily reset -- same as everything else.
 *     Set it to an actual hour count (e.g. 2 for Ubiks Cube, 1 for Torrhus
 *     Honey Hives) for something that can genuinely be re-earned multiple
 *     times per day: once that many hours have passed since it was last
 *     completed, it automatically becomes "due" again and reappears on the
 *     checklist -- WITHOUT waiting for the daily reset -- unless the global
 *     "Daily-Only Short Cooldowns" setting is on, which forces every entry
 *     (regardless of cooldownHours) back to the once-per-day behavior.
 *
 * HOW TO FIND A TRIGGER: flip on "Log Chat (find triggers)" below, do the
 * thing in-game, read the exact stripped line back out of the client
 * log/console, then paste it into a new Regex() here.
 */
private data class DailyDefinition(
    val id: String,
    val displayName: String,
    val cooldownLabel: String,
    val island: IslandState.Island? = null,
    val completionPatterns: List<Regex> = emptyList(),
    val requiredCount: Int = 1,
    val dedupeMillis: Long = 0L,
    val cooldownHours: Int? = null,
)

private data class DailyTaskItem(val name: String, var completed: Boolean = false) {
    val key: String get() = name.trim().lowercase()
}

private data class TaskSave(val name: String, val completed: Boolean)
private data class SavedState(
    val tasks: List<TaskSave>,
    val autoProgress: Map<String, Int>,
    // Nullable: save files from before cooldownHours existed won't have
    // this key, and Gson will hand back null for it rather than an empty
    // map -- the ?.let on load already accounts for that.
    val lastCompletedMillis: Map<String, Long>? = null,
    val lastResetEpochDay: Long,
    val x: Int,
    val y: Int,
    val scale: Float
)

/**
 * HUD checklist of "things that reset daily". Two kinds of entry, shown
 * together in one list:
 *
 *  - AUTO-TRACKED (see [dailyDefinitions] below): known dailies that tick
 *    themselves off (or count up toward [DailyDefinition.requiredCount])
 *    the moment their chat trigger is seen. Each one gets its own on/off
 *    toggle (a real Setting -- shows up right in this module's ClickGUI
 *    panel) so any single daily can be hidden without touching the others.
 *  - MANUAL (`/cc daily add <name>` / `/cc daily done <name>`): anything not
 *    covered by a definition yet, checked off by hand.
 *
 * Both kinds share the same base reset: everything clears the first time
 * this runs after a new Hypixel day starts. A few auto-tracked entries with
 * a genuinely shorter real cooldown (see [DailyDefinition.cooldownHours])
 * can ALSO reappear mid-day once their own cooldown elapses, unless the
 * "Daily-Only Short Cooldowns" setting forces everything to the once-a-day
 * behavior.
 *
 * RESET TIMING: Hypixel's own dailies (e.g. Fetchur's item quest, per the
 * SkyBlock wiki) reset at midnight US EASTERN TIME -- NOT CEST. Those are
 * different clocks that only briefly line up around DST transitions, since
 * EU and US change their clocks on different dates. Comparing
 * LocalDate.now() in the America/New_York zone specifically (rather than
 * this PC's local zone) is what actually tracks Hypixel's reset correctly
 * regardless of where the client is running.
 */
object DailyReset : Module(
    "Daily Reset",
    Category.Misc,
    "Checklist of SkyBlock dailies -- known ones tick themselves off automatically, plus a manual list for anything else. Clears at Hypixel's daily reset (midnight ET).",
    defaultEnabled = true
), HudElement {

    override val label: String = "Daily Reset"

    private const val MIN_SCALE = 0.5f
    private const val MAX_SCALE = 3.0f

    // Hypixel's daily resets run on US Eastern Time regardless of this PC's
    // own time zone -- see the class doc comment above.
    private val EASTERN: ZoneId = ZoneId.of("America/New_York")

    override var panelX: Int = 6
    override var panelY: Int = 90
    override var panelScale: Float = 1.0f

    override var lastWidth: Int = 120
        private set
    override var lastHeight: Int = 16
        private set

    // =========================================================================
    // AUTO-TRACKED DAILY DEFINITIONS -- see the DailyDefinition class doc above
    // for exactly what each field means and how to add a new one.
    //
    // Every completionPatterns entry below is transcribed from what was
    // reported in-game, generalized slightly (e.g. \d+ instead of a literal
    // number) where the exact number looked incidental rather than fixed --
    // narrow it back down to an exact literal if a false positive ever shows
    // up. `island` is only set where reasonably confident; several are left
    // null with a comment because the exact location wasn't confirmed --
    // fill those in once you know, to avoid a same-wording line from an
    // unrelated island ever mis-triggering it.
    // =========================================================================
    private val dailyDefinitions: List<DailyDefinition> = listOf(
        DailyDefinition(
            id = "fetchur",
            displayName = "Fetchur's Item Quest",
            cooldownLabel = "24h",
            island = IslandState.Island.WINTER_ISLAND,
            // Reported as "[NPC] Fetchur: thanks thats probably what i
            // needed" -- almost certainly a paraphrase/typo of the real
            // line (capitalization/apostrophe), so matched loosely and
            // case-insensitively rather than as an exact literal. Tighten
            // this once you've copied the real line out of the log.
            completionPatterns = listOf(Regex("""Fetchur:.*needed""", RegexOption.IGNORE_CASE)),
        ),
        DailyDefinition(
            id = "ubiks_cube",
            displayName = "Ubiks Cube",
            cooldownLabel = "2h",
            // island not confirmed -- add IslandState.Island.XXX here once known.
            completionPatterns = listOf(Regex("""opponent earned \d+ Motes in this match""")),
            // Genuinely re-playable every 2h in-game, not just once/day --
            // see cooldownHours doc on DailyDefinition above.
            cooldownHours = 2,
        ),
        DailyDefinition(
            id = "heavy_pearls",
            displayName = "Heavy Pearls",
            cooldownLabel = "24h",
            // "Find a way to reach the top of the stomach!" reads like the
            // quest's OBJECTIVE text (shown on accept), not a completion
            // line -- double check this actually only appears once you've
            // finished it, not the moment you pick it up.
            completionPatterns = listOf(Regex("""Find a way to reach the top of the stomach""")),
        ),
        DailyDefinition(
            id = "puzzler",
            displayName = "Puzzler",
            cooldownLabel = "24h",
            completionPatterns = listOf(Regex("""Puzzler gave you""")),
        ),
        DailyDefinition(
            id = "hunting_traps",
            displayName = "Hunting Traps",
            cooldownLabel = "unclear",
            // No trigger yet -- see the earlier caveat that Trevor's own
            // hunts are cooldown-based (repeatable many times/day), not a
            // single daily reset. If there's a genuinely once-daily reward
            // tied to this, its trigger goes here; otherwise this may not
            // belong on a daily checklist at all.
        ),
        DailyDefinition(
            id = "island_cakes",
            displayName = "Island Cakes",
            cooldownLabel = "48h",
            // "Yum! You" ("Century Cakes") -- fairly generic phrasing;
            // watch for false positives from unrelated food/eating lines.
            completionPatterns = listOf(Regex("""Yum! You""")),
        ),
        DailyDefinition(
            id = "experimentation_table",
            displayName = "Experimentation Table",
            cooldownLabel = "24h",
            completionPatterns = listOf(Regex("""You claimed the Superpairs rewards!""")),
        ),
        DailyDefinition(
            id = "isle_minibosses",
            displayName = "Isle Minibosses",
            cooldownLabel = "24h",
            island = IslandState.Island.CRIMSON_ISLE,
            completionPatterns = listOf(Regex("""\d+ Reputation for killing the miniboss""")),
            requiredCount = 5,
            // Confirmed in-game: Hypixel echoes this line twice per single
            // miniboss kill. 2s is comfortably longer than the observed gap
            // between the two copies but far shorter than any realistic gap
            // between two DIFFERENT minibosses, so it can't eat a real kill.
            dedupeMillis = 2000L,
        ),
        DailyDefinition(
            id = "mage_reputation",
            displayName = "Mage Reputation Quests",
            cooldownLabel = "24h",
            island = IslandState.Island.CRIMSON_ISLE,
            completionPatterns = listOf(Regex("""\+\d+\s+Mage\s+Reputation""")),
            requiredCount = 5,
            // Same double-send behavior as isle_minibosses above.
            dedupeMillis = 2000L,
        ),
        DailyDefinition(
            id = "barbarian_reputation",
            displayName = "Barbarian Reputation Quests",
            cooldownLabel = "24h",
            island = IslandState.Island.CRIMSON_ISLE,
            completionPatterns = listOf(Regex("""\+\d+\s+Barbarian\s+Reputation""")),
            requiredCount = 5,
            // Same double-send behavior as isle_minibosses above.
            dedupeMillis = 2000L,
        ),
        DailyDefinition(
            id = "daily_powder",
            displayName = "Daily Powder (Mithril/Gemstone/Glacite)",
            cooldownLabel = "24h",
            // Confirmed: all three ore types share one template --
            // "You've earned <varying amount> <Ore> Powder from mining
            // your first <Ore> of the day!" (amount is comma-formatted,
            // e.g. "5,000" or "7,000", and varies run to run -- matched
            // loosely rather than pinned to a specific value). Each type
            // fires its own line once per Hypixel day, so requiredCount=3
            // below means this entry only counts as done once you've hit
            // first-of-day on Mithril, Gemstone, AND Glacite. No single
            // island fits (Mithril is Dwarven Mines, Gemstone/Glacite are
            // Crystal Hollows/Glacite Tunnels), so island is left null.
            completionPatterns = listOf(
                Regex("""You've earned [\d,]+ (?:Mithril|Gemstone|Glacite) Powder from mining your first""")
            ),
            requiredCount = 3,
            dedupeMillis = 2000L,
        ),
        DailyDefinition(
            id = "tree_whispers",
            displayName = "Forest/Desert Whispers",
            cooldownLabel = "24h",
            // Confirmed: this ChatMessageEvent has been observed firing
            // TWICE for a single real tree/desert completion (once from
            // the network thread, once from the render thread) -- unlike
            // isle_minibosses/mage_reputation/barbarian_reputation/
            // daily_powder above, this isn't Hypixel double-sending a chat
            // line, it's the client processing one real line twice. Same
            // dedupeMillis mechanism handles it either way.
            completionPatterns = listOf(Regex("""You helped cut 100% of the.""")),
            requiredCount = 3,
            dedupeMillis = 2000L,
        ),
        DailyDefinition(
            id = "torrhus_honeyhives",
            displayName = "Torrhus Honey Hives",
            cooldownLabel = "1h",
            island = IslandState.Island.TORRHUS_CANYON,
            completionPatterns = listOf(Regex("""HIVE! You""")),
            // Genuinely re-collectible every 1h in-game -- see cooldownHours
            // doc on DailyDefinition above.
            cooldownHours = 1,
        ),

        // Template for a new entry -- copy/uncomment and fill in:
        // DailyDefinition(
        //     id = "example_id",
        //     displayName = "Example Daily",
        //     cooldownLabel = "2h",
        //     island = null,
        //     completionPatterns = listOf(Regex("""exact or partial chat line here""")),
        //     requiredCount = 1,
        // ),
    )

    // One toggle per auto-tracked daily, registered as a real Setting so it
    // renders (and can be flipped) right in this module's ClickGUI panel and
    // persists through the normal ConfigManager path -- same as any other
    // BooleanSetting in the codebase. Disabling one hides it from the list
    // entirely (not just "counts as done").
    private val entryToggles: Map<String, BooleanSetting> = dailyDefinitions.associate { def ->
        def.id to BooleanSetting(
            "${def.displayName} (${def.cooldownLabel})",
            true,
            buildString {
                append(
                    if (def.completionPatterns.isEmpty())
                        "No trigger wired up yet -- mark it done manually with /cc daily done."
                    else if (def.requiredCount > 1)
                        "Auto-detected -- needs ${def.requiredCount} matches today to count as done."
                    else
                        "Auto-detected and ticked off automatically."
                )
                if (def.cooldownHours != null) {
                    append(" Re-appears every ${def.cooldownHours}h unless 'Daily-Only Short Cooldowns' is on.")
                }
            }
        )
    }

    private fun isDefinitionEnabled(id: String) = entryToggles[id]?.value != false

    private val logChatForTriggers = BooleanSetting(
        "Log Chat (find triggers)",
        false,
        "Prints every chat line to console while on -- use it to find the exact wording for a new " +
                "daily's completionPatterns, then turn it back off."
    )

    // Lets people turn the "Daily completed!" chat announcement off without
    // losing auto-tracking itself -- separate from logChatForTriggers, which
    // is a debug tool, not a user-facing feature toggle.
    private val announceCompletionInChat = BooleanSetting(
        "Announce Completion In Chat",
        true,
        "Sends a local chat message, prefixed [CedClient], whenever a daily (auto-tracked or manual) is completed."
    )

    // When on, forces EVERY entry (even ones with cooldownHours set, like
    // Ubiks Cube / Torrhus Honey Hives) back to the plain once-per-Hypixel-
    // day behavior -- they still tick off the moment they're first
    // completed, but won't reappear on the checklist again until the actual
    // daily reset, instead of resurfacing every 2h/1h as they'd otherwise do.
    private val forceDailyOnlyShortCooldowns = BooleanSetting(
        "Daily-Only Short Cooldowns",
        true,
        "Entries like Ubiks Cube (2h) and Torrhus Honey Hives (1h) can genuinely be re-earned multiple " +
                "times a day. With this ON, they're still only shown once per Hypixel day like everything " +
                "else. Turn it OFF to have them reappear on the checklist as soon as their own cooldown " +
                "passes, instead of waiting for the daily reset."
    )

    // On by default now that it's been tested -- see the doc comment on
    // renderNVG() below for exactly how it works. If NanoVG rendering ever
    // throws for any reason, renderInternal() below still catches it and
    // silently falls back to the plain (square-corner-safe) vanilla path
    // for that frame, so a regression shows up as a wrong-looking panel
    // rather than breaking anything else. Flagged .advanced so the toggle
    // to fall back to the plain renderer is only visible with Advanced
    // Mode on, out of the way for everyone else.
    private val useNanoVGRendering = BooleanSetting(
        "Use NanoVG Rendering",
        true,
        "True rounded corners + antialiasing via the same NanoVG pipeline ClickGUI uses, instead of the " +
                "scanline-approximated corners GuiGraphicsExtractor.fill() draws. Falls back to the old " +
                "rendering automatically if anything goes wrong."
    ).also { it.advanced = true }

    private val tasks = mutableListOf<DailyTaskItem>()
    private val autoProgress = mutableMapOf<String, Int>() // definition id -> match count today

    // definition id -> System.currentTimeMillis() this entry last became
    // fully "done" (progress reached requiredCount). Used ONLY for
    // cooldownHours entries, to know when they become due again within
    // the same Hypixel day. NOT used for echo/dedupe detection -- see
    // lastMatchMillis below for that, since a multi-count entry can take
    // several real-world events to reach "done" and each of those needs
    // its own dedupe window, not just the final one.
    private val lastCompletedMillis = mutableMapOf<String, Long>()

    // definition id -> System.currentTimeMillis() of the last ACCEPTED
    // (non-echo) completionPatterns match, whether or not that match was
    // the one that finished the entry. This is what dedupeMillis actually
    // compares against. In-memory only (not persisted) -- it only needs to
    // survive long enough to catch an echo of the same real event a
    // fraction of a second later, never across a restart.
    private val lastMatchMillis = mutableMapOf<String, Long>()

    private var lastResetEpochDay: Long = currentResetEpochDay()

    private fun currentResetEpochDay(): Long = LocalDate.now(EASTERN).toEpochDay()

    /**
     * Whether [def] currently counts as done. For a plain daily (no
     * cooldownHours, or "Daily-Only Short Cooldowns" is on) this is a pure
     * progress check, same as always. For a cooldownHours entry with that
     * setting off, once enough real hours have passed since it was last
     * completed, this also clears its progress back to 0 so the caller sees
     * it as due again -- without waiting for the daily reset. That reset-
     * back-to-0 is the one piece of state mutation in an otherwise "query"
     * function; it only fires once per cooldown expiry (progress is already
     * 0 on every subsequent call until it's completed again), and persists
     * immediately so the mid-day reopen survives a restart.
     */
    private fun autoDone(def: DailyDefinition): Boolean {
        val progress = autoProgress[def.id] ?: 0
        if (progress < def.requiredCount) return false
        val cooldownHours = def.cooldownHours
        if (cooldownHours == null || forceDailyOnlyShortCooldowns.value) return true

        val completedAt = lastCompletedMillis[def.id] ?: return true
        val elapsedMs = System.currentTimeMillis() - completedAt
        if (elapsedMs >= cooldownHours * 3_600_000L) {
            autoProgress[def.id] = 0
            persist()
            return false
        }
        return true
    }

    /** "Name" for a finished/single-shot entry, "Name (2/5)" while a multi-count one is still in progress. */
    private fun autoDisplayName(def: DailyDefinition): String {
        val count = autoProgress[def.id] ?: 0
        return if (def.requiredCount <= 1) def.displayName else "${def.displayName} ($count/${def.requiredCount})"
    }

    // -------------------------
    // Completion chat announcement -- fires once, on the exact transition
    // to "done", for both auto-tracked and manual entries. Client-side only,
    // so it never touches the server and works identically whether or not
    // you're actually able to chat right now (muted, cooldown, etc).
    // -------------------------
    private val PREFIX_COLOR = TextColor.fromRgb(0x8A7FFF) // matches ACCENT_COLOR below
    private val BODY_COLOR = TextColor.fromRgb(0xDDDDDD)

    private fun announceCompletion(name: String) {
        if (!announceCompletionInChat.value) return
        // Client-side only, and tagged GuiMessageSource.SYSTEM_CLIENT under
        // the hood -- the same public entry point vanilla itself uses for
        // local-only notices (e.g. "Please select a Realm"), so it never
        // touches the server and is never mistaken for a real chat/command
        // line by anything else listening on ChatMessageEvent.
        val chat = Minecraft.getInstance().gui?.chat ?: return
        val message = Component.literal("[CedClient] ")
            .withStyle(Style.EMPTY.withColor(PREFIX_COLOR).withBold(true))
            .append(
                Component.literal("Daily completed: ")
                    .withStyle(Style.EMPTY.withColor(BODY_COLOR))
            )
            .append(
                Component.literal(name)
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF)).withBold(true))
            )
        chat.addClientSystemMessage(message)
    }

    /**
     * Wipes every task's progress (manual and auto) the first time this is
     * called after a new Hypixel day has actually started. Called from
     * render() and every query method below, so the rollover happens
     * whether the HUD is currently on screen or you're just running a
     * /cc daily command.
     */
    private fun rolloverIfNeeded() {
        val today = currentResetEpochDay()
        if (today == lastResetEpochDay) return
        lastResetEpochDay = today
        for (t in tasks) t.completed = false
        for (id in autoProgress.keys.toList()) autoProgress[id] = 0
        persist()
    }

    // -------------------------
    // Manual checklist -- /cc daily add/remove/done/undo.
    // -------------------------

    fun add(name: String): Boolean {
        ensureLoaded()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val key = trimmed.lowercase()
        // Refuse a manual entry that shadows an auto-tracked one -- it'd
        // otherwise show up twice with two independently-tracked states.
        if (dailyDefinitions.any { it.displayName.trim().lowercase() == key }) return false
        if (tasks.any { it.key == key }) return false
        tasks.add(DailyTaskItem(trimmed))
        persist()
        return true
    }

    fun remove(name: String): Boolean {
        ensureLoaded()
        val key = name.trim().lowercase()
        val removed = tasks.removeAll { it.key == key }
        if (removed) persist()
        return removed
    }

    /**
     * Marks done/undone by name -- checks auto-tracked definitions first
     * (by display name or id), then the manual list. For a multi-count auto
     * entry, "done" jumps progress straight to requiredCount and "undo"
     * resets it to 0 -- there's no manual way to set a partial count.
     * Only fires the chat announcement on an actual false->true transition,
     * so re-running `/cc daily done` on something already finished (or
     * flipping it to false) stays silent.
     */
    fun setCompleted(name: String, completed: Boolean): Boolean {
        ensureLoaded()
        rolloverIfNeeded()
        val key = name.trim().lowercase()

        val def = dailyDefinitions.firstOrNull { it.displayName.trim().lowercase() == key || it.id == key }
        if (def != null) {
            val wasDone = autoDone(def)
            autoProgress[def.id] = if (completed) def.requiredCount else 0
            if (completed) lastCompletedMillis[def.id] = System.currentTimeMillis()
            persist()
            if (completed && !wasDone) announceCompletion(def.displayName)
            return true
        }

        val task = tasks.firstOrNull { it.key == key } ?: return false
        val wasDone = task.completed
        task.completed = completed
        persist()
        if (completed && !wasDone) announceCompletion(task.name)
        return true
    }

    /** Current enabled state of one auto-tracked daily by display name or id, or null if no match. */
    fun isEntryEnabled(name: String): Boolean? {
        val key = name.trim().lowercase()
        val def = dailyDefinitions.firstOrNull { it.displayName.trim().lowercase() == key || it.id == key } ?: return null
        return isDefinitionEnabled(def.id)
    }

    /** Enables/disables one auto-tracked daily by display name or id. Returns false if nothing matched. */
    fun setEntryEnabled(name: String, enabled: Boolean): Boolean {
        ensureLoaded()
        val key = name.trim().lowercase()
        val def = dailyDefinitions.firstOrNull { it.displayName.trim().lowercase() == key || it.id == key } ?: return false
        entryToggles[def.id]?.value = enabled
        ConfigManager.saveModulesOnly()
        return true
    }

    /** Every auto-tracked definition (regardless of enabled state) with its cooldown label and today's done state -- for `/cc daily entries`. */
    fun autoEntries(): List<Triple<String, String, Boolean>> {
        ensureLoaded()
        rolloverIfNeeded()
        return dailyDefinitions.map { def ->
            Triple(def.displayName, def.cooldownLabel, autoDone(def))
        }
    }

    /** Everything not yet finished today (enabled auto-tracked first, then manual), in that order. Multi-count entries show live progress. */
    fun remaining(): List<String> {
        ensureLoaded()
        rolloverIfNeeded()
        val auto = dailyDefinitions
            .filter { isDefinitionEnabled(it.id) && !autoDone(it) }
            .map { autoDisplayName(it) }
        val manual = tasks.filter { !it.completed }.map { it.name }
        return auto + manual
    }

    /** Every enabled auto-tracked daily + every manual task, with today's done state, for a full `/cc daily list`. */
    fun allTasks(): List<Pair<String, Boolean>> {
        ensureLoaded()
        rolloverIfNeeded()
        val auto = dailyDefinitions
            .filter { isDefinitionEnabled(it.id) }
            .map { autoDisplayName(it) to autoDone(it) }
        val manual = tasks.map { it.name to it.completed }
        return auto + manual
    }

    // -------------------------
    // Auto-detection -- see dailyDefinitions above.
    // -------------------------
    init {
        on<ChatMessageEvent> { event ->
            if (logChatForTriggers.value) println("[DailyReset chat] ${event.unformattedText}")
            if (!isEnabled) return@on

            ensureLoaded()
            rolloverIfNeeded()

            val text = event.unformattedText
            for (def in dailyDefinitions) {
                if (def.completionPatterns.isEmpty()) continue
                if (!isDefinitionEnabled(def.id)) continue
                if (autoDone(def)) continue
                if (def.island != null && IslandState.island != def.island) continue
                if (def.completionPatterns.any { it.containsMatchIn(text) }) {
                    // Guard against the same real event producing more than
                    // one ChatMessageEvent (Hypixel double-sending a line
                    // server-side, e.g. Crimson Isle reputation/miniboss
                    // turn-ins; or the client itself processing one line
                    // twice, e.g. tree/desert whisper completions firing
                    // once on the network thread and again on the render
                    // thread). A match arriving within dedupeMillis of the
                    // last ACCEPTED match for this same definition is an
                    // echo, not a second real occurrence -- checked against
                    // lastMatchMillis (every accepted match), NOT
                    // lastCompletedMillis (only set once the whole entry is
                    // done), so a multi-count entry gets its dedupe window
                    // re-armed after every single accepted match, not just
                    // the final one that crosses requiredCount.
                    val now = System.currentTimeMillis()
                    val lastMatch = lastMatchMillis[def.id]
                    if (def.dedupeMillis > 0 && lastMatch != null && now - lastMatch < def.dedupeMillis) {
                        continue
                    }
                    lastMatchMillis[def.id] = now

                    autoProgress[def.id] = (autoProgress[def.id] ?: 0) + 1
                    val nowDone = autoProgress[def.id]!! >= def.requiredCount
                    if (nowDone) lastCompletedMillis[def.id] = now
                    persist()
                    // Only announce on the exact match that pushes a
                    // multi-count entry (e.g. reputation quests) over its
                    // requiredCount threshold, not on every intermediate tick.
                    if (nowDone) announceCompletion(def.displayName)
                }
            }
        }
    }

    // -------------------------
    // Persistence -- own file, same pattern as WarpShortcuts/TimeHud. Only
    // progress (auto + manual) round-trips here; the per-daily enable
    // toggles are real Settings and persist through ConfigManager.
    // lastMatchMillis is deliberately NOT persisted -- it's a short-lived
    // echo-detection window, not durable state.
    // -------------------------
    private val gson = Gson()
    private val saveFile: File by lazy {
        File(Minecraft.getInstance().gameDirectory, "cedclient/daily_reset.json")
    }

    private fun persist() {
        try {
            saveFile.parentFile?.mkdirs()
            val state = SavedState(
                tasks.map { TaskSave(it.name, it.completed) },
                autoProgress.toMap(),
                lastCompletedMillis.toMap(),
                lastResetEpochDay,
                panelX,
                panelY,
                panelScale
            )
            saveFile.writeText(gson.toJson(state))
        } catch (e: Exception) {
            println("[DailyReset] Failed to save: ${e.message}")
        }
    }

    private var loadedOnce = false

    /**
     * Removes any manual task whose name matches an auto-tracked
     * definition's id or display name -- cleans up entries created via
     * `/cc daily add` before that daily got auto-tracking, which would
     * otherwise sit alongside the auto entry forever showing duplicated
     * and un-toggleable (manual tasks have no BooleanSetting, so the
     * per-daily toggle can't touch them). Returns true if anything was
     * removed, so the caller knows whether to persist.
     */
    private fun pruneShadowingManualTasks(): Boolean {
        val shadowedKeys = dailyDefinitions.flatMapTo(mutableSetOf()) {
            listOf(it.id.lowercase(), it.displayName.trim().lowercase())
        }
        return tasks.removeAll { it.key in shadowedKeys }
    }

    fun ensureLoaded() {
        if (loadedOnce) return
        loadedOnce = true
        try {
            if (saveFile.exists()) {
                val loaded = gson.fromJson(saveFile.readText(), SavedState::class.java)
                if (loaded != null) {
                    tasks.clear()
                    tasks.addAll(loaded.tasks.map { DailyTaskItem(it.name, it.completed) })
                    autoProgress.clear()
                    autoProgress.putAll(loaded.autoProgress)
                    lastCompletedMillis.clear()
                    loaded.lastCompletedMillis?.let { lastCompletedMillis.putAll(it) }
                    lastResetEpochDay = loaded.lastResetEpochDay
                    panelX = loaded.x
                    panelY = loaded.y
                    panelScale = loaded.scale.coerceIn(MIN_SCALE, MAX_SCALE)
                    if (pruneShadowingManualTasks()) persist()
                    return
                }
            }
            lastResetEpochDay = currentResetEpochDay()
            persist()
        } catch (e: Exception) {
            println("[DailyReset] Failed to load: ${e.message}")
        }
    }

    // HudElement.save() -- fired by MasterHudEditScreen after a drag/scale.
    override fun save() = persist()

    override fun adjustScale(delta: Float) {
        panelScale = (panelScale + delta).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /**
     * Real gameplay render path -- unlike renderInternal() (also used by
     * MasterHudEditScreen for a live drag/scale preview, which should
     * always show SOMETHING to grab), this one goes fully off-screen the
     * moment nothing is left to do today, even though the module itself is
     * still enabled. It reappears the instant anything becomes due again
     * (daily reset, or a cooldownHours entry coming back around).
     */
    fun render(g: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
        if (!isEnabled) return
        if (remaining().isEmpty()) return
        renderInternal(g)
    }

    // Corner radius in local (unscaled) pixels, and the border thickness
    // drawn around the rounded card.
    private const val CORNER_RADIUS = 8
    private const val BORDER_THICKNESS = 1

    /**
     * Fills a rounded rectangle using only GuiGraphicsExtractor.fill() --
     * no NanoVG. See the class-level rationale above render(): a HUD panel
     * running its own top-level NVG frame every frame previously broke
     * badly in this codebase (TimeHud's doc comment has the details), so
     * this rasterizes rounded corners the boring way instead. The middle
     * is two overlapping plain rects forming a plus/cross shape covering
     * everything except the four corner squares; each corner square then
     * gets its quarter-circle carved out one horizontal scanline at a
     * time, testing (localX - r)^2 + (localY - r)^2 <= r^2 against a
     * circle centered on that square's inner corner.
     */
    private fun fillRounded(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, radius: Int, color: Int) {
        val r = radius.coerceIn(0, minOf(width, height) / 2)
        if (r <= 0) {
            g.fill(x, y, x + width, y + height, color)
            return
        }

        g.fill(x + r, y, x + width - r, y + height, color)
        g.fill(x, y + r, x + width, y + height - r, color)

        for (row in 0 until r) {
            val dy = r - row
            val dx = kotlin.math.sqrt((r * r - dy * dy).toDouble()).toInt()
            val inset = r - dx

            g.fill(x + inset, y + row, x + r, y + row + 1, color) // top-left
            g.fill(x + width - r, y + row, x + width - inset, y + row + 1, color) // top-right
            g.fill(x + inset, y + height - 1 - row, x + r, y + height - row, color) // bottom-left
            g.fill(x + width - r, y + height - 1 - row, x + width - inset, y + height - row, color) // bottom-right
        }
    }

    /** Draws a rounded card border by layering a background-colored rounded rect inside a border-colored one. */
    private fun drawCard(g: GuiGraphicsExtractor, width: Int, height: Int) {
        fillRounded(g, 0, 0, width, height, CORNER_RADIUS, BORDER_COLOR)
        fillRounded(
            g,
            BORDER_THICKNESS, BORDER_THICKNESS,
            width - BORDER_THICKNESS * 2, height - BORDER_THICKNESS * 2,
            (CORNER_RADIUS - BORDER_THICKNESS).coerceAtLeast(0),
            BACKGROUND_COLOR
        )
    }

    private val BORDER_COLOR = 0xFF5A4FCF.toInt()
    private val BACKGROUND_COLOR = 0xE8161620.toInt()
    private val ACCENT_COLOR = 0xFF8A7FFF.toInt()
    private val PROGRESS_COLOR = 0xFFE8B85C.toInt() // warm amber, for multi-count "(x/y)" lines
    private val DONE_BADGE_COLOR = 0xFF7BD88F.toInt() // soft green, edit-screen-preview "DONE" badge
    private val BADGE_TEXT_COLOR = 0xFF1A1A24.toInt()
    private val DIVIDER_HIGHLIGHT = 0x30FFFFFF

    /** Also used by MasterHudEditScreen to draw a live preview while dragging/scaling. */
    /**
     * Also used by MasterHudEditScreen to draw a live preview while
     * dragging/scaling. Dispatches to whichever backend is selected;
     * NanoVG gets a try/catch specifically because it's the
     * unproven/experimental path (see useNanoVGRendering's doc comment) --
     * if renderNVG() throws for any reason, this silently falls back to
     * renderVanilla() for that frame instead of leaving the panel blank or
     * propagating the exception. renderVanilla() itself is NOT wrapped:
     * it's the long-proven path (same primitives TimeHud/EntityESPHud use),
     * so a throw from there is a real bug worth seeing, not something to
     * paper over.
     */
    override fun renderInternal(g: GuiGraphicsExtractor) {
        ensureLoaded()
        rolloverIfNeeded()

        if (useNanoVGRendering.value) {
            try {
                renderNVG(g)
                return
            } catch (e: Exception) {
                println("[DailyReset] NanoVG render failed, falling back to vanilla rendering this frame: ${e.message}")
            }
        }
        renderVanilla(g)
    }

    /**
     * NanoVG rendering path -- EXPERIMENTAL, see useNanoVGRendering's doc
     * comment above for the toggle, and TimeHud's doc comment for exactly
     * how a previous attempt at this broke (resizing made the panel
     * disappear, and disabling the module broke ALL NVG-drawn UI including
     * ClickGUI). That second symptom traces to a real bug in
     * NVGSpecialRenderer.renderToTexture(): NVGRenderer.beginFrame()/
     * endFrame() form a matched pair around state.renderContent(), and
     * beginFrame() throws if a previous endFrame() never ran -- but nothing
     * guaranteed endFrame() ran if renderContent() itself threw. One
     * exception anywhere in one panel's NVG drawing permanently wedged
     * NVGRenderer's internal `drawing` flag true, so every OTHER
     * beginFrame() call afterward -- including ClickGUI's own top-level one
     * -- immediately threw too. NVGSpecialRenderer.kt now wraps that block
     * in try/finally so that specific failure mode can't recur; this is
     * still a new call site for the pipeline outside a Screen's
     * extractRenderState though (a HUD element rendering every frame,
     * independent of whether any Screen is open), which the first attempt
     * never got a chance to root-cause. Layout mirrors renderVanilla()
     * exactly; only the actual draw calls differ (NVGRenderer.rect/
     * hollowRect/text instead of GuiGraphicsExtractor.fill/text, giving
     * real rounded corners and antialiasing instead of the scanline
     * approximation). Follows ClickGUI's own established call convention
     * as closely as possible (the one other known-working reference this
     * pipeline has): NVGSpecialRenderer.draw() gets the FULL canvas as its
     * bounds (0,0 to guiWidth/guiHeight), exactly like ClickGUI passes, and
     * this panel's actual position/scale is applied manually inside
     * renderContent via NVGRenderer.translate/scale -- deliberately NOT a
     * tight per-panel bounding box, since stale/mis-sized per-panel bounds
     * (e.g. computed from last frame's width/height before this frame's
     * resize is accounted for) is one plausible explanation for the
     * "disappears on resize" symptom, and matching ClickGUI's own pattern
     * exactly removes that as a variable.
     */
    private fun renderNVG(g: GuiGraphicsExtractor) {
        val font = NVGRenderer.defaultFont
        val fontSize = 12f * panelScale

        val header = "Daily Reset"
        val actualRemaining = remaining()
        val allDone = actualRemaining.isEmpty()
        val lines = actualRemaining.ifEmpty { listOf("All done for today!") }
        val badgeText = if (allDone) "DONE" else "${actualRemaining.size} LEFT"

        val paddingX = 10f * panelScale
        val paddingY = 8f * panelScale
        val headerGap = 5f * panelScale
        val lineHeight = fontSize + 7f * panelScale
        val bulletIndent = 12f * panelScale
        val badgeHPad = 6f * panelScale
        val badgeVPad = 3f * panelScale
        val cornerRadius = CORNER_RADIUS * panelScale
        val borderThickness = BORDER_THICKNESS * panelScale

        val headerWidth = NVGRenderer.textWidth(header, fontSize, font)
        val badgeWidth = NVGRenderer.textWidth(badgeText, fontSize, font) + badgeHPad * 2
        val badgeHeight = fontSize + badgeVPad * 2
        val headerRowHeight = maxOf(fontSize, badgeHeight)
        val headerRowWidth = headerWidth + 8f * panelScale + badgeWidth

        val contentWidth = maxOf(
            headerRowWidth,
            lines.maxOf { NVGRenderer.textWidth(it, fontSize, font) + bulletIndent }
        )
        val width = contentWidth + paddingX * 2
        val height = paddingY * 2 + headerRowHeight + headerGap + 2f * panelScale + lineHeight * lines.size

        lastWidth = (width / panelScale).toInt()
        lastHeight = (height / panelScale).toInt()

        NVGSpecialRenderer.draw(
            g,
            0, 0, g.guiWidth(), g.guiHeight(),
            renderContent = {
                // NVGRenderer's own canvas space is NOT the same coordinate
                // system as GuiGraphicsExtractor's (panelX/panelY, and the
                // editor's own outline box, are both in GUI-pose/GUI-Scale
                // units) -- see NVGRenderer.canvasWidth's doc comment: canvas
                // space is raw framebuffer pixels / device pixel ratio, while
                // guiWidth() is raw pixels / Minecraft's GUI Scale setting.
                // Those only coincide when GUI Scale and device pixel ratio
                // happen to be equal. ClickGUI never has to bridge this
                // because it's the only thing positioning its own content --
                // it just made up a canvas-space-native `clickGuiScale` and
                // never touches a GuiGraphicsExtractor coordinate at all.
                // DailyReset's panelX/panelY, though, are dragged via plain
                // mouse coordinates and shared with the editor's vanilla
                // outline-box overlay (MasterHudEditScreen's
                // context.outline() call), so they're GUI-pose values by
                // definition. Scaling by canvasWidth/guiWidth() first
                // converts a GUI-pose offset into the equivalent NVG-canvas
                // offset before translating by it, so this panel's NVG
                // content actually lands under that same outline box instead
                // of drifting off to a different apparent position/size
                // whenever GUI Scale != device pixel ratio.
                val spaceRatio = NVGRenderer.canvasWidth / g.guiWidth().toFloat()
                NVGRenderer.scale(spaceRatio, spaceRatio)
                NVGRenderer.translate(panelX.toFloat(), panelY.toFloat())

                NVGRenderer.rect(0f, 0f, width, height, BACKGROUND_COLOR, cornerRadius)
                NVGRenderer.hollowRect(0f, 0f, width, height, borderThickness, BORDER_COLOR, cornerRadius)

                val headerTextY = paddingY + (headerRowHeight - fontSize) / 2f
                NVGRenderer.text(header, paddingX, headerTextY, fontSize, Colors.WHITE.rgba, font)

                val badgeLeft = width - paddingX - badgeWidth
                val badgeTop = paddingY
                NVGRenderer.rect(
                    badgeLeft, badgeTop, badgeWidth, badgeHeight,
                    if (allDone) DONE_BADGE_COLOR else ACCENT_COLOR, badgeHeight / 2f
                )
                NVGRenderer.text(badgeText, badgeLeft + badgeHPad, badgeTop + badgeVPad, fontSize, BADGE_TEXT_COLOR, font)

                val dividerY = paddingY + headerRowHeight + headerGap
                NVGRenderer.rect(paddingX, dividerY, width - paddingX * 2, 1f * panelScale, ACCENT_COLOR)
                NVGRenderer.rect(paddingX, dividerY + 1f * panelScale, width - paddingX * 2, 1f * panelScale, DIVIDER_HIGHLIGHT)

                val listStartY = dividerY + 2f * panelScale + headerGap / 2f
                for ((i, line) in lines.withIndex()) {
                    val y = listStartY + lineHeight * i
                    val isProgress = !allDone && line.contains('(') && line.contains(')')
                    val color = if (isProgress) PROGRESS_COLOR else 0xFFDDDDDD.toInt()
                    NVGRenderer.text("\u2022", paddingX, y, fontSize, if (allDone) DONE_BADGE_COLOR else ACCENT_COLOR, font)
                    NVGRenderer.text(line, paddingX + bulletIndent, y, fontSize, color, font)
                }
            }
        )
    }

    private fun renderVanilla(g: GuiGraphicsExtractor) {
        val font = Minecraft.getInstance().font
        val header = "Daily Reset"
        val actualRemaining = remaining()
        val allDone = actualRemaining.isEmpty()
        val lines = actualRemaining.ifEmpty { listOf("All done for today!") }
        val badgeText = if (allDone) "DONE" else "${actualRemaining.size} LEFT"

        val paddingX = 10
        val paddingY = 8
        val headerGap = 5 // extra space below the header row, above the divider
        val lineHeight = font.lineHeight + 4
        val bulletIndent = 10 // horizontal offset from bullet to line text
        val badgeHPad = 5
        val badgeVPad = 2

        val badgeWidth = font.width(badgeText) + badgeHPad * 2
        val badgeHeight = font.lineHeight + badgeVPad * 2
        val headerRowHeight = maxOf(font.lineHeight, badgeHeight)
        val headerRowWidth = font.width(header) + 8 + badgeWidth

        val width = maxOf(headerRowWidth, lines.maxOf { font.width(it) + bulletIndent }) + paddingX * 2
        val height = paddingY * 2 + headerRowHeight + headerGap + 2 + lineHeight * lines.size

        lastWidth = width
        lastHeight = height

        // draw everything in local (0,0)-anchored space, then scale+translate
        // the whole panel as one unit, same approach as TimeHud/EntityESPHud
        g.pose().pushMatrix()
        g.pose().translate(panelX.toFloat(), panelY.toFloat())
        g.pose().scale(panelScale, panelScale)

        drawCard(g, width, height)

        val headerTextY = paddingY + (headerRowHeight - font.lineHeight) / 2
        g.text(font, header, paddingX, headerTextY, Colors.WHITE.rgba)

        // right-aligned status pill -- green "DONE" only ever visible via
        // the edit-screen preview, since render() keeps the real HUD fully
        // off-screen once actualRemaining is empty.
        val badgeLeft = width - paddingX - badgeWidth
        val badgeTop = paddingY
        fillRounded(g, badgeLeft, badgeTop, badgeWidth, badgeHeight, badgeHeight / 2, if (allDone) DONE_BADGE_COLOR else ACCENT_COLOR)
        g.text(font, badgeText, badgeLeft + badgeHPad, badgeTop + badgeVPad, BADGE_TEXT_COLOR)

        // divider with a faint 1px highlight underneath for a bit of depth
        val dividerY = paddingY + headerRowHeight + headerGap
        g.fill(paddingX, dividerY, width - paddingX, dividerY + 1, ACCENT_COLOR)
        g.fill(paddingX, dividerY + 1, width - paddingX, dividerY + 2, DIVIDER_HIGHLIGHT)

        val listStartY = dividerY + 2 + (headerGap / 2)
        for ((i, line) in lines.withIndex()) {
            val y = listStartY + lineHeight * i
            val isProgress = allDone.not() && line.contains('(') && line.contains(')')
            val color = if (isProgress) PROGRESS_COLOR else 0xFFDDDDDD.toInt()
            g.text(font, "\u2022", paddingX, y, if (allDone) DONE_BADGE_COLOR else ACCENT_COLOR)
            g.text(font, line, paddingX + bulletIndent, y, color)
        }

        g.pose().popMatrix()
    }

    init {
        addSettings(logChatForTriggers)
        addSettings(announceCompletionInChat)
        addSettings(forceDailyOnlyShortCooldowns)
        addSettings(useNanoVGRendering)
        addSettings(*entryToggles.values.toTypedArray())
        addSettings(
            // Manual override for testing (or just clearing a mistaken
            // check-off) without waiting for the actual midnight-ET reset.
            ActionSetting("Clear Completed (test reset)") {
                ensureLoaded()
                for (t in tasks) t.completed = false
                for (id in autoProgress.keys.toList()) autoProgress[id] = 0
                persist()
            }
        )
    }
}