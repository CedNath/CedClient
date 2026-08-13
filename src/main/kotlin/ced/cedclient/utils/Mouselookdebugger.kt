package ced.cedclient.utils

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

/**
 * Debug tool: while enabled, feeds your REAL mouse look each tick as the
 * "target" into a live HumanLook instance, then prints both the real
 * movement and what HumanLook produced for the same input, side by side.
 *
 * This is a read-only diagnostic — nothing here records or replays input,
 * it just lets you tune HumanLook's settings (smoothTimeSeconds,
 * maxDegreesPerSecond, jitterDegrees) by comparing the printed curves
 * against how you actually move the mouse.
 *
 * Toggle with the keybind (set it in Controls menu, unbound by default).
 */
object MouseLookDebugger {

    private val mc = Minecraft.getInstance()

    @Volatile
    var enabled: Boolean = false
        private set

    private val toggleKey = KeyMapping(
        "key.cedclient.mouse_debug_toggle",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_UNKNOWN, // unbound by default, bind it in Options > Controls
        KeyMapping.Category.MISC
    )

    private var lastYaw = 0f
    private var lastPitch = 0f
    private var initialized = false

    private var simulated = HumanLook()
    private var simYaw = 0f
    private var simPitch = 0f

    private var lastKeyDown = false
    private var tickCount = 0

    /** Call once from your mod's client initializer. */
    fun register() {
        KeyMappingHelper.registerKeyMapping(toggleKey)

        ClientTickEvents.END_CLIENT_TICK.register {
            handleToggleKey()
            if (enabled) tick()
        }
    }

    /** Swap in whatever settings you want to test without restarting. */
    fun configure(smoothTimeSeconds: Float, maxDegreesPerSecond: Float, jitterDegrees: Float) {
        simulated = HumanLook(smoothTimeSeconds, maxDegreesPerSecond, jitterDegrees)
        initialized = false
        println("[MouseDebug] simulator reconfigured: smoothTime=$smoothTimeSeconds maxSpeed=$maxDegreesPerSecond jitter=$jitterDegrees")
    }

    private fun handleToggleKey() {
        val down = toggleKey.isDown
        if (down && !lastKeyDown) setEnabled(!enabled)
        lastKeyDown = down
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        initialized = false
        tickCount = 0
        if (value) {
            println("[MouseDebug] ON — move your mouse around, compare REAL vs SIM columns below.")
            println("[MouseDebug] lagYaw/lagPitch = how far SIM is currently trailing behind REAL.")
        } else {
            println("[MouseDebug] OFF")
        }
    }

    private fun tick() {
        val player = mc.player ?: return

        val realYaw = player.yRot
        val realPitch = player.xRot

        if (!initialized) {
            lastYaw = realYaw
            lastPitch = realPitch
            simYaw = realYaw
            simPitch = realPitch
            initialized = true
            return
        }

        val dYawReal = shortestDiff(lastYaw, realYaw)
        val dPitchReal = realPitch - lastPitch
        val realSpeed = kotlin.math.hypot(dYawReal.toDouble(), dPitchReal.toDouble()) / 0.05

        // Feed the real current angle in as the target HumanLook chases,
        // same deltaTime CoralotHelper uses per tick (0.05s).
        val (newSimYaw, newSimPitch) = simulated.step(simYaw, simPitch, realYaw, realPitch, 0.05f)
        val dYawSim = shortestDiff(simYaw, newSimYaw)
        val dPitchSim = newSimPitch - simPitch
        val simSpeed = kotlin.math.hypot(dYawSim.toDouble(), dPitchSim.toDouble()) / 0.05

        val lagYaw = shortestDiff(newSimYaw, realYaw)
        val lagPitch = realPitch - newSimPitch

        tickCount++
        // Only print when there's actually movement, otherwise it's just noise every tick.
        if (kotlin.math.abs(dYawReal) > 0.001f || kotlin.math.abs(dPitchReal) > 0.001f || kotlin.math.abs(lagYaw) > 0.01f || kotlin.math.abs(lagPitch) > 0.01f) {
            println(
                "[MouseDebug] #%-5d REAL dYaw=%7.3f dPitch=%7.3f spd=%8.1f°/s  |  SIM dYaw=%7.3f dPitch=%7.3f spd=%8.1f°/s  |  lagYaw=%7.3f lagPitch=%7.3f"
                    .format(tickCount, dYawReal, dPitchReal, realSpeed, dYawSim, dPitchSim, simSpeed, lagYaw, lagPitch)
            )
        }

        lastYaw = realYaw
        lastPitch = realPitch
        simYaw = newSimYaw
        simPitch = newSimPitch
    }

    private fun shortestDiff(from: Float, to: Float): Float {
        var diff = (to - from) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }
}