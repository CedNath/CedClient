package ced.cedclient.ui.inventory.editor

import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo

fun EditBox.mouseClicked(mx: Int, my: Int, button: Int): Boolean {
    val info = MouseButtonInfo(
        button, // button index
        0       // no modifiers
    )

    val event = MouseButtonEvent(
        mx.toDouble(),
        my.toDouble(),
        info
    )

    return this.mouseClicked(event, true)
}
