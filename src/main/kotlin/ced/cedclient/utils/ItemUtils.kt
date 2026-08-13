package ced.cedclient.utils

import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * EXACT SkyHanni-style skull texture extraction for 1.21.x.
 *
 * SkyHanni uses DataComponents.PROFILE → ResolvableProfile → partialProfile() → properties.
 */
fun ItemStack.getSkullTexture(): String? {
    if (this.isEmpty) return null
    if (this.item != Items.PLAYER_HEAD) return null

    val profile = this.get(DataComponents.PROFILE)?.partialProfile() ?: return null
    val textures = profile.properties["textures"] ?: return null
    val first = textures.firstOrNull() ?: return null

    return first.value
}
