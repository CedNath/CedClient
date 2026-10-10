package ced.cedclient.features.impl.misc

import ced.cedclient.accessor.BorderlessWindowAccess
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import net.minecraft.client.Minecraft

/**
 * Fullscreen (F11 / Video Settings) becomes a borderless window covering the monitor at its current resolution:
 * instant alt-tab and no display mode switch. The window side lives in WindowBorderlessMixin.
 */
object BorderlessFullscreen : Module(
    "Borderless Fullscreen",
    Category.Misc,
    "Fullscreen (F11) becomes a borderless window: instant alt-tab, no resolution switch.",
    defaultEnabled = true
) {

    override fun onEnable() = reapply()
    override fun onDisable() = reapply()

    /** Switch the current fullscreen window over right away (queued: this can run while the config loads). */
    private fun reapply() {
        val mc = Minecraft.getInstance() ?: return
        mc.execute {
            val window = runCatching { mc.window }.getOrNull() ?: return@execute
            if (window.isFullscreen) (window as BorderlessWindowAccess).`cedclient$reapplyMode`()
        }
    }
}
