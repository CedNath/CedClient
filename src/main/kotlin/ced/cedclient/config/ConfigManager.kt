package ced.cedclient.config

import ced.cedclient.features.ModuleManager
import ced.cedclient.features.settings.Saving
import ced.cedclient.ui.clickgui.PanelSetting
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import java.io.File

/**
 * Phase 3 rewrite: walks Module.settings (a LinkedHashMap<String, Setting<*>>)
 * instead of the old Module.getSettings() list, and persists each setting
 * through its own Saving.read()/write() rather than a central `when` over
 * concrete setting types (BooleanSetting/NumberSetting/ModeSetting), which no
 * longer exist in that shape.
 *
 * Panel layout now persists via PanelSetting (x/y/extended), not by reading
 * fields off the real Panel view object -- that object is still Phase 4.
 */
object ConfigManager {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val configDir: File
        get() = CedPaths.dir

    private val configFile: File
        get() = File(configDir, "config.json")

    // While true, saveModulesOnly() is a no-op. Set this during startup
    // loading so replaying saved enabled/disabled state doesn't trigger a
    // cascade of redundant writes -- see loadModulesOnly() / load().
    private var suppressAutoSave = false

    /** True while the saved config is being replayed (modules switch on without the player doing it). */
    val isLoading: Boolean get() = suppressAutoSave

    // Used only when a category has no saved position yet (first launch,
    // or a brand new category that's never been dragged before).
    // Panel.WIDTH is 320f as of the bigger-text pass -- 15px left margin,
    /** Panels that existed before the category reshuffle; a saved layout containing them is reset once. */
    private val legacyPanels = setOf("Funqol", "CedClient", "Render", "HUD")

