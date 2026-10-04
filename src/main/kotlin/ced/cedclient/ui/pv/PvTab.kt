package ced.cedclient.ui.pv

import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

/**
 * One top-level tab in the Profile Viewer (Home, Inventory, Collections, ...),
 * modeled after skyblock-pv's layout: a row of tabs across the top, and for
 * tabs that expose more than one [PvCategory], a left-hand sidebar to switch
 * between them (Inventory -> Main/EnderChest/Backpacks/Wardrobe/...).
 *
 * PvScreen owns the chrome (tab bar, sidebar, panel background) and just
 * hands each tab a content rectangle to draw into and its input events to
 * handle -- a tab never has to know about the other tabs or where on screen
 * it's actually positioned.
 */
interface PvTab {

    val id: String
    val displayName: String

    /** Sub-pages shown in a left sidebar. Empty or single-entry = no sidebar drawn. */
    val categories: List<PvCategory> get() = emptyList()

    /**
     * Draw this tab's content into (x, y, w, h) -- already inset past the tab
     * bar and, if [categories] has more than one entry, past the sidebar too.
     * [category] is the currently selected sidebar entry (null if [categories]
     * has 0 or 1 entries).
     */
    fun draw(x: Float, y: Float, w: Float, h: Float, mouseX: Float, mouseY: Float, category: PvCategory?)

    // Input is opt-in -- most tabs are read-only displays and need none of this.
    fun mouseClicked(mouseX: Float, mouseY: Float, event: MouseButtonEvent): Boolean = false
    fun mouseReleased(event: MouseButtonEvent): Boolean = false
    fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Int): Boolean = false
    fun keyPressed(event: KeyEvent): Boolean = false
    fun charTyped(event: CharacterEvent): Boolean = false
}
