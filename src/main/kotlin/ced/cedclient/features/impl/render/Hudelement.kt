package ced.cedclient.features.impl.render

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Anything that can be dragged/resized from MasterHudEditScreen. Implemented
 * by EntityESPHud and TimeHud; anything new that wants to show up in the
 * shared editor just implements this and gets added to
 * MasterHudEditScreen.elements.
 */
interface HudElement {
    /** Shown above the panel in the editor so multiple panels aren't ambiguous. */
    val label: String

    var panelX: Int
    var panelY: Int
    var panelScale: Float

    /** Logical (unscaled) size from the last render — used for hit-testing. */
    val lastWidth: Int
    val lastHeight: Int

    /** Draws the panel content only, ignoring the module's own enabled/disabled — used for the live editor preview. */
    fun renderInternal(g: GuiGraphicsExtractor)

    fun adjustScale(delta: Float)
    fun save()
}