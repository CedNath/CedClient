package ced.cedclient.features.impl.render.nametag

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.utils.Color

object CustomNametag : Module(
    "Custom Nametag",
    Category.Cosmetic,
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

    val levelColorOn = BooleanSetting(
        "Level Prefix Color",
        false,
        "Gives the SkyBlock level prefix next to your name (the [300] in the tab list and chat) a color of your choice. Only the color changes, never the level."
    )
    val levelColor = ColorSetting("Level Color", Color(255, 170, 0), "Color of your level prefix.").withDependency { levelColorOn.value }

    val levelGradient = BooleanSetting("Level Gradient", false, "Fades the level prefix from Level Color to Level Color 2.").withDependency { levelColorOn.value }
    val levelColor2 = ColorSetting("Level Color 2", Color(255, 85, 85), "Second color of the level gradient.").withDependency { levelColorOn.value && levelGradient.value }

    /** 0xRRGGBB stops for your own level prefix (two = a gradient), or null when it should keep the server's color. */
    fun ownLevelColor(): List<Int>? {
        if (!levelColorOn.value) return null
        val first = levelColor.value.rgba and 0xFFFFFF
        return if (levelGradient.value) listOf(first, levelColor2.value.rgba and 0xFFFFFF) else listOf(first)
    }

    init {
        addSettings(tagText, levelColorOn, levelColor, levelGradient, levelColor2, logNametagDebug)
    }
}