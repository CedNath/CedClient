package ced.cedclient.utils

import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.component.SwingAnimation

/** Client side main hand swing the way vanilla does it: animation from the held item, nothing sent to other players. */
fun LocalPlayer.swingMainHand(attack: Boolean = false) {
    val component = if (attack) DataComponents.ATTACK_ANIMATION else DataComponents.INTERACT_ANIMATION
    swing(InteractionHand.MAIN_HAND, getItemInHand(InteractionHand.MAIN_HAND).getOrDefault(component, SwingAnimation.DEFAULT), false)
}
