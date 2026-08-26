package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.ui.inventory.InventoryButtonManager
import ced.cedclient.ui.inventory.editor.InvButtonEditorScreen
import ced.cedclient.utils.Debug
import net.minecraft.client.Minecraft

object InventoryButtons : Module(
    "InventoryButtons",
    Category.Misc,
    description = "Adds customizable NEU-style buttons to your inventory."
) {

    var snapEnabled = true

    // Text rendering behavior
    var showTextOnHover = true
    var hideBlankName = true

    // Icon rendering
    var enableIcons = true

    // Default button size for new buttons
    var defaultWidth = 20
    var defaultHeight = 20

    override fun onEnable() {
        InventoryButtonManager.ensureLoaded()
    }

    // GUI actions
    private val openEditor = ActionSetting("Open Editor") {
        InventoryButtonManager.ensureLoaded()
        Debug.log("InventoryButtons: opening InvButtonEditorScreen -- buttons = ${InventoryButtonManager.buttons.size}")

        Minecraft.getInstance().gui.setScreen(InvButtonEditorScreen())
    }

    init {
        addSettings(openEditor)
    }
}