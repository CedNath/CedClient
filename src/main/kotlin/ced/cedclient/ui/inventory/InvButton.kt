package ced.cedclient.ui.inventory

import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import ced.cedclient.features.impl.misc.InventoryButtons

data class InvButton(
    var name: String,
    var xOff: Int,
    var yOff: Int,
    var width: Int,
    var height: Int,
    var command: String,

    // Button Colors
    var bgColor: Int = 0xFF2A2A2A.toInt(),
    var bgHoverColor: Int = 0xFF3A3A3A.toInt(),
    var borderColor: Int = 0xFF000000.toInt(),

    // Icon data
    var icon: Identifier? = null,
    var iconIsItem: Boolean = false,

    // Behavior flags
    var showTextOnHover: Boolean = true,
    var hideBlankName: Boolean = true
) {

    fun onClick() {
        val mc = Minecraft.getInstance()

        if (!InventoryButtons.enabled) return
        if (mc.gui.screen() !is InventoryScreen) return

        if (command.isNotBlank()) {
            val player = mc.player ?: return
            player.connection.sendCommand(command)
        }
    }
}