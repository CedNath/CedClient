package ced.cedclient.events

import ced.cedclient.data.ClickType
import ced.cedclient.events.core.Event
import net.minecraft.world.item.ItemStack

open class WorldClickEvent(
    val itemInHand: ItemStack?,
    val clickType: ClickType
) : Event()