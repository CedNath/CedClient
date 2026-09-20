package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.render.EntityESP
import net.minecraft.client.Minecraft

/**
 * Opened by EntityESP's "Select Players" ActionSetting. Lists every player
 * currently loaded nearby, read straight from the level's player list --
 * deliberately NOT EntityESP.scannedEntities, since that list has EntityESP's
 * own onlyNames filter already applied to it (see EntityESP.scan()). Sourcing
 * from scannedEntities meant that as soon as you put one player in the Only
 * list, every other player stopped being scanned and vanished from this
 * popup. Reading the level's player list directly keeps everyone visible
 * here regardless of which ones are currently toggled on.
 */
class PlayerFilterPopup : FilterPopup(
    title = "Select Players",
    blockedNames = { EntityESP.blockedNames },
    onlyNames = { EntityESP.onlyNames },
    addBlocked = EntityESP::blockName,
    removeBlocked = EntityESP::unblockName,
    addOnly = EntityESP::onlyName,
    removeOnly = EntityESP::unOnlyName
) {
    override fun candidateNames(): List<String> {
        val mc = Minecraft.getInstance()
        val self = mc.player
        return mc.level?.players()
            ?.filter { it != self }
            ?.map { it.name.string }
            ?.distinct()
            ?: emptyList()
    }
}