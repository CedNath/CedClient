package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.render.BlockESP
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Blocks

/**
 * Opened by BlockESP's "Select Blocks" ActionSetting. Lists every block in
 * the game (from the block registry, so it always matches whatever
 * Minecraft version this is built against), alphabetically ordered -- same
 * "registry as candidate source, type to filter, click to toggle" pattern
 * MobFilterPopup uses for entities. Air is excluded since it's not a block
 * anyone would ever want to highlight.
 */
class BlockFilterPopup : FilterPopup(
    title = "Select Blocks",
    blockedNames = { BlockESP.blockedNames },
    onlyNames = { BlockESP.onlyNames },
    addBlocked = BlockESP::blockName,
    removeBlocked = BlockESP::unblockName,
    addOnly = BlockESP::onlyName,
    removeOnly = BlockESP::unOnlyName
) {
    override fun candidateNames(): List<String> =
        BuiltInRegistries.BLOCK
            .filter { it != Blocks.AIR }
            .map { it.name.string }
            .distinct()
}