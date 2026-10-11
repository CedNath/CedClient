package ced.cedclient.events

import ced.cedclient.data.ClickType
import net.minecraft.world.item.ItemStack

class ItemClickEvent(
    itemInHand: ItemStack?,
    clickType: ClickType
) : WorldClickEvent(itemInHand, clickType)