    private fun readRoot(): JsonObject? {
        if (!configFile.exists()) return null
        return try {
            JsonParser.parseString(configFile.readText()).asJsonObject
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    private fun writeRoot(root: JsonObject) {
        if (!configDir.exists()) configDir.mkdirs()
        // Temp file then move: a crash mid-write used to leave a cut-off config that then failed to parse.
        val tmp = File(configDir, "config.json.tmp")
        tmp.writeText(gson.toJson(root))
        try {
            java.nio.file.Files.move(tmp.toPath(), configFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(tmp.toPath(), configFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // -------------------------
    // MODULES + SETTINGS
    // -------------------------

    private fun buildModulesJson(): JsonObject {
        val modulesJson = JsonObject()

        for (module in ModuleManager.modules) {
            val moduleData = JsonObject()
            moduleData.addProperty("enabled", module.enabled)
            moduleData.addProperty("expanded", module.expanded)

            val settingsJson = JsonObject()
            for (setting in module.settings.values) {
                if (setting is Saving) {
                    settingsJson.add(setting.name, setting.write(gson))
                }
            }
            moduleData.add("settings", settingsJson)

            modulesJson.add(module.name, moduleData)
        }

        return modulesJson
    }

    /** Renamed modules: read old saves into the new name (until the new name has been saved once). */
    private fun savedName(name: String, saved: JsonObject): String = when {
        name == "Mod Settings" && !saved.has("Mod Settings") -> if (saved.has("Client Settings")) "Client Settings" else "Theme"
        // "Spotify" became "Music" (it reads Apple Music too): old saves carry over.
        name == "Spotify" && !saved.has("Music") -> "Music"
        // Old "Slayer Helper" (Skyblock) is now "Slayer Tracker"; the cheats module took the "Slayer Helper" name.
        !saved.has("Slayer Tracker") && name == "Slayer Helper" -> "Slayer Tracker"
        !saved.has("Slayer Tracker") && name == "Slayer Cheats" -> "Slayer Helper"
        // "Chat History" and "Chat Ping" merged into "Chat Features" (on / off comes from Chat History, see mergedIn).
        !saved.has("Chat Features") && (name == "Chat History" || name == "Chat Ping") -> "Chat Features"
        else -> name
    }

    /** Old modules merged into another: only their settings are read, not their on / off. */
    private val mergedIn = setOf("Chat Ping")

    private fun applyModulesJson(modulesJson: JsonObject) {
        for ((name, element) in modulesJson.entrySet()) {
            val module = ModuleManager.getModule(savedName(name, modulesJson)) ?: continue
            // One module's onEnable throwing (e.g. something that needs the world) must not stop the modules after it
            // from being restored: they would stay off and the next save would write that over the real config.
            try {
                val moduleData = element.asJsonObject

                if (name !in mergedIn) {
                    module.expanded = moduleData.get("expanded")?.asBoolean ?: false
                    module.setEnabled(moduleData.get("enabled")?.asBoolean ?: false)
                }

                val settingsJson = moduleData.get("settings")?.asJsonObject ?: continue
                for (setting in module.settings.values) {
                    if (setting !is Saving) continue
                    val raw = settingsJson.get(setting.name) ?: continue
                    try {
                        setting.read(raw, gson)
                    } catch (t: Throwable) {
                        // one bad/renamed setting shouldn't take the rest of the config down with it
                        t.printStackTrace()
                    }
                }
            } catch (t: Throwable) {
                println("Warning: could not restore ${module.name}: ${t.message}")
                t.printStackTrace()
            }
        }
    }

    /** False until the saved config has been replayed; saving before that would write the defaults over it. */
    private var loadedOnce = false

    fun loadModulesOnly() {
        try {
            val modulesJson = readRoot()?.get("modules")?.asJsonObject ?: return

            suppressAutoSave = true
            try {
                applyModulesJson(modulesJson)
            } finally {
                suppressAutoSave = false
            }
        } finally {
            loadedOnce = true
        }
    }

    // Saves ONLY the "modules" section, preserving whatever "panels" data is
    // already on disk. This lets us persist enabled/disabled + settings from
    // anywhere (e.g. a keybind) without needing a List<PanelSetting>, which
    // only exists while the ClickGUI screen is open.
    fun saveModulesOnly() {
        if (suppressAutoSave || !loadedOnce) return

        val root = readRoot() ?: JsonObject()
        root.add("modules", buildModulesJson())
        writeRoot(root)
    }

    // -------------------------
    // PANEL LAYOUT
    // -------------------------

    private fun applyDefaultPosition(panel: PanelSetting, index: Int) {
        // Default layout: one row of 350 px columns in panel order; the ClickGUI scrolls sideways to reach the rest.
        val default = 15f + 350f * index to 15f
        panel.x = default.first
        panel.y = default.second
    }

    fun resetPanelsToDefaults(panels: List<PanelSetting>) {
        panels.forEachIndexed { i, panel -> applyDefaultPosition(panel, i) }
        save(panels) // persist immediately so it survives restart
    }

    fun save(panels: List<PanelSetting>) {
        if (!loadedOnce) return
        val root = JsonObject()
        root.add("modules", buildModulesJson())

        val panelsJson = JsonObject()
        for (panel in panels) {
            val entry = JsonObject()
            entry.addProperty("x", panel.x)
            entry.addProperty("y", panel.y)
            entry.addProperty("extended", panel.extended)
            panelsJson.add(panel.category.name, entry)
        }
        root.add("panels", panelsJson)

        writeRoot(root)
    }

    /**
     * Panel layout only, for the ClickGUI as it opens. Module settings are NOT re-read here: they are loaded once at
     * startup (loadModulesOnly) and saved on every change, so the in-memory state is already current -- re-applying
     * them on every GUI open was a wasted disk read plus a re-apply of every setting, a hitch on the opening frame.
     */
    fun loadPanels(panels: List<PanelSetting>) {
        try {
            val root = readRoot()

            // -------------------------
            // LOAD PANEL POSITIONS (with defaults) -- runs even if config.json
            // doesn't exist yet, so first launch still gets the right layout
            // -------------------------
            // Old layout (pre HUD/Cosmetic/Skyblock split): its Render/Misc positions would overlap the new
            // panels, so start from the defaults instead.
            val panelsJson = root?.get("panels")?.asJsonObject
                ?.takeUnless { saved -> saved.keySet().any { it in legacyPanels } || !saved.has("Movement") }
            for ((index, panel) in panels.withIndex()) {
                val entry = panelsJson?.get(panel.category.name)?.asJsonObject
                val x = entry?.get("x")?.asFloat
                val y = entry?.get("y")?.asFloat
//#if CHEAT
                // Cheats briefly defaulted to under Skyblock; a panel still at that spot gets the new default.
                val oldCheatsSpot = panel.category.name == "Cheats" && x == 1065f && y == 300f
                if (x != null && y != null && !oldCheatsSpot) {
//#else
//$                if (x != null && y != null) {
//#endif
                    panel.x = x
                    panel.y = y
                    panel.extended = entry.get("extended")?.asBoolean ?: true
                } else {
                    applyDefaultPosition(panel, index)
                }
            }
        } catch (t: Throwable) {
            // Catch any unexpected parsing/runtime errors and log them instead of crashing
            t.printStackTrace()
            println("Warning: ConfigManager.loadPanels encountered an error; using default panel positions.")
        }
    }

    // -------------------------
    // SHARE CODE (export / import)
    // -------------------------

    private const val CODE_PREFIX = "CC1:"

    /** Every module's on / off and settings as one pasteable line: "CC1:" + base64(gzip(json)). Panel layout is not in it. */
    fun exportCode(): String {
        val raw = gson.toJson(buildModulesJson()).toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(raw) }
        return CODE_PREFIX + java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** Applies a code from [exportCode]; returns how many modules it covered, or null if the text isn't a valid code. */
    fun importCode(code: String): Int? {
        val text = code.filterNot { it.isWhitespace() }
        if (!text.startsWith(CODE_PREFIX)) return null
        return try {
            val bytes = java.util.Base64.getDecoder().decode(text.removePrefix(CODE_PREFIX))
            val json = java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
            val modules = JsonParser.parseString(json).asJsonObject
            suppressAutoSave = true
            try {
                applyModulesJson(modules)
            } finally {
                suppressAutoSave = false
            }
            saveModulesOnly()
            modules.size()
        } catch (t: Throwable) {
            null
        }
    }
}
