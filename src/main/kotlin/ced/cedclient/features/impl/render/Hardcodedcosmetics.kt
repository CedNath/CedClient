package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.state.CosmeticsSync


object HardcodedCosmetics : Module(
    "Cosmetics",
    Category.CedClient,
    "Shows Custom built-in cosmetic tag on your client.",
    defaultEnabled = true
) {

    /**
     * Toggling the Cosmetics button off and on again forces an immediate
     * re-download of cosmetics.json instead of waiting for the next poll.
     */
    override fun onEnable() {
        CosmeticsSync.forceSync()
    }
}