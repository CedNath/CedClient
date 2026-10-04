package ced.cedclient.ui.pv.tabs

import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.ui.pv.PvCategory
import ced.cedclient.ui.pv.PvTab
import ced.cedclient.utils.Colors

/**
 * Placeholder for a tab that's in skyblock-pv's documented lineup (see its
 * README's "All Tabs" list: Home, Combat, Inventory, Collections, Mining,
 * Fishing, Pets, Farming, Museum, Chocolate Factory, Rift) but not built
 * here yet. Keeps PvScreen's tab bar showing the full intended shape from
 * day one instead of visibly growing tab-by-tab as each one gets
 * implemented.
 *
 * One instance per placeholder tab (not an object) since several of these
 * sit in PvScreen's tabs list at once, each needing its own displayName.
 */
class ComingSoonTab(
    override val displayName: String,
    override val id: String = displayName.lowercase().replace(" ", "_")
) : PvTab {

    override fun draw(x: Float, y: Float, w: Float, h: Float, mouseX: Float, mouseY: Float, category: PvCategory?) {
        val text = "$displayName -- coming soon"
        val textWidth = NVGRenderer.textWidth(text, 18f, NVGRenderer.defaultFont)
        NVGRenderer.text(
            text, x + (w - textWidth) / 2f, y + h / 2f - 9f, 18f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont
        )
    }
}
