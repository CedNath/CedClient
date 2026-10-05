package ced.cedclient.features.impl.render.nametag

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
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

    // TEMP DEBUG toggle -- diagnosing the in-world-nametag CosmeticsSync
    // gap (gameProfile.name faked by SkyBlock for other players' entities).
    // Gates the println debug lines in EntityMixin/PlayerRendererMixin;
    // remove this setting once that's resolved.
    val logNametagDebug = BooleanSetting(
        "Log Nametag Debug",
        false,
        "Prints gameProfile.name(), the resolved nametag text, and the known synced IGNs to console " +
                "for every player entity, in both EntityMixin's getDisplayName() redirect and " +
                "PlayerRendererMixin's fallback splice -- use this to diagnose why a synced/custom tag " +
                "isn't applying to someone's in-world floating nametag."
    ).also { it.advanced = true }

    init {
        addSettings(tagText, logNametagDebug)
    }
}