package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.utils.Debug
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import ced.cedclient.utils.Keys

/**
 * Look around your character in third person without turning it. While active the mouse moves only the camera
 * (PerspectiveTurnMixin takes the turn away from the player, PerspectiveCameraMixin feeds the camera [yaw] / [pitch]),
 * so your heading, aim and movement stay as they were. Visual only: nothing is sent to the server.
 */
object Perspective : Module(
    "Perspective", Category.Visuals,
    "Look around your character in third person without changing where you face."
) {
    private val key = KeybindSetting("Key", Keys.LEFT_ALT, "Hold (or press, see Mode) to look around.")
    private val mode = DropdownSetting("Mode", listOf("Hold", "Toggle"), "Hold")

    private var active = false
    private var toggled = false
    private var keyWasDown = false
    private var savedType = CameraType.FIRST_PERSON
    var yaw = 0f
        private set
    var pitch = 0f
        private set

    init {
        addSettings(key, mode)
        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> stop() }
    }

    /** Mixins read this. */
    fun isActive(): Boolean = active

    override fun onDisable() { toggled = false; stop() }

    private fun tick(client: Minecraft) {
        val down = isEnabled && client.gui.screen() == null && Keys.isDown(key.value)
        if (down && !keyWasDown && mode.value == "Toggle") toggled = !toggled
        keyWasDown = down

        val want = isEnabled && client.player != null && (if (mode.value == "Toggle") toggled else down)
        if (want && !active) start(client) else if (!want && active) stop()
    }

    private fun start(client: Minecraft) {
        val player = client.player ?: return
        savedType = client.options.cameraType
        yaw = player.yRot
        pitch = player.xRot
        client.options.cameraType = CameraType.THIRD_PERSON_BACK
        active = true
        Debug.line(name, "started, camera was $savedType")
    }

    private fun stop() {
        if (!active) return
        active = false
        Minecraft.getInstance().options.cameraType = savedType
        Debug.line(name, "stopped, camera back to $savedType")
    }

    /** Mixin (MouseHandler.turnPlayer): the same maths as Entity.turn, applied to the camera only. */
    fun turn(dx: Double, dy: Double) {
        yaw += (dx * 0.15).toFloat()
        pitch = (pitch + (dy * 0.15).toFloat()).coerceIn(-90f, 90f)
    }
}
