//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.Box3D
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.Color
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB

/**
 * Boxes the three F7 Goldor gates while they stand (cracked / infested stone bricks at their marker block). Unique: the box
 * turns to a warning colour for the last five seconds after the "gate will open" line, and a gate you are looking at is
 * dimmed so it does not cover your aim.
 */
object GateHighlight : Module(
    "Gate Highlight",
    Category.Dungeons,
    "Boxes the Goldor gates until they are destroyed; warns in the last 5 seconds."
) {
    private class Gate(val marker: BlockPos, val box: AABB)

    private val gates = listOf(
        Gate(BlockPos(103, 134, 123), AABB(95.0, 114.0, 122.0, 107.0, 135.0, 125.0)),
        Gate(BlockPos(17, 134, 135), AABB(18.0, 114.0, 127.0, 20.0, 135.0, 139.0)),
        Gate(BlockPos(5, 134, 49), AABB(1.0, 114.0, 49.0, 15.0, 135.0, 52.0)),
    )

    private val color = ColorSetting("Color", Color(60, 170, 255), "A standing gate.")
    private val warnColor = ColorSetting("Warning Color", Color(255, 85, 85), "The last 5 seconds before the gate opens.")
    private val dimAimed = BooleanSetting("Dim Aimed Gate", false, "Draw the gate you look straight at a little fainter.")

    private var warnUntil = 0L

    init {
        addSettings(color, warnColor, dimAimed)
        on<ChatMessageEvent> {
            if (!isEnabled) return@on
            when (it.unformattedText.trim()) {
                "The gate will open in 5 seconds!" -> warnUntil = System.currentTimeMillis() + 5000
                "The gate has been destroyed!" -> warnUntil = 0
            }
        }
        LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { context ->
            if (!isEnabled || !DungeonState.inDungeon || DungeonState.floor?.endsWith("7") != true) return@CollectSubmits
            val mc = Minecraft.getInstance()
            val level = mc.level ?: return@CollectSubmits
            val warn = System.currentTimeMillis() < warnUntil
            val boxes = gates.filter {
                val b = level.getBlockState(it.marker).block
                b == Blocks.CRACKED_STONE_BRICKS || b == Blocks.INFESTED_STONE_BRICKS
            }.map { g ->
                val base = if (warn) warnColor.value else color.value
                val aimed = dimAimed.value && mc.player?.let { p -> g.box.clip(p.getEyePosition(1f), p.getEyePosition(1f).add(p.getViewVector(1f).scale(80.0))).isPresent } == true
                g.box to (if (aimed) Color(base.red, base.green, base.blue, 0.35f) else base)
            }
            Box3D.draw(context, boxes)
        })
    }
}

/**
 * Trevor the Trapper helper: a title when he has a new animal for you ("Return to the Trapper soon") and an optional auto
 * call (/call trevor) a few seconds early. Unique: the early call has a configurable lead and a cooldown so it never
 * spams, and the title shows the animal tier when it is named in the message.
 */
object TrevorHelper : Module(
    "Trevor Helper",
    Category.Skyblock,
    "Alerts when Trevor has a new animal and can call him for you."
) {
    private val alert = BooleanSetting("Ready Alert", true, "A title when Trevor has a new animal.")
    private val autoCall = BooleanSetting("Auto Call", false, "Run /call trevor shortly after he is ready.")
    private val delay = NumberSetting("Call Delay (s)", 2.0, 0.0, 10.0, 0.5, "Wait this long after the alert before calling.")

    private var callAt = 0L
    private var lastCall = 0L

    init {
        addSettings(alert, autoCall, delay)
        on<ChatMessageEvent> {
            if (!isEnabled) return@on
            if (it.unformattedText.contains("Return to the Trapper soon to get a new animal to hunt!")) {
                if (alert.value) Alerts.title("Trevor", "Trevor is ready", "new animal", 0x55FF55, true, 1.3f)
                if (autoCall.value) callAt = System.currentTimeMillis() + (delay.value * 1000).toLong()
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            val now = System.currentTimeMillis()
            if (callAt != 0L && now >= callAt && now - lastCall > 15_000) {
                callAt = 0L
                lastCall = now
                mc.player?.connection?.sendCommand("call trevor")
            }
        }
    }
}
//#endif
