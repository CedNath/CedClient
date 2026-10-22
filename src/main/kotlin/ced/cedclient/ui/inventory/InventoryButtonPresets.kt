package ced.cedclient.ui.inventory

import ced.cedclient.config.CedPaths
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.minecraft.resources.Identifier

/**
 * Named sets of inventory buttons: a few built-in ones plus the player's own, saved from the current buttons.
 * Applying one replaces the current buttons. Saved ones live in inventory_button_presets.json in the same format as
 * inventory_buttons.json.
 */
object InventoryButtonPresets {
    private val gson = Gson()
    private val file by lazy { CedPaths.file("inventory_button_presets.json") }
    private val saved = LinkedHashMap<String, List<Map<String, Any?>>>()

    /** A 20x20 item button left of the inventory: [slot] counts down the first column, then the next one out (7 per column). */
    private fun btn(slot: Int, name: String, command: String, item: String) = InvButton(
        name = name, xOff = -24 - (slot / 7) * 22, yOff = (slot % 7) * 22, width = 20, height = 20, command = command,
        icon = Identifier.parse("minecraft:$item"), iconIsItem = true
    )

    private val builtIn: Map<String, List<InvButton>> = linkedMapOf(
        "Skyblock Basics" to listOf(
            btn(0, "SkyBlock Menu", "sbmenu", "nether_star"), btn(1, "Pets", "pets", "bone"), btn(2, "Wardrobe", "wardrobe", "leather_chestplate"),
            btn(3, "Storage", "storage", "chest"), btn(4, "Ender Chest", "ec", "ender_chest"), btn(5, "Bazaar", "bz", "emerald"), btn(6, "Auction House", "ah", "gold_ingot"),
        ),
        "Dungeons" to listOf(
            btn(0, "Dungeon Hub", "warp dhub", "compass"), btn(1, "Party List", "pl", "paper"), btn(2, "Wardrobe", "wardrobe", "leather_chestplate"),
            btn(3, "Pets", "pets", "bone"), btn(4, "Equipment", "equipment", "diamond_chestplate"), btn(5, "Storage", "storage", "chest"), btn(6, "SkyBlock Menu", "sbmenu", "nether_star"),
        ),
        "All Warps" to listOf(
            "Hub" to "hub" to "compass", "Dungeon Hub" to "dhub" to "skeleton_skull", "Island" to "is" to "grass_block", "Garden" to "garden" to "wheat",
            "Barn" to "barn" to "hay_block", "Desert" to "desert" to "sand", "Gold Mine" to "gold" to "gold_ingot",
            "Deep Caverns" to "deep" to "iron_ingot", "Dwarven Forge" to "forge" to "anvil", "Crystal Hollows" to "ch" to "diamond", "Spider's Den" to "spider" to "cobweb",
            "The End" to "end" to "end_stone", "Dragon's Nest" to "drag" to "dragon_egg", "Crimson Isle" to "crimson" to "netherrack",
            "The Park" to "park" to "jungle_log", "Kuudra" to "kuudra" to "magma_cream", "Museum" to "museum" to "painting", "Wizard Tower" to "wizard" to "blaze_rod",
            "Castle" to "castle" to "stone_bricks", "Crypts" to "crypt" to "bone_block", "Dark Auction" to "da" to "gold_block",
        ).mapIndexed { i, (nc, item) -> btn(i, nc.first, "warp ${nc.second}", item) },
        "Farming" to listOf(
            btn(0, "Garden", "warp garden", "wheat"), btn(1, "Island", "is", "grass_block"), btn(2, "Bazaar", "bz", "emerald"),
            btn(3, "Pets", "pets", "bone"), btn(4, "Wardrobe", "wardrobe", "leather_chestplate"), btn(5, "Storage", "storage", "chest"), btn(6, "SkyBlock Menu", "sbmenu", "nether_star"),
        ),
    )

    init {
        runCatching {
            if (file.exists()) gson.fromJson<Map<String, List<Map<String, Any?>>>>(file.readText(), object : TypeToken<Map<String, List<Map<String, Any?>>>>() {}.type)
                ?.let { saved.putAll(it) }
        }
    }

    /** Built-in presets first, then the player's own. */
    fun names(): List<String> = builtIn.keys.toList() + saved.keys

    fun isBuiltIn(name: String) = name in builtIn

    /** Replaces the current buttons with preset [name]; false when there is none by that name. */
    fun apply(name: String): Boolean {
        val list = builtIn[name]?.map { it.copy() } ?: saved[name]?.map { InventoryButtonManager.fromMap(it) } ?: return false
        InventoryButtonManager.ensureLoaded()
        InventoryButtonManager.buttons.clear()
        InventoryButtonManager.buttons.addAll(list)
        InventoryButtonManager.save()
        return true
    }

    /** Saves the current buttons as [name] (replacing a saved preset of that name). Built-in names are refused. */
    fun saveCurrent(name: String): Boolean {
        if (name.isBlank() || name in builtIn) return false
        InventoryButtonManager.ensureLoaded()
        saved[name] = InventoryButtonManager.buttons.map { InventoryButtonManager.toMap(it) }
        write()
        return true
    }

    fun delete(name: String): Boolean = (saved.remove(name) != null).also { if (it) write() }

    private fun write() {
        runCatching { file.parentFile?.mkdirs(); file.writeText(gson.toJson(saved)) }
    }
}
