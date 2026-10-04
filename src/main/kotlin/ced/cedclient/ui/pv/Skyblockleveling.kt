package ced.cedclient.ui.pv

import com.google.gson.JsonObject

/**
 * SkyBlock skill/slayer leveling math -- XP thresholds, current level, and
 * progress into the next level, for the Home tab's skill grid.
 *
 * The XP tables below are the long-standing community tables (the same
 * ones NEU/SkyblockAddons-style mods ship), reconstructed from memory
 * rather than pulled fresh from a live Hypixel constants payload, and
 * deliberately capped at level 50 across the board (Runecrafting at 25)
 * even though a couple of skills (Farming, notably) can go higher in the
 * real game via Jacob's Farming XP boosts -- I didn't want to guess at
 * exact XP requirements for levels this table doesn't cover and have them
 * be silently wrong. If a level looks off for a real account, this table
 * (not the math around it) is the thing to check against the current
 * SkyBlock wiki and extend.
 *
 * Field reading is a similar best-effort: this reads the long-standing flat
 * `experience_skill_<name>` / `slayer_bosses.<type>.xp` member fields.
 * Hypixel has been migrating skyblock/profiles toward more nested
 * categories over time -- if a fresh payload (ProfileViewer.lastSkyblockJson,
 * dumped via Debug.enabled) shows these living somewhere else now, update
 * [skillXp]/[slayerXp] to match; everything downstream (level, progress bar)
 * just consumes whatever Double they return.
 */
object SkyblockLeveling {

    // Per-level XP deltas (NOT cumulative), levels 1..50.
    private val standardTable = longArrayOf(
        50, 125, 200, 300, 500, 750, 1000, 1500, 2000, 3500,
        5000, 7500, 10000, 15000, 20000, 30000, 50000, 75000, 100000, 200000,
        300000, 400000, 500000, 600000, 700000, 800000, 900000, 1000000, 1100000, 1200000,
        1300000, 1400000, 1500000, 1600000, 1700000, 1800000, 1900000, 2000000, 2100000, 2200000,
        2300000, 2400000, 2500000, 2600000, 2750000, 2900000, 3100000, 3300000, 3500000, 3700000
    )

    // Per-level XP deltas, levels 1..25.
    private val runecraftingTable = longArrayOf(
        50, 100, 125, 160, 200, 250, 315, 400, 500, 625,
        785, 1000, 1250, 1600, 2000, 2465, 3125, 4000, 5000, 6200,
        7800, 9800, 12200, 15300, 19050
    )

    data class SkillDef(val key: String, val displayName: String, val maxLevel: Int, val table: LongArray)

    val skills: List<SkillDef> = listOf(
        SkillDef("TAMING", "Taming", 50, standardTable),
        SkillDef("MINING", "Mining", 50, standardTable),
        SkillDef("FORAGING", "Foraging", 50, standardTable),
        SkillDef("ENCHANTING", "Enchanting", 50, standardTable),
        SkillDef("CARPENTRY", "Carpentry", 50, standardTable),
        SkillDef("FARMING", "Farming", 50, standardTable),
        SkillDef("COMBAT", "Combat", 50, standardTable),
        SkillDef("FISHING", "Fishing", 50, standardTable),
        SkillDef("ALCHEMY", "Alchemy", 50, standardTable),
        SkillDef("RUNECRAFTING", "Runecrafting", 25, runecraftingTable)
    )

    data class SlayerDef(val key: String, val displayName: String, val table: LongArray)

    // Cumulative XP thresholds (NOT deltas) for tiers 1..9 -- reaching tier N
    // needs this many total slayer XP for that boss type, not this much on
    // top of the previous tier. Same table used for all three here; wolf/
    // enderman/blaze may genuinely differ at the low tiers in the real game,
    // so treat this as a starting point, not gospel.
    private val slayerTable = longArrayOf(5, 15, 200, 1000, 5000, 20000, 100000, 400000, 1000000)

