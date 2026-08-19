package ced.cedclient.features

import ced.cedclient.config.ConfigManager
import ced.cedclient.features.settings.Setting

/**
 * Base class for a module. Settings are declared inside a module with
 * Kotlin property delegation, e.g.:
 *
 *     val range by NumberSetting("Range", 10.0, 1.0, 50.0)
 *
 * The `by` triggers Setting's provideDelegate(), which calls
 * registerSetting() below automatically — no manual addSettings() call
 * needed, and `settings` stays populated in declaration order.
 */
open class Module(
    val name: String,
    val category: Category,
    val description: String = ""
) {
    var enabled = false
        private set

    // Public getter for mixins and Kotlin code
    val isEnabled: Boolean
        get() = enabled

    var visible = true
    var keybind: Int = -1
    var expanded = false

    /**
     * All settings registered to this module, in declaration order.
     * Key is the setting's name.
     */
    val settings: LinkedHashMap<String, Setting<*>> = linkedMapOf()

    /**
     * Registers a [Setting] to this module and returns it. Called
     * automatically by Setting.provideDelegate() — you shouldn't need to
     * call this directly.
     */
    fun <K : Setting<*>> registerSetting(setting: K): K {
        settings[setting.name] = setting
        return setting
    }

    /**
     * Registers several settings at once. For modules whose settings are
     * declared as plain `private val x = BooleanSetting(...)` (no `by`) and
     * then registered explicitly in `init {}` — e.g. so post-construction
     * config like `x.advanced = true` stays possible, which `by` delegation
     * doesn't allow since the property type collapses to the setting's
     * value type, not the Setting object itself.
     */
    fun addSettings(vararg settings: Setting<*>) {
        for (setting in settings) registerSetting(setting)
    }

    /** Backward-compatible view for callers still iterating settings as a list (e.g. ConfigManager, GUI code mid-port). */
    fun getSettings(): List<Setting<*>> = settings.values.toList()

    open fun onEnable() {}
    open fun onDisable() {}

    fun toggle() {
        setEnabled(!enabled)
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return

        enabled = value
        println("Set $name → $enabled")

        if (enabled) onEnable() else onDisable()

        // Persist immediately, no matter what called this (GUI click OR keybind).
        // ConfigManager suppresses this during its own load/loadModulesOnly()
        // calls, so this only fires for real user-driven toggles.
        ConfigManager.saveModulesOnly()
    }
}