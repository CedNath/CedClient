package ced.cedclient.features

/**
 * ClickGUI sub-headings: per panel (by Category name, so the same list works on every branch), the headings in order
 * and the modules (by name) under each. Modules a build doesn't have are skipped; unlisted ones go last, no heading.
 */
object ModuleGroups {
    private val groups: Map<String, List<Pair<String, List<String>>>> = mapOf(
        "Render" to listOf(
            "Highlights" to listOf("StarredMobHighlight", "FloorDrops"),
            "World" to listOf("Fullbright", "Clean View", "Render Optimizer", "Zoom"),
            "Items & Tooltips" to listOf("Vanilla Textures", "Custom Tooltips", "Scrollable Tooltips"),
        ),
        "HUD" to listOf(
            "Scoreboard & Tab" to listOf("Compact Scoreboard", "Compact Tab"),
            "Chat & Action Bar" to listOf("Chat Channel HUD", "Action Bar HUD"),
            "Info" to listOf("Time HUD", "Item Cooldowns", "Daily Tasks"),
        ),
        "Cosmetic" to listOf(
            "World" to listOf("Cosmetic Blocks"),
            "Players" to listOf("Custom Nametag", "Cosmetics", "Player Scale"),
        ),
        "Skyblock" to listOf(
            "Dungeons" to listOf("Dungeon Splits", "Positional Messages", "F7SoundCap", "CustomHypeSound", "Explosive Shot Damage"),
            "Hunting" to listOf("Critter Safari"),
            "Rift" to listOf("Sun Gecko"),
            "Slayers" to listOf("Slayer Tracker"),
            "Trading" to listOf("SearchOverlay", "Auction Price Input", "Item Value"),
        ),
        "Cheats" to listOf(
            "ESP & Camera" to listOf("EntityESP", "BlockESP", "Freecam", "Secret Hitboxes"),
            "Fishing & Hunting" to listOf("FishingHelper", "LassoHelper", "PangolinCatcher(!!!)", "CoralotHelper(!!!)", "TropicalFishHelper(!!!)", "Auto Fusion"),
            "Slayers" to listOf("Slayer Helper"),
            "Minigames" to listOf("TinyDancerHelper", "Auto Experiments"),
        ),
        "Misc" to listOf(
            "General" to listOf("Client Settings", "AutoSprint", "WarpShortcuts", "InventoryButtons", "HideRecipeBook"),
            "Chat" to listOf("Chat Features", "ChatFilter"),
            "Window & Connection" to listOf("ScreenshotTweaks", "Borderless Fullscreen", "Keep Connection"),
            "Panels" to listOf("Advanced Mode", "Reset Panels"),
        ),
    )

    /** [modules] of one panel in heading order, each with its heading (null = no heading). */
    fun arrange(category: Category, modules: List<Module>): List<Pair<String?, Module>> {
        val list = groups[category.name] ?: return modules.map { null to it }
        val byName = modules.associateBy { it.name }
        val placed = list.flatMap { (heading, names) -> names.mapNotNull { byName[it] }.map { heading to it } }
        val rest = modules.filter { m -> placed.none { it.second === m } }.map { null to it }
        return placed + rest
    }
}
