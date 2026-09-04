package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.TextSetting

object CustomNametag : Module(
    "Custom Nametag",
    Category.Render,
    "Shows a custom tag in place of a username -- in-world, tab list, and chat."
) {
    val tagText = TextSetting(
        name = "Tag Text",
        default = "",
        description = "Replaces your own username (in-world, tab list, chat) while this module is enabled. " +
                "Also settable with /cedclient nametag <text>. Supports &0-&f/&l/&o/&n/&m/&k/&r legacy codes, " +
                "&#RRGGBB hex colors, <gradient:#RRGGBB:#RRGGBB>text</gradient>, and <rainbow>text</rainbow>.",
        maxLength = 128
    )

    init {
        addSettings(tagText)
    }
}