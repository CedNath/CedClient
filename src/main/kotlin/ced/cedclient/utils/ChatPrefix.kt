package ced.cedclient.utils

import ced.cedclient.features.impl.misc.Theme
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/** CedClient's chat prefix: "[CC]" in the HUD accent (Client Settings), so it follows the chosen colour. */
object ChatPrefix {
    /** "[CC] " then [text], which can carry its own § colours (white without them). */
    @JvmStatic
    fun msg(text: String): MutableComponent =
        Component.empty()
            .append(Component.literal("[CC]").withColor(Theme.hudAccentRgba and 0xFFFFFF))
            .append(Component.literal(" $text"))
}
