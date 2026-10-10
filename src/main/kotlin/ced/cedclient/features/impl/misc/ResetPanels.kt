package ced.cedclient.features.impl.misc

import ced.cedclient.config.ConfigManager
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.ui.clickgui.ClickGUI
import net.minecraft.client.Minecraft

/** Button: puts every ClickGUI panel back at its default position. */
object ResetPanels : Module("Reset Panels", Category.Misc, "Moves every panel back to its default position.") {

    override val isAction = true

    override fun onAction() {
        try {
            val screen = Minecraft.getInstance().screen
            if (screen is ClickGUI) {
                ConfigManager.resetPanelsToDefaults(screen.getPanels())
            } else {
                println("ResetPanels: ClickGUI not open, cannot reset live panel objects")
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
}
