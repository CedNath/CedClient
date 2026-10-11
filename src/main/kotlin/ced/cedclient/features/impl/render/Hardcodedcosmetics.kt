package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.state.CosmeticsSync


object HardcodedCosmetics : Module(
    "Cosmetics",
    Category.Cosmetic,
    "Shows Custom built-in cosmetic tag on your client.",
    defaultEnabled = true
) {

    // Chat extras (ChatEmotes). These work whether or not the cosmetic tags themselves are on.
    val chatEmotes = BooleanSetting("Chat Emotes", true, "Show Discord emotes in chat: :sob: and 😭 become the emote image.")
    val vineBoom = BooleanSetting("Vine Boom on 🗿", true, "Play the vine boom when someone sends 🗿 or :moyai: in chat.")
    val vineBoomVolume = NumberSetting("Vine Boom Volume", 1.0, 0.1, 2.0, 0.1)

    init {
        addSettings(chatEmotes, vineBoom, vineBoomVolume)
        ChatEmotes // load the emote table and start listening for 🗿
    }

    /**
     * Toggling the Cosmetics button off and on again forces an immediate
     * re-download of cosmetics.json instead of waiting for the next poll.
     */
    override fun onEnable() {
        CosmeticsSync.forceSync()
    }
}