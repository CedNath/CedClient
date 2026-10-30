package ced.cedclient

import ced.cedclient.ui.clickgui.ClickGUI
import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

/** Mod Menu's "Configure" button opens the ClickGUI. */
class CedModMenu : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> = ConfigScreenFactory { ClickGUI() }
}
