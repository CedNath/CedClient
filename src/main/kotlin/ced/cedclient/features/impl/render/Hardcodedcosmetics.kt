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
    val chatEmotes = BooleanSetting("Chat Emotes", true, "Show Discord emotes in chat: :sob: (or the emoji itself) becomes the emote image. Also plays meme sounds for :moyai: :white_check_mark: :x: :spider: :scream: :fah: :sus: :dictator:.")
    val vineBoom = BooleanSetting("Vine Boom on 🗿", true, "Play the vine boom when someone sends 🗿 or :moyai: in chat.")
    val vineBoomVolume = NumberSetting("Vine Boom Volume", 1.0, 0.1, 1.0, 0.05, "Ignores the UI / Players sliders; only Master Volume also applies.")
    /** Emote sound volume, used instead of the Master slider. Set with /emotevolume, so hidden from the ClickGUI. */
    val emoteVolume = NumberSetting("Emote Volume", 0.5, 0.0, 1.0, 0.01, "Set with /emotevolume <0-100>.").apply { hide() }

    init {
        // Vine boom + its volume are deliberately not registered: hidden from the ClickGUI and not saved, so they
        // always run at their defaults (on, full volume).
        addSettings(chatEmotes, emoteVolume)
        ChatEmotes // load the emote table
    }

    /**
     * Toggling the Cosmetics button off and on again forces an immediate
     * re-download of cosmetics.json instead of waiting for the next poll.
     */
    override fun onEnable() {
        CosmeticsSync.forceSync()
    }
}