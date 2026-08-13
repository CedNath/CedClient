package ced.cedclient.ui.clickgui

import net.minecraft.client.Minecraft

object ClickGUIPopupHost {
    fun close() {
        (Minecraft.getInstance().screen as? ClickGUI)?.closePopup()
    }
}