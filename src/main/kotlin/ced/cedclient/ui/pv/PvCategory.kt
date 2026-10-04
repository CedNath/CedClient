package ced.cedclient.ui.pv

/**
 * One sidebar entry within a [PvTab] that has more than one page -- e.g.
 * Inventory's Main/EnderChest/Backpacks/Wardrobe/Accessory/Sacks entries.
 *
 * Plain data holder: a tab decides how to render its own categories'
 * content in [PvTab.draw]; this just gives PvScreen something stable to
 * key hover-state and "currently selected" off of.
 */
interface PvCategory {
    val id: String
    val displayName: String
}

/** Convenience for tabs whose categories are nothing but an id + label. */
data class SimplePvCategory(override val id: String, override val displayName: String) : PvCategory
