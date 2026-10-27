package ced.cedclient.features

/**
 * ClickGUI panels, in this order; empty ones are not shown. A module's panel comes from ModuleGroups (by name), the
 * category it is constructed with is only the fallback. Render / HUD / Misc-as-"Client" are the old categories that
 * modules still declare; ModuleGroups moves them all out.
 */
// displayName is the panel title; name stays the config key, so renaming a panel keeps its saved spot.
enum class Category(val displayName: String) {
    General("General"),
    Visuals("Visuals"),
//#if CHEAT
    Cheats("Cheats"),
//#endif
    Skyblock("Skyblock"),
    Dungeons("Dungeons"),
    Combat("Combat"),
    Farming("Farming"),
    Mining("Mining"),
    Rift("Rift"),
    Hunting("Hunting / Foraging"),
    Chat("Chat"),
    Cosmetic("Cosmetic"),
    Misc("Misc"),
    Dev("Dev"),
    Render("Render"),
    HUD("HUD"),
}
