package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.NumberSetting
import net.minecraft.client.Minecraft
import net.minecraft.tags.ItemTags
import net.minecraft.world.item.ItemStack

/**
 * 1.8 style sword block: holding right click with a sword in your main hand pulls the sword across your body (first
 * person) and raises your arm (third person). Visual only, nothing is sent to the server, so SkyBlock abilities on
 * right click work as usual. Hooked from SwordBlockingHandMixin (first person) and SwordBlockingPoseMixin (third).
 */
object SwordBlocking : Module(
    "Sword Blocking", Category.Visuals,
    "Right-click with a sword shows the old 1.8 blocking pose. Visual only."
) {
    val offsetX = NumberSetting("Offset X", 0.0, -1.0, 1.0, 0.01, "Nudge the first-person sword sideways.")
    val offsetY = NumberSetting("Offset Y", 0.0, -1.0, 1.0, 0.01, "Nudge the first-person sword up / down.")
    val offsetZ = NumberSetting("Offset Z", 0.0, -1.0, 1.0, 0.01, "Nudge the first-person sword towards / away from you.")

    init { addSettings(offsetX, offsetY, offsetZ) }

    /** Right click is held, no menu is open, and [stack] is a sword. */
    fun blocking(stack: ItemStack): Boolean {
        if (!isEnabled || !stack.`is`(ItemTags.SWORDS)) return false
        val mc = Minecraft.getInstance()
        return mc.gui.screen() == null && mc.options.keyUse.isDown && mc.player?.isUsingItem == false
    }
}
