package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.render.EntityESP
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.MobCategory

/**
 * Opened by EntityESP's "Select Mobs" ActionSetting. Lists every hostile/
 * passive mob type in the game (from the entity type registry, so it always
 * matches whatever Minecraft version this is built against), alphabetically
 * ordered -- not just whatever happens to be scanned nearby right now.
 * MobCategory.MISC covers non-mob entities (boats, item frames, projectiles,
 * players, etc.), so excluding it is what keeps this list to actual mobs.
 * You can still toggle any of these into the Blocked or Only list, or type a
 * name pattern in directly, same as before (substring matches).
 */
class MobFilterPopup : FilterPopup(
    title = "Select Mobs",
    blockedNames = { EntityESP.blockedNames },
    onlyNames = { EntityESP.onlyNames },
    addBlocked = EntityESP::blockName,
    removeBlocked = EntityESP::unblockName,
    addOnly = EntityESP::onlyName,
    removeOnly = EntityESP::unOnlyName
) {
    override fun candidateNames(): List<String> =
        BuiltInRegistries.ENTITY_TYPE
            .filter { it.category != MobCategory.MISC }
            .map { it.description.string }
            .distinct()
}