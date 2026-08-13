package ced.cedclient.config

import ced.cedclient.features.ModuleManager
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ModeSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.ui.clickgui.Panel
import com.google.gson.GsonBuilder
import net.minecraft.client.Minecraft
import java.io.File

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
    private val defaultPanelPositions: Map<String, Pair<Float, Float>> = mapOf(
        "Render" to (285f to 15f),
        "Funqol" to (15f to 15f),
        "Misc" to (555f to 15f)
    )

    /**
     * Helper: try to interpret an arbitrary JSON value as a Number.
     * Accepts Number or numeric String.
     */
    private fun asNumber(obj: Any?): Number? {
        return when (obj) {
            is Number -> obj
            is String -> obj.toDoubleOrNull()
            else -> null
        }
    }

    /**
     * Helper: try to interpret an arbitrary JSON value as a Boolean.
     * Accepts Boolean or String ("true"/"false").
     */
    private fun asBoolean(obj: Any?): Boolean? {
        return when (obj) {
            is Boolean -> obj
            is String -> obj.equals("true", ignoreCase = true)
            else -> null
        }
    }

    fun loadModulesOnly() {
        if (!configFile.exists()) return

        suppressAutoSave = true
        try {
            val json = try {
                gson.fromJson(configFile.readText(), Map::class.java)
            } catch (t: Throwable) {
                t.printStackTrace()
                return
            }

            val modules = json["modules"] as? Map<*, *> ?: return

            for ((name, moduleObj) in modules) {
                val module = ModuleManager.getModule(name.toString()) ?: continue
                val moduleData = moduleObj as? Map<*, *> ?: continue

                // Enabled
                val enabledVal = asBoolean(moduleData["enabled"]) ?: false
                module.setEnabled(enabledVal)

                // Expanded
                module.expanded = asBoolean(moduleData["expanded"]) ?: false

                val settingsMap = moduleData["settings"] as? Map<*, *> ?: continue

                for (setting in module.getSettings()) {
                    if (!settingsMap.containsKey(setting.name)) continue

                    val raw = settingsMap[setting.name]
                    when (setting) {
                        is BooleanSetting -> {
                            val v = asBoolean(raw)
                            if (v != null) setting.value = v
                        }
                        is NumberSetting -> {
                            val n = asNumber(raw)
                            if (n != null) setting.value = n.toDouble()
                        }
                        is ModeSetting -> {
                            if (raw != null) setting.value = raw.toString()
                        }
                    }
                }
            }
        } finally {
            suppressAutoSave = false
        }
    }

    fun save(panels: List<Panel>) {
        if (!configDir.exists()) configDir.mkdirs()

        val data = mutableMapOf<String, Any>()

        // -------------------------
        // SAVE MODULES + SETTINGS
        // -------------------------
        data["modules"] = buildModulesMap()

        // -------------------------
        // SAVE PANEL POSITIONS
        // -------------------------
        data["panels"] = panels.associate {
            it.category.name to mapOf(
                "x" to it.x,
                "y" to it.y
            )
        }

        // Inventory buttons are handled by InventoryButtonManager's own file

        configFile.writeText(gson.toJson(data))
    }

    // Saves ONLY the "modules" section, preserving whatever "panels" data is
    // already on disk. This lets us persist enabled/disabled + settings from
    // anywhere (e.g. a keybind) without needing a List<Panel>, which only
    // exists while the ClickGUI screen is open.
    fun saveModulesOnly() {
        if (suppressAutoSave) return
        if (!configDir.exists()) configDir.mkdirs()

        val existing: MutableMap<String, Any> = if (configFile.exists()) {
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(configFile.readText(), Map::class.java) as? Map<String, Any>)
                ?.toMutableMap() ?: mutableMapOf()
        } else {
            mutableMapOf()
        }

        existing["modules"] = buildModulesMap()

        configFile.writeText(gson.toJson(existing))
    }

    private fun buildModulesMap(): Map<String, Any> {
        return ModuleManager.modules.associate { module ->
            val moduleData = mutableMapOf<String, Any>()

            moduleData["enabled"] = module.enabled
            moduleData["expanded"] = module.expanded

            val settingsMap = mutableMapOf<String, Any>()
            for (setting in module.getSettings()) {
                when (setting) {
                    is BooleanSetting -> settingsMap[setting.name] = setting.value
                    is NumberSetting -> settingsMap[setting.name] = setting.value
                    is ModeSetting -> settingsMap[setting.name] = setting.value
                }
            }

            moduleData["settings"] = settingsMap
            module.name to moduleData
        }
    }
    fun resetPanelsToDefaults(panels: List<Panel>) {
        for (panel in panels) {
            val default = defaultPanelPositions[panel.category.name]
            if (default != null) {
                panel.x = default.first
                panel.y = default.second
            }
        }
        save(panels) // persist immediately so it survives restart
    }
    fun load(panels: List<Panel>) {
        try {
            // -------------------------
            // LOAD PANEL POSITIONS (with defaults) — runs even if config.json
            // doesn't exist yet, so first launch still gets the right layout
            // -------------------------
            val panelData = if (configFile.exists()) {
                val json = try {
                    gson.fromJson(configFile.readText(), Map::class.java)
                } catch (t: Throwable) {
                    t.printStackTrace()
                    emptyMap<Any, Any>()
                }
                json["panels"] as? Map<*, *> ?: emptyMap<Any, Any>()
            } else {
                emptyMap<Any, Any>()
            }

            for (panel in panels) {
                val entry = panelData[panel.category.name] as? Map<*, *>
                if (entry != null) {
                    val xNum = asNumber(entry["x"])
                    val yNum = asNumber(entry["y"])
                    if (xNum != null && yNum != null) {
                        panel.x = xNum.toFloat()
                        panel.y = yNum.toFloat()
                    } else {
                        // fallback to defaults if present
                        val default = defaultPanelPositions[panel.category.name]
                        if (default != null) {
                            panel.x = default.first
                            panel.y = default.second
                        }
                    }
                } else {
                    val default = defaultPanelPositions[panel.category.name]
                    if (default != null) {
                        panel.x = default.first
                        panel.y = default.second
                    }
                    // if there's neither a saved entry nor a default, panel keeps
                    // whatever position ClickGUI's init block computed for it
                }
            }

            if (!configFile.exists()) return

            suppressAutoSave = true
            try {
                val json = try {
                    gson.fromJson(configFile.readText(), Map::class.java)
                } catch (t: Throwable) {
                    t.printStackTrace()
                    return
                }

                // -------------------------
                // LOAD MODULES + SETTINGS
                // -------------------------
                val modules = json["modules"] as? Map<*, *> ?: emptyMap<Any, Any>()

                for ((name, moduleObj) in modules) {
                    val module = ModuleManager.getModule(name.toString()) ?: continue
                    val moduleData = moduleObj as? Map<*, *> ?: continue

                    // Enabled
                    module.setEnabled(asBoolean(moduleData["enabled"]) ?: false)

                    // Expanded
                    module.expanded = asBoolean(moduleData["expanded"]) ?: false

                    // Settings
                    val settingsMap = moduleData["settings"] as? Map<*, *> ?: emptyMap<Any, Any>()

                    for (setting in module.getSettings()) {
                        if (!settingsMap.containsKey(setting.name)) continue

                        val raw = settingsMap[setting.name]
                        when (setting) {
                            is BooleanSetting -> {
                                val v = asBoolean(raw)
                                if (v != null) setting.value = v
                            }
                            is NumberSetting -> {
                                val n = asNumber(raw)
                                if (n != null) setting.value = n.toDouble()
                            }
                            is ModeSetting -> {
                                if (raw != null) setting.value = raw.toString()
                            }
                        }
                    }
                }
            } finally {
                suppressAutoSave = false
            }

            // Inventory buttons are handled by InventoryButtonManager's own loader
        } catch (t: Throwable) {
            // Catch any unexpected parsing/runtime errors and log them instead of crashing
            t.printStackTrace()
            println("Warning: ConfigManager.load encountered an error; continuing with defaults where possible.")
        }
    }
}
