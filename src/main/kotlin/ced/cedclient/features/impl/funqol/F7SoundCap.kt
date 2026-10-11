package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.state.DungeonState
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.resources.Identifier

/**
 * Tames the F7/M7 boss sound spam (withers, explosions, P5 dragons). While in the Floor 7 boss room:
 *  - each distinct sound plays at most [maxRepeats] times per tick, extra copies are dropped
 *  - every sound's final volume is capped at [volumeCap]% (0 = mute everything)
 * Hooked from SoundEngineMixin, so it covers server packets and client-side sounds alike.
 */
object F7SoundCap : Module(
    "F7SoundCap",
    Category.Skyblock,
    "Caps repeated sounds and volume inside the F7/M7 boss"
) {
    private val maxRepeats = NumberSetting("Max Repeats Per Tick", 2.0, 1.0, 10.0, 1.0,
        "How many copies of the same sound may start in one tick")
    private val volumeCap = NumberSetting("Volume Cap %", 40.0, 0.0, 100.0, 5.0,
        "Upper limit for every sound's volume in the boss (0 mutes everything)")

    private val playedThisTick = HashMap<Identifier, Int>()

    init {
        addSettings(maxRepeats, volumeCap)
        ClientTickEvents.END_CLIENT_TICK.register { playedThisTick.clear() }
    }

    private val active: Boolean
        get() = isEnabled && DungeonState.inBoss && (DungeonState.floor == "F7" || DungeonState.floor == "M7")

    /** True if this sound should not start at all. */
    @JvmStatic
    fun shouldCancel(instance: SoundInstance): Boolean {
        if (!active) return false
        if (volumeCap.value <= 0.0) return true
        val count = playedThisTick.merge(instance.identifier, 1, Int::plus)!!
        return count > maxRepeats.value
    }

    /** Final channel volume, capped while active. */
    @JvmStatic
    fun capVolume(volume: Float): Float =
        if (active) minOf(volume, (volumeCap.value / 100.0).toFloat()) else volume
}
