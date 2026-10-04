package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

/**
 * FOV-based zoom modeled after WI-Zoom (github.com/Wurst-Imperium/WI-Zoom):
 * hold the zoom key to zoom in, scroll while held to zoom further (up to
 * Max Zoom), smoothly interpolated so the FOV change never snaps, with
 * optional dynamic mouse sensitivity so a full mouse swipe doesn't fling
 * the camera across a heavily-zoomed view.
 *
 * This object only holds state/settings. The actual FOV override happens in
 * GameRendererFovMixin (reads getZoomDivisor(partialTick) every frame,
 * same interpolation idiom Freecam uses for getYaw()/getPitch()). Scroll
 * input is captured by MouseScrollMixin, which forwards to adjustZoom() and
 * cancels vanilla scroll handling (hotbar/inventory) while zooming.
 */
object Zoom : Module(
    "Zoom",
    Category.Render,
    description = "Hold the zoom key to zoom in, scroll while zooming to zoom further.",
    defaultEnabled = false
) {
    private val maxZoom = NumberSetting(
        "Max Zoom", 16.0, 2.0, 50.0, 1.0,
        "Highest zoom multiplier reachable by scrolling"
    )
    private val defaultZoomSetting = NumberSetting(
        "Default Zoom", 4.0, 1.0, 20.0, 1.0,
        "Zoom multiplier applied as soon as you press the zoom key"
    )
    private val scrollStep = NumberSetting(
        "Scroll Step", 1.0, 0.5, 5.0, 0.5,
        "How much each scroll tick changes the zoom multiplier"
    )
    private val zoomSpeed = NumberSetting(
        "Zoom Speed", 1.0, 1.0, 1.0, 1.0,
        "How quickly the FOV eases toward the target zoom each tick -- higher is snappier, lower is smoother"
    )
    private val dynamicSensitivity = BooleanSetting(
        "Dynamic Sensitivity", true,
        "Scales mouse sensitivity down proportional to the current zoom level while zooming"
    )

    private val mc get() = Minecraft.getInstance()

    var zooming = false
        private set

    // Public, Java-visible getter for the mixins below -- same reasoning as
    // Module.isEnabled / Freecam.isActive: a plain Kotlin Boolean property
    // named "zooming" only generates getZooming() on the JVM side, not
    // isZooming(), so mixins written from Java need this instead.
    val isZooming: Boolean
        get() = zooming

    // divisor: final FOV = vanillaFov / currentDivisor. 1.0 == no zoom.
    private var targetDivisor = 1.0
    private var prevDivisor = 1.0
    private var currentDivisor = 1.0

    private var originalSensitivity: Double? = null

    init {
        addSettings(maxZoom, defaultZoomSetting, scrollStep, zoomSpeed, dynamicSensitivity)

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!isEnabled) return@register

            prevDivisor = currentDivisor

            if (!zooming) targetDivisor = 1.0

            // Ease currentDivisor toward targetDivisor. zoomSpeed is a
            // per-tick fraction of the remaining distance, so smaller values
            // take more ticks (and therefore longer) to settle.
            currentDivisor += (targetDivisor - currentDivisor) * zoomSpeed.value

            if (zooming && dynamicSensitivity.value) applySensitivityScaling()
        }

        // If the client quits while zooming, make sure we don't leave the
        // real sensitivity option permanently scaled down.
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            if (zooming) stopZoom()
        }
    }

    override fun onDisable() {
        if (zooming) stopZoom()
    }

    /** Called every tick from CedClient's keybind loop with the zoom key's held state. */
    fun setHeld(held: Boolean) {
        if (!isEnabled) {
            if (zooming) stopZoom()
            return
        }
        if (held && !zooming) startZoom()
        if (!held && zooming) stopZoom()
    }

    private fun startZoom() {
        zooming = true
        targetDivisor = defaultZoomSetting.value.coerceIn(1.0, maxZoom.value)

        if (dynamicSensitivity.value) {
            originalSensitivity = mc.options.sensitivity().get()
        }
    }

    private fun stopZoom() {
        zooming = false
        targetDivisor = 1.0

        originalSensitivity?.let { mc.options.sensitivity().set(it) }
        originalSensitivity = null
    }

    /** Forwarded from MouseScrollMixin. Positive yOffset == scroll up == zoom in. */
    fun adjustZoom(yOffset: Double) {
        if (!zooming) return
        targetDivisor = (targetDivisor + yOffset * scrollStep.value).coerceIn(1.0, maxZoom.value)
    }

    /**
     * Interpolated zoom divisor for this frame. Called from
     * GameRendererFovMixin every render frame (not just once per tick), so
     * it lerps smoothly between the last two tick states -- exactly the
     * interpolation idiom Freecam already uses for
     * getInterpolatedPos()/getYaw()/getPitch().
     */
    fun getZoomDivisor(partialTick: Float): Double {
        return prevDivisor + (currentDivisor - prevDivisor) * partialTick
    }

    /**
     * Scales the real sensitivity option down by the current zoom divisor
     * so turning the mouse the same physical distance covers less of the
     * (now magnified) view. Restored to its original value in stopZoom().
     *
     * Mutates the live Options#sensitivity value directly (temporarily) --
     * same technique other zoom mods use -- rather than intercepting
     * MouseHandler#turnPlayer's internal math, which this codebase doesn't
     * have a reliable hook into yet. One known edge case: if the options
     * screen is saved to disk while actively zoomed (e.g. the player opens
     * the options menu mid-zoom and changes an unrelated setting), the
     * scaled-down value could get persisted -- worth keeping an eye on.
     */
    private fun applySensitivityScaling() {
        val original = originalSensitivity ?: return
        val scaled = (original / currentDivisor.coerceAtLeast(1.0))
        mc.options.sensitivity().set(scaled)
    }
}