package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.utils.Debug
import net.minecraft.core.Holder
import net.minecraft.world.clock.WorldClock
import net.minecraft.world.clock.WorldClocks
import java.time.LocalTime

/**
 * Client-side time of day: pin the sun / moon where you like, or let it follow your real clock (noon at 12:00).
 * Only changes what you see (sky, sun, moon, light); the server's time is untouched.
 * Hooked from ClientClockManagerMixin, which asks [overrideTicks] for the overworld clock.
 */
object CustomSky : Module(
    "Custom Sky",
    Category.Render,
    "Client-side time of day: pin the sun and moon, or follow your real clock."
) {
    private val mode = DropdownSetting("Mode", listOf("Fixed", "Real Time"), "Fixed", "Fixed keeps one time; Real Time follows your clock, noon at 12:00.")
    private val presets = linkedMapOf(
        "Sunrise" to 23000, "Morning" to 1000, "Noon" to 6000, "Afternoon" to 9000,
        "Sunset" to 12000, "Night" to 14000, "Midnight" to 18000, "Custom" to -1,
    )
    private val time = DropdownSetting("Time", presets.keys.toList(), "Noon", "The time to keep.").withDependency { mode.value == "Fixed" }
    private val custom = NumberSetting("Custom Ticks", 6000.0, 0.0, 23999.0, 100.0, "0 = sunrise, 6000 = noon, 12000 = sunset, 18000 = midnight.")
        .withDependency { mode.value == "Fixed" && time.value == "Custom" }

    private val skyOptions = ArrayList(listOf("None") + SkyBox.list())
    private val skybox = DropdownSetting("Skybox", skyOptions, "None", "A sky picture from the skyboxes folder (3 x 2 faces, see SkyBox). None: the normal sky.")
    private val reload = ActionSetting("Reload Skyboxes", "Looks in the skyboxes folder again (config/cedclient/skyboxes).") {
        SkyBox.reset()
        skyOptions.clear(); skyOptions += "None"; skyOptions += SkyBox.list()
        if (skybox.value !in skyOptions) skybox.value = "None"
        Debug.line("CustomSky", "skyboxes: ${skyOptions.drop(1)}")
    }

    /** The skybox picture to draw (from SkyRendererSkyboxMixin), or null for the normal sky. */
    @JvmStatic
    fun skyboxName(): String? = if (isEnabled && skybox.value != "None") skybox.value else null

    private var lastLogged = Long.MIN_VALUE

    init {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register { skyboxName()?.let { SkyBox.ensureLoaded(it) } }
    }

    /** The overworld clock's tick count to show, or null to leave the real one. */
    @JvmStatic
    fun overrideTicks(definition: Holder<WorldClock>): Long? {
        if (!isEnabled || !definition.`is`(WorldClocks.OVERWORLD)) return null
        val ticks = if (mode.value == "Real Time") {
            val sec = LocalTime.now().toSecondOfDay()
            (sec * 24000L / 86400L + 18000L) % 24000L
        } else {
            val p = presets[time.value] ?: 6000
            (if (p < 0) custom.value.toInt() else p).toLong()
        }
        val bucket = ticks / 1000
        if (bucket != lastLogged) { lastLogged = bucket; Debug.line("CustomSky", "${mode.value}: time of day now ~$ticks ticks") }
        return ticks
    }

    init {
        addSettings(mode, time, custom, skybox, reload)
    }
}
