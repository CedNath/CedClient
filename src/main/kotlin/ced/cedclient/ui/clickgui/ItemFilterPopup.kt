//#if CHEAT
package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.render.ItemESP
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.item.ItemEntity

/** Opened by Item ESP's "Select Items". Offers the dropped items loaded nearby; any name pattern can still be typed in. */
class ItemFilterPopup : FilterPopup(
    title = "Select Items",
    blockedNames = { ItemESP.blockedNames },
    onlyNames = { ItemESP.onlyNames },
    addBlocked = ItemESP::blockName,
    removeBlocked = ItemESP::unblockName,
    addOnly = ItemESP::onlyName,
    removeOnly = ItemESP::unOnlyName,
    styleOf = ItemESP::styleOf,
    onStyleChanged = ItemESP::saveFilters,
) {
    override fun candidateNames(): List<String> =
        Minecraft.getInstance().level?.entitiesForRendering()
            ?.filterIsInstance<ItemEntity>()
            ?.map { it.item.hoverName.string }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
}
//#endif
