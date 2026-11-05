//#if CHEAT
package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.render.EspFilterModule

/**
 * Opened by Player ESP / Mob ESP's "Select Custom" ActionSetting. No scanned-entity
 * candidates -- this is the free-text editor for name patterns that aren't
 * currently in render distance (or aren't a mob/player name at all, e.g. a
 * partial match you already know you want blocked).
 */
class ESPFilterPopup(module: EspFilterModule) : FilterPopup(
    title = "Select Custom",
    blockedNames = { module.blockedNames },
    onlyNames = { module.onlyNames },
    addBlocked = module::blockName,
    removeBlocked = module::unblockName,
    addOnly = module::onlyName,
    removeOnly = module::unOnlyName,
    styleOf = module::styleOf,
    onStyleChanged = module::saveFilters,
)
//#endif
