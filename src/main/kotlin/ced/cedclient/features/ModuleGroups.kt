package ced.cedclient.features

/**
 * Which ClickGUI panel each module is in, and its order there (first = top). Names a build doesn't have are skipped;
 * a module not listed stays in the category it was constructed with, after the listed ones. A panel whose Category
 * this build lacks (Cheats in Legit) leaves its modules in their own category.
 */
object ModuleGroups {
    private val panels: Map<String, List<String>> = linkedMapOf(
        "General" to listOf("Mod Settings", "ScreenshotTweaks", "Borderless Fullscreen", "AutoSprint", "HideRecipeBook", "Keep Connection", "Texture Pack Fix", "Config Share", "Hotbar Keybinds"),
        "Visuals" to listOf("Fullbright", "Saturation", "Clean View", "Render Optimizer", "Zoom", "Custom Sky", "Crosshair", "Hiders", "Vanilla Textures", "Old Stars",
            "Custom Tooltips", "Scrollable Tooltips", "Tooltip Dates", "Item Info Tooltip", "Item Rarity", "Enchant Colors", "Perspective"),
        "Cheats" to listOf("EntityESP", "BlockESP", "Freecam", "Secret Hitboxes", "Auto Fusion", "Auto Terminals", "FishingHelper", "Beach Ball Helper", "LassoHelper", "PangolinCatcher", "CoralotHelper", "TropicalFishHelper",
            "Slayer Helper", "FarmHelper", "Anvil Helper", "TinyDancerHelper", "Auto Experiments", "Beacon Solver", "Dojo Helper"),
        "Skyblock" to listOf("Auction Price Input", "SearchOverlay", "Item Value", "Item Cooldowns", "Chat Channel HUD", "Deployable Timers", "Ragnarock Timer", "WarpShortcuts",
            "InventoryButtons", "Daily Tasks", "Event Countdown", "Pet Display", "SB Kick Timer", "Stat Bars", "Skill Display", "Storage Overlay", "Protect Item", "Slot Binding", "Loadout Keybinds", "Wardrobe Keybinds", "Waypoints"),
        "Dungeons" to listOf("Dungeon Map", "Leap Menu", "Leap Counter", "Terminal Solver", "Dungeon Splits", "Door Highlight", "StarredMobHighlight", "Room Times", "Run Profit",
            "Secret Clicked", "Left Click Secrets", "Secret HUD", "Simon Says Lines", "Positional Messages", "Explosive Shot Damage", "F7SoundCap", "CustomHypeSound",
            "Dungeonbreaker Sound", "Auto Requeue", "Terminal Simulator"),
        "Combat" to listOf("Slayer Tracker", "Revenant Horror", "Tarantula Broodfather", "Sven Packmaster", "Voidgloom Seraph", "Inferno Demonlord", "Riftstalker Bloodfiend"),
        "Farming" to listOf("Pest Highlighter"),
        "Rift" to listOf("Sun Gecko"),
        "Hunting" to listOf("Critter Safari", "FloorDrops"),
        "Chat" to listOf("Chat Features", "Chat Tabs", "Smooth Chat", "Chat Ping", "ChatFilter", "Party Commands", "Chat Prefix"),
        "Cosmetic" to listOf("Cosmetics", "Cosmetic Blocks", "Custom Nametag", "Player Scale", "China Hat", "Hide Armor", "Falling Petals"),
        "Misc" to listOf("Music", "Time HUD", "Armor HUD", "Compact Scoreboard", "Scoreboard Toggle", "Compact Tab", "Tab List Toggle", "Mute Sounds", "ADHD", "Action Bar HUD", "Ping / FPS"),
        "Dev" to listOf("Advanced Mode", "Debug Panel", "Copy Item Data", "Copy Entity Data", "Dumpers"),
    )

    private val panelOf: Map<String, String> = panels.flatMap { (panel, names) -> names.map { it to panel } }.toMap()

    fun categoryOf(name: String, fallback: Category): Category =
        panelOf[name]?.let { panel -> Category.entries.firstOrNull { it.name == panel } } ?: fallback

    /** [modules] of one panel in list order, each with its heading (always null: panels have no sub-headings now). */
    fun arrange(category: Category, modules: List<Module>): List<Pair<String?, Module>> {
        val order = panels[category.name]?.withIndex()?.associate { it.value to it.index } ?: emptyMap()
        return modules.sortedBy { order[it.name] ?: Int.MAX_VALUE }.map { null to it }
    }
}
