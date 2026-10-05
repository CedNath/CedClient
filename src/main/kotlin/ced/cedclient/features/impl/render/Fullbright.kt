package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.NumberSetting
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import kotlin.math.pow

/**
 * Configurable fullbright. Works by overriding what the game reads for the Brightness
 * (gamma) option -- see OptionInstanceMixin -- so it needs no knowledge of the lightmap
 * internals. The real saved setting is never touched (OptionsMixin turns the override off
 * while options.txt is being written).
 *
 * Strength is a single "boost" applied on a log scale, so sliders and the fade feel even
 * instead of "nothing, nothing, nothing, everything":
 *
 *     gamma = vanilla + 1000^(brightness% * minLevel/15 * fade) - 1
 *
 *  - Brightness %      overall strength (0 = off, 100 = full)
 *  - Minimum Light     how much darkness gets removed. 15 = everything fully lit. Lower values
 *                      brighten dark areas less. This is an approximation done through gamma,
 *                      not a true "nothing darker than level N" floor, so very bright areas
 *                      also get slightly brighter at mid values.
 *  - Fade Time         the fade (0..1) eases in/out over this many seconds when you toggle.
 */
object Fullbright : Module(
    "Fullbright",
    Category.Render,
    "Adjustable fullbright with a brightness slider, minimum light level and smooth fade",
    defaultEnabled = true
) {
    private val brightness = NumberSetting("Brightness %", 10.0, 0.0, 100.0, 5.0)
    private val minLight = NumberSetting("Minimum Light Level", 15.0, 0.0, 15.0, 1.0)
    private val fadeSeconds = NumberSetting("Fade Time (s)", 1.0, 0.0, 5.0, 0.25)

    /** Set by OptionsMixin while options.txt is being saved, so the boosted value never gets written. */
    @Volatile
    var suppress = false

    private const val MAX_BOOST = 1000.0

    // 0 = fully off, 1 = fully on. Moves toward the target a little every time it's read.
    private var fade = 0.0
    private var lastNanos = 0L

    init {
        addSettings(brightness, minLight, fadeSeconds)

        // Safety net: if Options.save() ever threw before clearing the flag, don't stay stuck.
        ClientTickEvents.END_CLIENT_TICK.register { suppress = false }
    }

    /**
     * Called from OptionInstanceMixin every time the game reads the gamma option.
     * @return the gamma to use, or null to leave vanilla's value alone.
     */
    fun gammaOverride(vanilla: Double): Double? {
        if (suppress) return null

        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.0 else ((now - lastNanos) / 1_000_000_000.0).coerceIn(0.0, 0.1)
        lastNanos = now

        val target = if (isEnabled) 1.0 else 0.0
        val fadeTime = fadeSeconds.value
        fade = if (fadeTime <= 0.0) {
            target
        } else {
            val step = dt / fadeTime
            if (target > fade) minOf(target, fade + step) else maxOf(target, fade - step)
        }

        if (fade <= 0.0) return null

        // Ease the fade (smoothstep) so it starts and ends gently.
        val eased = fade * fade * (3.0 - 2.0 * fade)
        val strength = (brightness.value / 100.0) * (minLight.value / 15.0) * eased
        if (strength <= 0.0) return null

        return vanilla + (MAX_BOOST.pow(strength) - 1.0)
    }
}