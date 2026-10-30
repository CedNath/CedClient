package ced.cedclient.features

/**
 * ClickGUI panels, in this order; empty ones are not shown. A module's panel comes from ModuleGroups (by name), the
 * category it is constructed with is only the fallback. Render / HUD / Misc-as-"Client" are the old categories that
 * modules still declare; ModuleGroups moves them all out.
 */
// displayName is the panel title; name stays the config key, so renaming a panel keeps its saved spot.
enum class Category(val displayName: String) {
    General("General"),
    Dungeons("Dungeons"),
    Combat("Slayer & Combat"),
    Farming("Farming & Garden"),
    Fishing("Fishing"),
    Minigames("Crimson Isle, Dojo & Minigames"),
    // Config key stays "Misc" (saved panel positions); the panel is the HUD & statistics one.
    Misc("HUD & Statistics"),
    Inventory("Inventory, Items & Economy"),
    Visuals("Visuals, Rendering & ESP"),
    Tooltips("Tooltips & Enchantments"),
    Chat("Chat & Communication"),
    Cosmetic("Cosmetics & Appearance"),
    Movement("Movement & Navigation"),
    Sound("Sound & Audio"),
//#if CHEAT
    Cheats("Cheats"),
//#endif
    Skyblock("Skyblock"),
    Mining("Mining"),
    Rift("Rift"),
    Hunting("Gathering"),
    Dev("Dev"),
    Render("Render"),
    HUD("HUD"),
}
