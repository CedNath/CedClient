package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.utils.skyblockId
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * A SkyBlock shortbow ("Shortbow: Instantly shoots!") fires on click, but vanilla still plays the pull-back animation. This
 * cancels the animation (see PlayerBowMixin). Unique: the lore check is remembered per item id so it costs nothing after the
 * first look, and any bow whose lore says otherwise is left alone.
 */
object ArrowFix : Module(
    "Arrow Fix",
    Category.Misc,
    "Removes the bow pull-back animation on shortbows."
) {
    private val shortbows = HashSet<String>()
    private val others = HashSet<String>()

    @JvmStatic
    fun isShortbow(item: ItemStack?): Boolean {
        if (item == null || item.isEmpty || !item.`is`(Items.BOW)) return false
        val id = item.skyblockId ?: return false
        if (id in shortbows) return true
        if (id in others) return false
        val lore = item.get(DataComponents.LORE)?.lines() ?: return false
        val found = lore.any { "Shortbow: Instantly shoots!" in it.string }
        (if (found) shortbows else others).add(id)
        return found
    }
}
