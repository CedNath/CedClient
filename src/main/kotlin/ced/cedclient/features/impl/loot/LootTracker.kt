package ced.cedclient.features.impl.loot

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.multiplayer.ClientLevel

/**
 * Accumulates item drops parsed out of suppressed reward chat spam (Kuudra "PAID CHEST REWARDS"
 * blocks, rare reward broadcasts, etc.) so they can be shown later as one consolidated list
 * instead of flooding chat line-by-line. View with /cedloot.
 *
 * Call init() once from CedClient.onInitializeClient(), same as DungeonState and CosmeticsSync --
 * the world-swap reset always runs, but the recordX() calls below are no-ops while the module
 * is disabled, so nothing accumulates unless it's toggled on in the GUI.
 */
object LootTracker : Module(
    "Loot Tracker",
    Category.Misc,
    "Tracks Kuudra/Dungeon reward drops into a running summary (/cedloot to view)"
) {

    data class LootEntry(var count: Int = 0)

    private val entries = LinkedHashMap<String, LootEntry>()
    private val rareRewards = mutableListOf<String>()
    private val dungeonRewards = mutableListOf<String>() // TODO: populate once patterns confirmed

    // Mirrors DungeonState's/ItemCooldowns' world-swap detection: reference-compare the
    // ClientLevel instance each tick so counts don't bleed between Kuudra/Dungeon runs.
    private var lastLevel: ClientLevel? = null

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val currentLevel = client.level
            if (currentLevel !== lastLevel) {
                lastLevel = currentLevel
                reset()
            }
        }
    }

    @Volatile
    var chestsOpened: Int = 0
        private set

    fun reset() {
        entries.clear()
        rareRewards.clear()
        dungeonRewards.clear()
        chestsOpened = 0
    }

    fun recordChestOpened() {
        if (!isEnabled) return
        chestsOpened++
    }

    /**
     * @param rawLine an already-stripped-of-color item line, e.g. "Kuudra Teeth x2"
     *                or "Crimson Essence x500 (x2)"
     */
    fun recordDrop(rawLine: String) {
        if (!isEnabled) return
        val (name, qty) = parseItemLine(rawLine) ?: return
        entries.getOrPut(name) { LootEntry() }.count += qty
    }

    fun recordRareReward(playerName: String, itemName: String, chestType: String) {
        if (!isEnabled) return
        rareRewards += "$playerName found $itemName ($chestType Chest)"
    }

    // TODO: call this once dungeon reward chat patterns are confirmed against a real log
    fun recordDungeonReward(rawLine: String) {
        if (!isEnabled) return
        dungeonRewards += rawLine
    }

    // Matches "Name xN" and the stacked variant "Name xN (xM)"
    private val ITEM_QTY_REGEX = Regex("""^(.*?)\s*x(\d+)(?:\s*\(x(\d+)\))?$""")

    private fun parseItemLine(line: String): Pair<String, Int>? {
        val clean = line.trim()
        if (clean.isEmpty()) return null

        val match = ITEM_QTY_REGEX.find(clean)
        return if (match != null) {
            val name = match.groupValues[1].trim()
            val baseQty = match.groupValues[2].toIntOrNull() ?: 1
            val multiplier = match.groupValues[3].toIntOrNull() ?: 1
            name to (baseQty * multiplier)
        } else {
            clean to 1
        }
    }

    fun buildSummaryLines(): List<String> {
        if (!isEnabled) {
            return listOf("[CC] Loot Tracker is disabled -- enable it in the GUI to start tracking.")
        }
        if (entries.isEmpty() && rareRewards.isEmpty() && dungeonRewards.isEmpty()) {
            return listOf("[CC] No loot tracked yet.")
        }

        val lines = mutableListOf<String>()
        lines += "[CC] Loot summary ($chestsOpened chests opened):"
        entries.entries
            .sortedByDescending { it.value.count }
            .forEach { (name, entry) -> lines += "  ${entry.count}x $name" }

        if (rareRewards.isNotEmpty()) {
            lines += "[CC] Rare rewards:"
            rareRewards.forEach { lines += "  $it" }
        }

        if (dungeonRewards.isNotEmpty()) {
            lines += "[CC] Dungeon rewards:"
            dungeonRewards.forEach { lines += "  $it" }
        }

        return lines
    }
}