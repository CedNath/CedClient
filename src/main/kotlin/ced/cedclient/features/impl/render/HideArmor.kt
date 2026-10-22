package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.world.entity.EquipmentSlot

/** Hides worn armor and head items on players, client-side (hooks: HideArmorLayerMixin, HideCustomHeadMixin). */
object HideArmor : Module(
    "Hide Armor",
    Category.Cosmetic,
    "Hides player armor and worn skull heads, client-side, in any world."
) {
    private val mode = DropdownSetting("Players", listOf("All Players", "Others Only", "Self Only"), "All Players", "Which players have armor hidden.")
    private val helmet = BooleanSetting("Hide Helmet", true, "Hides the helmet slot.")
    private val chest = BooleanSetting("Hide Chestplate", true, "Hides the chestplate slot.")
    private val legs = BooleanSetting("Hide Leggings", true, "Hides the leggings slot.")
    private val boots = BooleanSetting("Hide Boots", true, "Hides the boots slot.")
    private val heads = BooleanSetting("Player Heads", true, "Hides skulls and other items worn on the head.")

    init { addSettings(mode, helmet, chest, legs, boots, heads) }

    private fun applies(state: AvatarRenderState): Boolean {
        if (!isEnabled) return false
        val self = state.id == Minecraft.getInstance().player?.id
        return when (mode.value) { "Others Only" -> !self; "Self Only" -> self; else -> true }
    }

    @JvmStatic fun hideSlot(state: Any, slot: EquipmentSlot): Boolean {
        if (state !is AvatarRenderState || !applies(state)) return false
        return when (slot) {
            EquipmentSlot.HEAD -> helmet.value
            EquipmentSlot.CHEST -> chest.value
            EquipmentSlot.LEGS -> legs.value
            EquipmentSlot.FEET -> boots.value
            else -> false
        }
    }

    @JvmStatic fun hideHead(state: Any) = heads.value && state is AvatarRenderState && applies(state)
}
