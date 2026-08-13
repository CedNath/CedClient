package ced.cedclient.features.impl.misc

import ced.cedclient.config.ConfigManager
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.ui.clickgui.PanelConfig
import net.minecraft.client.Minecraft

object ResetPanels : Module("Reset Panels", Category.Misc) {

    private var lastRunMillis: Long = 0L
    private val debounceMs: Long = 1000L

    override fun onEnable() {
        val now = System.currentTimeMillis()
        if (now - lastRunMillis < debounceMs) return
        lastRunMillis = now

        try {
            val client = net.minecraft.client.Minecraft.getInstance()
            val screen = client.screen
            if (screen is ced.cedclient.ui.clickgui.ClickGUI) {
                screen.getPanels()?.let { ConfigManager.resetPanelsToDefaults(it) }
            } else {
                println("ResetPanels: ClickGUI not open, cannot reset live panel objects")
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
}


