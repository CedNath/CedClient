package ced.cedclient.features.impl.render

import ced.cedclient.config.ConfigManager
import ced.cedclient.features.Module
import ced.cedclient.features.impl.misc.Theme
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.utils.Debug

/**
 * One-time look reset when a config from before the new look loads (docs/superpowers/specs/2026-11-08-premium-look-design.md).
 * Most players never touched their HUD backgrounds, so the saved values are just the old defaults and would keep
 * cards on HUDs that are now see-through. Clears the HUD editor's per-HUD background choices and puts every module's own
 * Background toggle back to its default. Positions, scales, opacity and every other setting stay.
 */
object LookMigration {
    const val CURRENT = 1

    fun run() {
        if (Theme.lookVersion.value >= CURRENT) return
        val looks = HudLook.clear()
        var toggles = 0
        for (e in HudElement.all) {
            val m = e as? Module ?: continue
            m.settings.values.filter { it is BooleanSetting && HudCard.isBackgroundName(it.name) }.forEach { it.reset(); toggles++ }
        }
        Theme.lookVersion.value = CURRENT.toDouble()
        ConfigManager.saveModulesOnly()
        Debug.line("Look Migration", "reset $looks HUD editor choices and $toggles Background toggles")
    }
}
