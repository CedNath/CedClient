package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

object AutoSprint : Module(
    "AutoSprint",
    Category.Misc,
    "Automatically sprints for you",
            defaultEnabled = true
) {
    private val mc: Minecraft = Minecraft.getInstance()

    private val disableInWater = BooleanSetting(
        "Disable In Water",
        true,
        "Stops auto sprinting while you are in water"
    )

    // True while the sprint key is held down by us (not by the player), so we only
    // ever release a key press we made ourselves.
    private var pressedByUs = false

    init {
        addSettings(disableInWater)

        ClientTickEvents.START_CLIENT_TICK.register { client ->
            if (!isEnabled) return@register
            tick(client)
        }
    }

    private fun tick(client: Minecraft) {
        val player = client.player
        if (player == null || client.level == null) {
            pressedByUs = false
            return
        }

        val inWater = disableInWater.value && player.isInWater
        val shouldSprint = client.screen == null && !inWater

        if (shouldSprint) {
            client.options.keySprint.isDown = true
            pressedByUs = true
        } else if (pressedByUs) {
            release()
            // Already-running sprint (e.g. swimming) doesn't stop just because the key is up.
            if (inWater) player.isSprinting = false
        }
    }

    private fun release() {
        mc.options.keySprint.isDown = false
        pressedByUs = false
    }

    override fun onDisable() {
        if (pressedByUs) release()
    }
}