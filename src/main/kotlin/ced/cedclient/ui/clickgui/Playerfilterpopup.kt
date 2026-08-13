package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.render.EntityESP
import ced.cedclient.features.impl.render.MobCategoryType

/**
 * Player picker — lists everyone EntityESP currently sees nearby (updates live
 * as people come in/out of range), grouped as a single "Players" list. Checking
 * a box calls the same EntityESP.onlyName(name) allow-list the mob picker uses,
 * so mob selections and player selections render together.
 */
class PlayerFilterPopup : EntityCheckListPopup("Select players") {

    override fun groupsProvider(): List<Pair<String, List<String>>> {
        val names = EntityESP.scannedEntities
            .filter { it.category == MobCategoryType.PLAYER }
            .map { it.name }
            .distinct()
            .sorted()

        return listOf("Players in range" to names)
    }
}