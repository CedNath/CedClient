package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.NumberSetting
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import net.minecraft.client.CameraType
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Freecam : Module(
    "Freecam",
    Category.Render,
    description = "Detaches the camera and lets you fly around without moving your real player"
) {

    var active = false
        private set

    private val mc = Minecraft.getInstance()

    private val flySpeed = NumberSetting("Fly Speed", 1.0, 0.5, 5.0, 0.5)
    private val sprintMultiplier = NumberSetting("Sprint Multiplier", 2.0, 1.0, 10.0, 0.5)

    // New: freecam mouse sensitivity (scale applied to deltas forwarded from MouseHandler mixin)
    private val freecamMouseSensitivity = NumberSetting("Mouse Sensitivity", 0.2, 0.1, 1.0, 0.1)

    var freecamPos: Vec3 = Vec3.ZERO
        private set
    private var prevFreecamPos: Vec3 = Vec3.ZERO

    private var originalCameraType: CameraType? = null

    // Freecam rotation
    var yaw = 0f
    var pitch = 0f

    var lastYaw = 0f
    var lastPitch = 0f

    init {
        addSettings(flySpeed, sprintMultiplier, freecamMouseSensitivity)

        // Main tick handler for freecam movement and safety checks
        ClientTickEvents.END_CLIENT_TICK.register {
            // Safety: if module disabled externally, do nothing
            if (!isEnabled) return@register

            // If we lost player or level (left server / world unloaded), disable freecam
            // This covers "leave server" reliably across versions.
            if (mc.player == null || mc.level == null) {
                if (isEnabled) toggle()
                return@register
            }

            tick()
        }

        // When the client is stopping (quit game), ensure freecam is turned off
        ClientLifecycleEvents.CLIENT_STOPPING.register { client ->
            if (isEnabled) toggle()
        }
    }

    override fun onEnable() {
        active = true
        startFreecam()
    }

    override fun onDisable() {
        active = false
        stopFreecam()
    }

    private fun startFreecam() {
        val player = mc.player ?: return

        freecamPos = player.eyePosition
        prevFreecamPos = freecamPos

        yaw = player.yRot
        pitch = player.xRot

        lastYaw = yaw
        lastPitch = pitch

        originalCameraType = mc.options.cameraType
        mc.options.cameraType = CameraType.THIRD_PERSON_BACK
    }

    private fun stopFreecam() {
        originalCameraType?.let { mc.options.cameraType = it }
        originalCameraType = null
    }

    /**
     * Called from the MouseHandler mixin with the rotation deltas computed by vanilla.
     * We scale them by the freecamMouseSensitivity setting so the feel is tunable.
     */
    fun changeLookDirection(deltaX: Double, deltaY: Double) {
        lastYaw = yaw
        lastPitch = pitch

        val scale = freecamMouseSensitivity.value

        yaw += (deltaX * scale).toFloat()
        pitch += (deltaY * scale).toFloat()

        pitch = pitch.coerceIn(-90f, 90f)
    }

    private fun tick() {
        val player = mc.player ?: return
        val options = mc.options

        prevFreecamPos = freecamPos

        var forward = 0.0
        var strafe = 0.0
        var vertical = 0.0

        if (options.keyUp.isDown) forward += 1.0
        if (options.keyDown.isDown) forward -= 1.0
        if (options.keyRight.isDown) strafe -= 1.0
        if (options.keyLeft.isDown) strafe += 1.0
        if (options.keyJump.isDown) vertical += 1.0
        if (options.keyShift.isDown) vertical -= 1.0

        // No movement input -> nothing to do
        if (forward == 0.0 && strafe == 0.0 && vertical == 0.0) return

        val speed = flySpeed.value * (if (options.keySprint.isDown) sprintMultiplier.value else 1.0)

        // Use freecam yaw/pitch (not player) for movement direction
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())

        val forwardX = -sin(yawRad) * cos(pitchRad)
        val forwardY = -sin(pitchRad)
        val forwardZ = cos(yawRad) * cos(pitchRad)

        val strafeX = cos(yawRad)
        val strafeZ = sin(yawRad)

        var dx = forwardX * forward + strafeX * strafe
        var dy = forwardY * forward + vertical
        var dz = forwardZ * forward + strafeZ * strafe

        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length > 1.0) {
            dx /= length; dy /= length; dz /= length
        }

        freecamPos = freecamPos.add(dx * speed, dy * speed, dz * speed)
    }

    fun getInterpolatedPos(partialTick: Float): Vec3 {
        val t = partialTick.toDouble()
        return Vec3(
            prevFreecamPos.x + (freecamPos.x - prevFreecamPos.x) * t,
            prevFreecamPos.y + (freecamPos.y - prevFreecamPos.y) * t,
            prevFreecamPos.z + (freecamPos.z - prevFreecamPos.z) * t
        )
    }

    fun getYaw(partialTick: Float): Float {
        return lastYaw + (yaw - lastYaw) * partialTick
    }

    fun getPitch(partialTick: Float): Float {
        return lastPitch + (pitch - lastPitch) * partialTick
    }
}
