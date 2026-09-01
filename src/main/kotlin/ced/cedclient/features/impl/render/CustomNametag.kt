package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.TextSetting

/**
 * Shows a custom text tag wherever a player's name normally appears:
 * floating above their model in-world (CustomNametagRenderer), in the tab
 * list (PlayerInfoMixin), and in chat lines (ChatComponentMixin, via
 * CustomNametagText's substring swap). Purely client-side rendering/text
 * substitution -- no packets sent, so this only shows to people also
 * running the mod, same as PlayerScale's hardcoded target.
 *
 * Two independent behaviors, same split PlayerScale uses:
 *   - Hardcoded target: the real CedNath always shows HARDCODED_TAG
 *     everywhere above, regardless of this module's toggle. Edit the
 *     constant directly to change it -- intentionally not a Setting.
 *   - Self: whoever enables this module sees their OWN tag (the Tag Text
 *     field below, also settable with /cedclient nametag <text>) in place
 *     of their own username everywhere above.
 */
object CustomNametag : Module(
    "Custom Nametag",
    Category.Render,
    "Shows a custom tag in place of a username -- in-world, tab list, and chat. CedNath always shows a fixed tag, independent of this toggle."
) {
    const val HARDCODED_USERNAME = "CedNath"
    const val HARDCODED_TAG = "§b§lCedNath" // edit directly -- not exposed as a Setting

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