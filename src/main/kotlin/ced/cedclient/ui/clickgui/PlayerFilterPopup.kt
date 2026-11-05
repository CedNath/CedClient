//#if CHEAT
package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.render.PlayerESP
import net.minecraft.client.Minecraft

/**
 * Opened by Player ESP's "Select Players" ActionSetting. Lists every player
 * currently loaded nearby, read straight from the level's player list --
 * deliberately NOT PlayerESP.scannedEntities, since that list has the module's
 * own onlyNames filter already applied to it (see EntityEspModule.scan()). Sourcing
 * from scannedEntities meant that as soon as you put one player in the Only
 * list, every other player stopped being scanned and vanished from this
 * popup. Reading the level's player list directly keeps everyone visible
 * here regardless of which ones are currently toggled on.
 */
class PlayerFilterPopup : FilterPopup(
    title = "Select Players",
    blockedNames = { PlayerESP.blockedNames },
    onlyNames = { PlayerESP.onlyNames },
    addBlocked = PlayerESP::blockName,
    removeBlocked = PlayerESP::unblockName,
    addOnly = PlayerESP::onlyName,
    removeOnly = PlayerESP::unOnlyName,
    styleOf = PlayerESP::styleOf,
    onStyleChanged = PlayerESP::saveFilters,
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
//#endif
