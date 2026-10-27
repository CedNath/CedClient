package ced.cedclient.utils

import ced.cedclient.config.CedPaths
import ced.cedclient.features.impl.misc.Theme
import ced.cedclient.features.settings.BooleanSetting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * CedClient's chat prefix: "[CC]" in the HUD accent (Client Settings), so it follows the chosen colour.
 * Each module that talks in chat can have its prefix turned off (Misc > Chat Prefix); [msgFor] checks that.
 */
object ChatPrefix {
    /** Modules whose chat lines carry the prefix, in popup order. */
    val modules = listOf(
        "Anvil Helper", "Auto Fusion", "Cosmetic Blocks", "Critter Safari", "Dojo Helper", "Dungeon Clears", "Dungeon Splits",
        "Explosive Shot Damage", "FarmHelper", "Leap Menu", "Party Commands", "Positional Messages", "Room Times", "Music",
        "Sun Gecko", "TinyDancerHelper",
    )

    /** One "show the prefix" switch per module (on = prefix shown, the default). */
    val toggles: Map<String, BooleanSetting> = modules.associateWith { BooleanSetting(it, true, "Shows [CC] in front of $it's chat messages.") }

    private val file by lazy { CedPaths.file("chat_prefix.txt") }
    private var saved = ""

    /** "[CC] " then [text], which can carry its own § colours (white without them). */
    @JvmStatic
    fun msg(text: String): MutableComponent =
        Component.empty()
            .append(Component.literal("[CC]").withColor(Theme.hudAccentRgba and 0xFFFFFF))
            .append(Component.literal(" $text"))

    /** Like [msg], but without the prefix when [module]'s switch is off. */
    @JvmStatic
    fun msgFor(module: String, text: String): MutableComponent =
        if (toggles[module]?.value == false) Component.literal(text) else msg(text)

    fun load() {
        runCatching {
            if (file.exists()) file.readLines().map { it.trim() }.forEach { toggles[it]?.value = false }
        }
        saved = hiddenText()
    }

    private fun hiddenText() = toggles.filterValues { !it.value }.keys.joinToString("\n")

    /** Writes the switches when they changed (called from a tick). */
    fun saveIfChanged() {
        val now = hiddenText()
        if (now == saved) return
        saved = now
        runCatching { file.writeText(now) }
    }
}
