package ced.cedclient.features

/**
 * Which ClickGUI panel each module is in, and its order there (first = top). Names a build doesn't have are skipped;
 * a module not listed stays in the category it was constructed with, after the listed ones. A panel whose Category
 * this build lacks (Cheats in Legit) leaves its modules in their own category.
 */
object ModuleGroups {
    private val panels: Map<String, List<String>> = linkedMapOf(
        "General" to listOf("Mod Settings", "Advanced Mode", "Config Share", "Debug Panel", "Dumpers", "Freeze Capture", "Keep Connection", "Mod Hider", "ScreenshotTweaks", "Screenshot Preview", "ADHD", "Borderless Fullscreen"),
        "Dungeons" to listOf("Dungeon Map", "Dungeon Splits", "Door Highlight", "Gate Highlight", "StarredMobHighlight", "Room Times", "Room Alerts", "Run Profit", "Secret Clicked", "Left Click Secrets", "Secret HUD", "Secret Hitboxes", "Simon Says Lines", "Positional Messages", "Terminal Solver", "Terminal Simulator", "Terminal ESP", "Teammate ESP", "Wither ESP", "Blood Camp", "Wither Dragons", "Class Colors", "F7 Titles", "F4 Features", "Architect Draft", "Leap Menu", "Leap Counter", "Auto Requeue", "Auto Terminals", "Auto Fusion", "Explosive Shot Damage", "Warp Cooldown", "Tick Timers", "Mask Timers", "Auto Experiments", "FloorDrops", "Score Calculator", "Puzzle Solvers"),
        "Combat" to listOf("Slayer Helper", "Slayer Tracker", "Revenant Horror", "Tarantula Broodfather", "Sven Packmaster", "Voidgloom Seraph", "Inferno Demonlord", "Riftstalker Bloodfiend", "Gyro Helper", "Auto Clicker", "Arrow Align Triggerbot", "Lights Device Triggerbot", "Sword Blocking", "Ragnarock Timer"),
        "Farming" to listOf("FarmHelper", "Pest Highlighter", "Sun Gecko", "Trevor Helper", "No Item Place", "Critter Safari"),
        "Fishing" to listOf("FishingHelper", "Beach Ball Helper", "LassoHelper", "PangolinCatcher", "CoralotHelper", "TropicalFishHelper"),
        "Minigames" to listOf("Dojo Helper", "Snappy Tappy", "TinyDancerHelper", "Beacon Solver"),
        "Misc" to listOf("Action Bar HUD", "Armor HUD", "Compact Scoreboard", "Scoreboard Toggle", "Compact Tab", "Tab List Toggle", "Stat Bars", "Skill Display", "Ping / FPS", "Time HUD", "Pet Display", "Blessing Display", "Chat Channel HUD", "Item Cooldowns", "Deployable Timers", "Event Countdown", "SB Kick Timer"),
        "Inventory" to listOf("InventoryButtons", "Storage Overlay", "Protect Item", "Slot Binding", "Loadout Keybinds", "Wardrobe Keybinds", "Auction Price Input", "Item Value", "Item Info Tooltip", "Item Rarity", "Copy Item Data", "Copy Entity Data", "Hotbar Keybinds", "Command Keybinds", "SearchOverlay", "Daily Tasks", "Anvil Helper"),
        "Visuals" to listOf("Fullbright", "Saturation", "Clean View", "Render Optimizer", "Zoom", "Custom Sky", "Crosshair", "Hiders", "Vanilla Textures", "Old Stars", "EntityESP", "BlockESP", "Texture Pack Fix", "Lava To Water", "Title Background", "Arrow Fix"),
        "Tooltips" to listOf("Custom Tooltips", "Scrollable Tooltips", "Tooltip Dates", "Enchant Colors"),
        "Chat" to listOf("Chat Features", "Chat Tabs", "Smooth Chat", "Chat Ping", "ChatFilter", "Party Commands", "Chat Prefix"),
        "Cosmetic" to listOf("Cosmetics", "Cosmetic Blocks", "Custom Nametag", "Player Scale", "China Hat", "Hide Armor", "Falling Petals", "HideRecipeBook"),
        "Movement" to listOf("AutoSprint", "Hub Map", "Waypoints", "WarpShortcuts", "Spring Boots", "Freecam", "Perspective", "Camera", "Etherwarp"),
        "Sound" to listOf("CustomHypeSound", "Mute Sounds", "Dungeonbreaker Sound", "F7SoundCap", "Music"),
    )

    private val panelOf: Map<String, String> = panels.flatMap { (panel, names) -> names.map { it to panel } }.toMap()

//#if CHEAT
    /** Modules that move into the Cheats panel while Theme > Separate Cheats Panel is on. */
    private val cheatNames = setOf("EntityESP", "BlockESP", "Freecam", "Secret Hitboxes", "Auto Clicker", "Arrow Align Triggerbot", "Lights Device Triggerbot", "Auto Fusion", "Auto Terminals",
        "FishingHelper", "Beach Ball Helper", "LassoHelper", "PangolinCatcher", "CoralotHelper", "TropicalFishHelper", "Slayer Helper", "FarmHelper", "Anvil Helper", "TinyDancerHelper",
        "Auto Experiments", "Beacon Solver", "Dojo Helper", "Snappy Tappy")
//#endif

    /** The panel (a Category, by name) [name] belongs to: ModuleGroups' choice, else the category it was constructed with. */
    fun categoryOf(name: String, fallback: Category): Category {
//#if CHEAT
        if (name in cheatNames && ced.cedclient.features.impl.misc.Theme.separateCheats.value) return Category.Cheats
//#endif
        return panelOf[name]?.let { list -> Category.entries.firstOrNull { it.name == list } } ?: fallback
    }

    /** [modules] of one panel, in list order, then any unlisted ones. No sub-headings: every panel is one list. */
    fun arrange(category: Category, modules: List<Module>): List<Pair<String?, Module>> =
        modules.sortedBy { m -> panels[category.name]?.indexOf(m.name)?.takeIf { it >= 0 } ?: Int.MAX_VALUE }.map { null to it }
}
