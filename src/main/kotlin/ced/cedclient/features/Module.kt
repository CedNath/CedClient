package ced.cedclient.features

import ced.cedclient.config.ConfigManager
import ced.cedclient.features.impl.funqol.FishingHelper
import ced.cedclient.ui.clickgui.ModuleButton
import ced.cedclient.features.settings.Setting

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

    private val settings = mutableListOf<Setting<*>>()

    fun addSettings(vararg settings: Setting<*>) {
        this.settings.addAll(settings)
    }

    fun getSettings(): List<Setting<*>> = settings

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