    val slayers: List<SlayerDef> = listOf(
        SlayerDef("zombie", "Rev Slayer", slayerTable),
        SlayerDef("spider", "Tara Slayer", slayerTable),
        SlayerDef("wolf", "Sven Slayer", slayerTable)
    )

    data class LevelProgress(
        val level: Int,
        val maxLevel: Int,
        val xpIntoLevel: Double,
        val xpForNextLevel: Long,
        val progress: Float, // 0f..1f; 1f if maxed
        val maxed: Boolean
    )

    /**
     * First real-payload test (2026-09-27) came back all zeros on every
     * skill via the flat field alone, while slayer_bosses resolved fine --
     * so the flat `experience_skill_<key>` member field this was written
     * against is gone/moved in the current API.
     *
     * Best next guess, by analogy to coin_purse's own old-flat ->
     * new-nested-under-"currencies" migration (see PvData.purse): skills
     * got the same treatment, nested under a "player_data" category with
     * the same leaf field name. Tries that, then falls back to the
     * original flat guess in case some accounts/endpoints still expose it.
     *
     * If this still comes back 0, the fastest way to actually nail it:
     * dump `ProfileViewer.lastSkyblockJson` with Debug.enabled and grep the
     * member object for "experience" or "skill" -- whatever key holds a
     * plausible XP number (large-ish double) for a skill you know the real
     * level of is the one to wire in here.
     */
    fun skillXp(member: JsonObject, skill: SkillDef): Double {
        val fieldName = "experience_skill_${skill.key.lowercase()}"

        member.getAsJsonObject("player_data")?.get(fieldName)?.let { return it.asDouble }
        member.get(fieldName)?.let { return it.asDouble }

        return 0.0
    }

    /** Slayer XP lives under `member.slayer_bosses.<type>.xp` in the schema this was written against; falls back to a `member.slayer.slayer_bosses.<type>.xp` nesting in case that's changed. */
    fun slayerXp(member: JsonObject, slayer: SlayerDef): Double {
        member.getAsJsonObject("slayer_bosses")
            ?.getAsJsonObject(slayer.key)
            ?.get("xp")?.let { return it.asDouble }

        member.getAsJsonObject("slayer")
            ?.getAsJsonObject("slayer_bosses")
            ?.getAsJsonObject(slayer.key)
            ?.get("xp")?.let { return it.asDouble }

        return 0.0
    }

    /** For skills: [table] is per-level deltas, so this walks it consuming [xp] level by level. */
    fun levelFor(xp: Double, table: LongArray, maxLevel: Int): LevelProgress {
        val cappedTable = table.take(maxLevel)
        var remaining = xp
        var level = 0

        for (required in cappedTable) {
            if (remaining >= required) {
                remaining -= required
                level++
            } else {
                break
            }
        }

        if (level >= maxLevel) {
            return LevelProgress(maxLevel, maxLevel, 0.0, 0, 1f, true)
        }

        val nextRequirement = cappedTable[level]
        val progress = (remaining / nextRequirement).toFloat().coerceIn(0f, 1f)
        return LevelProgress(level, maxLevel, remaining, nextRequirement, progress, false)
    }

    /** For slayers: [table] is cumulative absolute thresholds, so this just counts how many [xp] clears. */
    fun slayerLevelFor(xp: Double, table: LongArray): LevelProgress {
        val maxLevel = table.size
        var level = 0
        for (threshold in table) {
            if (xp >= threshold) level++ else break
        }

        if (level >= maxLevel) {
            return LevelProgress(maxLevel, maxLevel, 0.0, 0, 1f, true)
        }

        val prevThreshold = if (level == 0) 0L else table[level - 1]
        val nextThreshold = table[level]
        val span = nextThreshold - prevThreshold
        val progress = if (span <= 0) 1f else ((xp - prevThreshold) / span).toFloat().coerceIn(0f, 1f)
        return LevelProgress(level, maxLevel, xp - prevThreshold, span, progress, false)
    }
}