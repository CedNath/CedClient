package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.TextSetting

object CustomNametag : Module(
    "Custom Nametag",
    Category.Render,
    "Shows a custom tag in place of a username -- in-world, tab list, and chat."
) {
    const val HARDCODED_USERNAME = "CedNath"
    const val HARDCODED_TAG = "§b§lCed" // edit directly -- not exposed as a Setting

    val tagText = TextSetting(
        name = "Tag Text",
        default = "",
        description = "Replaces your own username (in-world, tab list, chat) while this module is enabled. Also settable with /cedclient nametag <text>.",
        maxLength = 32
    )

    init {
        addSettings(tagText)
    }
}