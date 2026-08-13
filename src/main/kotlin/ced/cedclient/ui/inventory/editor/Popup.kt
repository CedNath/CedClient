package ced.cedclient.ui.inventory.editor

import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.gui.GuiGraphicsExtractor

interface Popup {
    fun render(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float)
    fun mouseClicked(mx: Int, my: Int, button: Int): Boolean
    fun mouseDragged(mx: Int, my: Int, button: Int, dx: Double, dy: Double): Boolean
    fun mouseReleased(mx: Int, my: Int, button: Int): Boolean
    fun mouseScrolled(mx: Int, my: Int, scrollX: Double, scrollY: Double): Boolean = false
    fun keyPressed(event: KeyEvent): Boolean
    fun charTyped(event: CharacterEvent): Boolean
    fun isInside(mx: Int, my: Int): Boolean
}