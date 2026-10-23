package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.state.DungeonState
import ced.cedclient.state.PartyApi
import ced.cedclient.utils.PlayerUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft

/** When a dungeon / Kuudra run ends and you lead the party, the same one is queued again. A "!dt" during the run skips it once. */
object AutoRequeue : Module(
    "Auto Requeue",
    Category.Misc,
    "Queues the same dungeon / Kuudra run again when it ends (party leader only; !dt in a run skips it once)."
) {
    private val delay = NumberSetting("Delay (s)", 3.0, 0.0, 30.0, 0.5, "Wait after the run ends before requeueing.")
    private val stopOnLeave = BooleanSetting("Stop On Leave", true, "A player leaving the party cancels the requeue (or skips the one at the end of this run).")

    private val codes = Regex("§.")
    private var sendAt = 0L
    private var wasEnded = false
    private var partySize = 0
    private var leftDuringRun = false

    private fun requeueAfterRun() {
        val skip = PartyCommands.dtThisRun
        PartyCommands.dtThisRun = false
        val left = leftDuringRun
        leftDuringRun = false
        val leader = PartyApi.partyLeader.equals(PlayerUtils.getName() ?: "", ignoreCase = true)
        if (!skip && !left && leader) sendAt = System.currentTimeMillis() + (delay.value * 1000).toLong()
    }

    init {
        addSettings(delay, stopOnLeave)

        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!overlay && isEnabled && message.string.replace(codes, "").trim() == "KUUDRA DOWN!") requeueAfterRun()
        }
        // A queued requeue belongs to the server it was triggered on.
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> sendAt = 0L }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> sendAt = 0L }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!isEnabled) return@register
            val ended = DungeonState.runEnded
            if (ended && !wasEnded) requeueAfterRun()
            wasEnded = ended
            // Someone leaving: cancel a pending requeue, or skip the one at the end of this run.
            val size = PartyApi.partyMembers.size
            if (stopOnLeave.value && size < partySize) { if (sendAt != 0L) sendAt = 0L else leftDuringRun = true }
            partySize = size
            if (sendAt != 0L && System.currentTimeMillis() >= sendAt) {
                sendAt = 0L
                Minecraft.getInstance().player?.connection?.sendCommand("instancerequeue")
            }
        }
    }

    override fun onDisable() { sendAt = 0L; leftDuringRun = false }
}
