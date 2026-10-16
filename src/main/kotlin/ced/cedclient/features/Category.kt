package ced.cedclient.features

/** ClickGUI panels, in this order. Modules inside a panel keep their registration order (see CedClient.registerModules). */
// displayName is the panel title; name stays the config key, so renaming a panel keeps its saved spot.
enum class Category(val displayName: String) {
    Misc("Client"),
    Skyblock("Skyblock"),
    Render("Render"),
    HUD("HUD"),
    Cosmetic("Cosmetic"),
    Cheats("Cheats"),
}
