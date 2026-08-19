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
        get() = File(Minecraft.getInstance().gameDirectory, "cedclient")

    private val configFile: File
        get() = File(configDir, "config.json")

    // While true, saveModulesOnly() is a no-op. Set this during startup
    // loading so replaying saved enabled/disabled state doesn't trigger a
    // cascade of redundant writes -- see loadModulesOnly() / load().
    private var suppressAutoSave = false

    // Used only when a category has no saved position yet (first launch,
    // or a brand new category that's never been dragged before).
    // Panel.WIDTH is 320f as of the bigger-text pass -- 15px left margin,
    // 30px gutter between panels: 15, 15+320+30=365, 365+320+30=715.
    private val defaultPanelPositions: Map<String, Pair<Float, Float>> = mapOf(
        "Render" to (365f to 15f),
        "Funqol" to (15f to 15f),
        "Misc" to (715f to 15f)
    )

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
        configFile.writeText(gson.toJson(root))
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

    private fun applyModulesJson(modulesJson: JsonObject) {
        for ((name, element) in modulesJson.entrySet()) {
            val module = ModuleManager.getModule(name) ?: continue
            val moduleData = element.asJsonObject

            module.setEnabled(moduleData.get("enabled")?.asBoolean ?: false)
            module.expanded = moduleData.get("expanded")?.asBoolean ?: false

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
        }
    }

    fun loadModulesOnly() {
        val modulesJson = readRoot()?.get("modules")?.asJsonObject ?: return

        suppressAutoSave = true
        try {
            applyModulesJson(modulesJson)
        } finally {
            suppressAutoSave = false
        }
    }

    // Saves ONLY the "modules" section, preserving whatever "panels" data is
    // already on disk. This lets us persist enabled/disabled + settings from
    // anywhere (e.g. a keybind) without needing a List<PanelSetting>, which
    // only exists while the ClickGUI screen is open.
    fun saveModulesOnly() {
        if (suppressAutoSave) return

        val root = readRoot() ?: JsonObject()
        root.add("modules", buildModulesJson())
        writeRoot(root)
    }

    // -------------------------
    // PANEL LAYOUT
    // -------------------------

    private fun applyDefaultPosition(panel: PanelSetting) {
        val default = defaultPanelPositions[panel.category.name] ?: return
        panel.x = default.first
        panel.y = default.second
    }

    fun resetPanelsToDefaults(panels: List<PanelSetting>) {
        for (panel in panels) applyDefaultPosition(panel)
        save(panels) // persist immediately so it survives restart
    }

    fun save(panels: List<PanelSetting>) {
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

    fun load(panels: List<PanelSetting>) {
        try {
            val root = readRoot()

            // -------------------------
            // LOAD PANEL POSITIONS (with defaults) -- runs even if config.json
            // doesn't exist yet, so first launch still gets the right layout
            // -------------------------
            val panelsJson = root?.get("panels")?.asJsonObject
            for (panel in panels) {
                val entry = panelsJson?.get(panel.category.name)?.asJsonObject
                val x = entry?.get("x")?.asFloat
                val y = entry?.get("y")?.asFloat
                if (x != null && y != null) {
                    panel.x = x
                    panel.y = y
                    panel.extended = entry.get("extended")?.asBoolean ?: true
                } else {
                    applyDefaultPosition(panel)
                }
            }

            if (root == null) return

            // -------------------------
            // LOAD MODULES + SETTINGS
            // -------------------------
            val modulesJson = root.get("modules")?.asJsonObject ?: return

            suppressAutoSave = true
            try {
                applyModulesJson(modulesJson)
            } finally {
                suppressAutoSave = false
            }
        } catch (t: Throwable) {
            // Catch any unexpected parsing/runtime errors and log them instead of crashing
            t.printStackTrace()
            println("Warning: ConfigManager.load encountered an error; continuing with defaults where possible.")
        }
    }
}