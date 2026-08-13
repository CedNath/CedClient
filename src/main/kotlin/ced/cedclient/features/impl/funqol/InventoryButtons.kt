package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.ui.inventory.InventoryButtonManager
import ced.cedclient.utils.Debug

object InventoryButtons : Module(
    "InventoryButtons",
    Category.Misc,
    description = "Adds customizable NEU-style buttons to your inventory."
) {

    // Overlay editor state
    var editorMode = false
    var snapEnabled = true
    val isEditorMode: Boolean
        get() = editorMode

    // Text rendering behavior
    var showTextOnHover = true
    var hideBlankName = true

    // Icon rendering
    var enableIcons = true

    // Default button size for AddButtonPopup
    var defaultWidth = 20
    var defaultHeight = 20
    override fun onEnable() {
        InventoryButtonManager.ensureLoaded()
    }
    override fun onDisable() {
        editorMode = false
    }

    // GUI actions
    private val openEditor = ActionSetting("Open Editor") {
        InventoryButtonManager.ensureLoaded()
        Debug.log("DEBUG: InventoryButtons.openEditor fired")
        Debug.log("OPEN EDITOR — buttons = ${InventoryButtonManager.buttons.size}")

        editorMode = true
        println("STEP 1: editorMode set to $editorMode")

        // ⭐ Instantly open the inventory so the editor is visible immediately
        val mc = net.minecraft.client.Minecraft.getInstance()
        val player = mc.player
        if (player != null) {
            mc.setScreen(net.minecraft.client.gui.screens.inventory.InventoryScreen(player))
        }
    }


    private val closeEditor = ActionSetting("Close Editor") {
        InventoryButtonManager.ensureLoaded()
        Debug.log("DEBUG: InventoryButtons.closeEditor fired")
        Debug.log("CLOSE EDITOR — buttons = ${InventoryButtonManager.buttons.size}")
        // ⭐ Save all button positions before editor closes
        InventoryButtonManager.save()

        editorMode = false
    }



    init {
        addSettings(openEditor, closeEditor)
    }
}
