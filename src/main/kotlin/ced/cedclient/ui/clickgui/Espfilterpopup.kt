package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.render.EntityESP

/**
 * Opened by EntityESP's "Select Custom" ActionSetting. No scanned-entity
 * candidates -- this is the free-text editor for name patterns that aren't
 * currently in render distance (or aren't a mob/player name at all, e.g. a
 * partial match you already know you want blocked).
 */
class ESPFilterPopup : FilterPopup(
    title = "Select Custom",
    blockedNames = { EntityESP.blockedNames },
    onlyNames = { EntityESP.onlyNames },
    addBlocked = EntityESP::blockName,
    removeBlocked = EntityESP::unblockName,
    addOnly = EntityESP::onlyName,
    removeOnly = EntityESP::unOnlyName
)