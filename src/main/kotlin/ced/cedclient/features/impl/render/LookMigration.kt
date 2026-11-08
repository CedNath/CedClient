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
    const val CURRENT = 2

    fun run() {
        val from = Theme.lookVersion.value.toInt()
        if (from >= CURRENT) return
        if (from < 1) {
            val looks = HudLook.clear()
            var toggles = 0
            for (e in HudElement.all) {
                val m = e as? Module ?: continue
                m.settings.values.filter { it is BooleanSetting && HudCard.isBackgroundName(it.name) }.forEach { it.reset(); toggles++ }
            }
            // Dungeon Splits: tighter lines and plain split names are the new defaults.
            ced.cedclient.features.impl.funqol.DungeonSplits.settings.values
                .filter { it.name == "Line Spacing" || it.name == "Colored Split Names" }.forEach { it.reset() }
            Debug.line("Look Migration", "v1: reset $looks HUD editor choices and $toggles Background toggles")
        }
        if (from < 2) {
            // Compact Scoreboard / Tab moved onto the shared HUD card: their old 65 % default made it paler than every other card.
            listOf(CompactScoreboard, CompactTab).forEach { m -> m.settings.values.filter { it.name == "Panel Opacity" }.forEach { it.reset() } }
            Debug.line("Look Migration", "v2: Compact Scoreboard / Tab opacity back to default")
        }
        Theme.lookVersion.value = CURRENT.toDouble()
        ConfigManager.saveModulesOnly()
    }
}
