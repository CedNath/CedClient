package ced.cedclient.features.impl.funqol

import ced.cedclient.config.CedPaths
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Max level of each SkyBlock enchantment, for Enchant Colors (Athen takes this from a data file on its own server; this
 * is a built-in table instead). Two sources, the second wins: the table below, written from memory so a few entries may
 * be off, and what the game itself shows: Hypixel draws a maxed enchant gold, so whenever one is seen its level is
 * remembered (enchant_max_levels.json in the config folder, which can be edited by hand too).
 * Keys are the displayed name, lower-cased, with spaces and hyphens as underscores ("Triple-Strike" -> triple_strike).
 */
object EnchantLevels {
    private val TABLE = mapOf(
        // swords and melee
        "sharpness" to 7, "smite" to 7, "bane_of_arthropods" to 7, "critical" to 7, "first_strike" to 5, "giant_killer" to 7,
        "execute" to 6, "lethality" to 6, "ender_slayer" to 7, "cubism" to 6, "impaling" to 3, "vampirism" to 6, "life_steal" to 5,
        "looting" to 5, "luck" to 7, "scavenger" to 5, "experience" to 5, "cleave" to 6, "thunderlord" to 7, "venomous" to 6,
        "prosecute" to 6, "titan_killer" to 7, "triple_strike" to 5, "fire_aspect" to 3, "knockback" to 2, "syphon" to 5,
        "mana_steal" to 3, "vicious" to 5, "champion" to 10, "divine_gift" to 3, "drain" to 5, "dragon_hunter" to 5,
        // bows
        "power" to 7, "snipe" to 4, "aiming" to 5, "piercing" to 1, "infinite_quiver" to 10, "flame" to 1, "punch" to 2,
        // armor
        "protection" to 7, "growth" to 7, "rejuvenate" to 5, "true_protection" to 1, "sugar_rush" to 3, "thorns" to 3, "respite" to 5,
        "big_brain" to 5, "smarty_pants" to 5, "mana_vampire" to 10, "ferocious_mana" to 10, "hardened_mana" to 10, "strong_mana" to 10,
        "pristine" to 5, "depth_strider" to 3, "frost_walker" to 2, "feather_falling" to 10, "blast_protection" to 7, "fire_protection" to 7,
        "projectile_protection" to 7, "aqua_affinity" to 1, "respiration" to 3, "swiftness" to 3, "ender_resistance" to 5,
        // tools
        "efficiency" to 10, "fortune" to 4, "silk_touch" to 1, "harvesting" to 6, "replenish" to 1, "cultivating" to 10, "dedication" to 4,
        "delicate" to 5, "compact" to 10, "smelting_touch" to 1, "telekinesis" to 1, "sunder" to 5, "rainbow" to 1,
        // fishing
        "lure" to 6, "luck_of_the_sea" to 7, "magnet" to 7, "spiked_hook" to 6, "blessing" to 6, "angler" to 6, "charm" to 5,
        "caster" to 6, "expertise" to 10, "frail" to 6, "piscary" to 6, "corruption" to 5,
        // misc
        "soul_eater" to 5, "bank" to 5, "last_stand" to 5, "legion" to 5, "wisdom" to 5, "no_pain_no_gain" to 5, "combo" to 5,
    )

    private val gson = Gson()
    private val file by lazy { CedPaths.file("enchant_max_levels.json") }
    private val learned = HashMap<String, Int>()

    init {
        runCatching {
            val map: Map<String, Int> = gson.fromJson(file.readText(), object : TypeToken<Map<String, Int>>() {}.type)
            learned.putAll(map)
        }
    }

    fun key(name: String) = name.lowercase().replace("'", "").replace('-', '_').replace(' ', '_')

    /** The enchant's max level, or null when nothing is known about it yet. */
    fun max(key: String): Int? = learned[key] ?: TABLE[key] ?: if (key.startsWith("turbo_")) 5 else null

    /** The game showed [key] as maxed at [level]: remember it. */
    fun learn(key: String, level: Int) {
        if (learned[key] == level) return
        learned[key] = level
        runCatching { file.parentFile?.mkdirs(); file.writeText(gson.toJson(learned)) }
    }
}
