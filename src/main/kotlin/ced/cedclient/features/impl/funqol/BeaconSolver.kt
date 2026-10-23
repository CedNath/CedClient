//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.events.PlaySoundEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.utils.CursorGlide
import ced.cedclient.utils.loreLines
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.ContainerInput
import kotlin.math.abs

/**
 * Moonglade Beacon ("Tune Frequency"): reads the reference color, speed and pitch the way SkyHanni's solver does, then
 * clicks your color / speed / pitch buttons until they match. Which click moves a value up or down is learned from the
 * first clicks (a click that changes nothing is dropped), so it doesn't depend on a guess.
 *  - color: the stained glass pane in the match row (slots 10-16), yours is in the change row (28-34) and the button's lore
 *  - speed: ticks the match pane takes to move one slot (52 / 42 / 32 / 22 / 12 = speed 1-5)
 *  - pitch: the note block bass sound that lands when the match pane moves
 * Only the Tune Frequency screen is handled, not "Upgrade Signal Strength".
 */
object BeaconSolver : Module(
    "Beacon Solver",
    Category.Cheats,
    "Moonglade beacon: sets the color, speed and pitch to the reference by itself."
) {
    private val autoClick = BooleanSetting("Auto Click", true, "Clicks the buttons itself. Off: only reads (use Debug to see it).")
    private val clickDelay = NumberSetting("Click Delay ms", 300.0, 50.0, 1000.0, 10.0)
    private val smoothCursor = BooleanSetting("Smooth Cursor", true, "Moves the cursor to each button before clicking it.")
    private val debug = BooleanSetting("Debug", false, "Prints what was read and every click to chat (only to you).")

    private val mc get() = Minecraft.getInstance()

    private const val COLOR_SLOT = 46
    private const val SPEED_SLOT = 48
    private const val PITCH_SLOT = 50
    private val MATCH = 10..16
    private val CHANGE = 28..34
    private val COLORS = listOf("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "cyan", "purple", "blue", "brown", "green", "red")
    private val PITCHES = listOf("low" to 0.0952381f, "normal" to 0.7936508f, "high" to 1.4920635f)
    private val SPEED_TICKS = listOf(52, 42, 32, 22, 12)
    private const val SOUND_WINDOW_MS = 250L

    private val colorRegex = Regex("Current color: ([\\w ]+)")
    private val speedRegex = Regex("Current speed: (\\d+)")
    private val pitchRegex = Regex("Current pitch: (\\w+)")

    private class Axis(val name: String, val size: Int, val slot: Int) {
        var ref: Int? = null
        var ours: Int? = null

        /** Steps from yours to the reference, the short way round (negative = down); null until both are known. */
        fun offset(): Int? {
            val r = ref ?: return null
            val o = ours ?: return null
            val d = ((r - o) % size + size) % size
            return if (d > size / 2) d - size else d
        }
    }

    private val color = Axis("color", COLORS.size, COLOR_SLOT)
    private val speed = Axis("speed", SPEED_TICKS.size, SPEED_SLOT)
    private val pitch = Axis("pitch", PITCHES.size, PITCH_SLOT)
    private val axes = listOf(color, speed, pitch)

    // Reference pane movement (client ticks between moves) and sounds, for speed and pitch.
    private var tickNow = 0
    private var lastMatchSlot = -1
    private var lastMatchTick = 0
    private var lastMatchMs = 0L
    private var lastChangeSlot = -1
    private var lastChangeMs = 0L
    private val gaps = ArrayList<Int>()
    private val pitchVotes = ArrayList<Int>()
    private var lastVoteMs = 0L

    /** A click type. [effect] = how one click moves a value: left +1 (up), right -1 (down). */
    private class Button(val label: String, val button: Int, val effect: Int)
    private val buttons = listOf(Button("left", 0, 1), Button("right", 1, -1))

    // After the values match: pause your pane, then let it go when the two panes are in the same place moving the same way.
    private const val PAUSE_SLOT = 52
    /** 0 = matching values, 1 = pausing your pane, 2 = waiting for the panes to line up, 3 = done. */
    private var phase = 0

    // The button's lore says "Currently: UNPAUSED" / "Currently: PAUSED"; the item itself may not change.
    private fun paused(slots: List<net.minecraft.world.inventory.Slot>) =
        slots[PAUSE_SLOT].item.let { it.`is`(net.minecraft.world.item.Items.DYED_TERRACOTTA.red()) || it.loreLines.any { l -> l.contains("Currently: PAUSED") } }

    private class Pending(val axis: Axis, val button: Button, val before: Int, val at: Long)
    private var pending: Pending? = null
    private var lastClickMs = 0L
    private var lastStatusMs = 0L
    private var lastScreen: Any? = null

    private fun dbg(msg: String) { if (debug.value) mc.player?.sendSystemMessage(Component.literal("§8[beacon] §7$msg")) }

    init {
        addSettings(autoClick, clickDelay, smoothCursor, debug)

        on<PlaySoundEvent> {
            if (!isEnabled || !it.soundName.endsWith("block.note_block.bass") || !open()) return@on
            val idx = PITCHES.indexOfFirst { p -> abs(p.second - it.pitch) < 0.01f }
            dbg("bass pitch=${it.pitch} -> ${if (idx < 0) "unknown" else PITCHES[idx].first}")
            if (idx < 0) return@on
            // Both panes ring: the one that isn't yours is the reference. Only yours heard for a while = same pitch.
            val ours = pitch.ours ?: return@on
            if (idx != ours) pitchVotes += idx else sameVotes++
            if (pitchVotes.size > 6) pitchVotes.removeAt(0)
            if (pitchVotes.size >= 3 && pitchVotes.takeLast(3).distinct().size == 1) pitch.ref = pitchVotes.last()
            else if (pitchVotes.isEmpty() && sameVotes >= 6) pitch.ref = ours
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            tickNow++
            val screen = mc.gui.screen()
            if (screen !== lastScreen) { lastScreen = screen; reset() }
            if (!isEnabled || !open()) return@register
            read(screen as AbstractContainerScreen<*>)
            if (autoClick.value) solve(screen)
        }
    }

    private var sameVotes = 0

    private fun open() = (mc.gui.screen() as? AbstractContainerScreen<*>)?.let { it.title.string.contains("Tune Frequency") && it.menu.slots.size >= 54 } == true

    private fun reset() {
        axes.forEach { it.ref = null; it.ours = null }
        gaps.clear(); pitchVotes.clear(); sameVotes = 0
        lastMatchSlot = -1; lastChangeSlot = -1
        phase = 0
        pending = null
    }

    private fun paneColor(stack: net.minecraft.world.item.ItemStack): Int? {
        val path = BuiltInRegistries.ITEM.getKey(stack.item).path
        if (!path.endsWith("_stained_glass_pane")) return null
        return COLORS.indexOf(path.removeSuffix("_stained_glass_pane")).takeIf { it >= 0 }
    }

    private fun read(screen: AbstractContainerScreen<*>) {
        val slots = screen.menu.slots
        val now = System.currentTimeMillis()
        // Reference pane: where it is (speed) and its color.
        val matchAt = MATCH.firstOrNull { paneColor(slots[it].item) != null }
        if (matchAt != null) {
            color.ref = paneColor(slots[matchAt].item)
            if (matchAt != lastMatchSlot) {
                if (lastMatchSlot >= 0) {
                    gaps += tickNow - lastMatchTick
                    if (gaps.size > 10) gaps.removeAt(0)
                    if (gaps.size >= 2) estimateSpeed()
                }
                lastMatchSlot = matchAt; lastMatchTick = tickNow; lastMatchMs = now
            }
        }
        val changeAt = CHANGE.firstOrNull { paneColor(slots[it].item) != null }
        if (changeAt != null && changeAt != lastChangeSlot) {
            lastChangeSlot = changeAt; lastChangeMs = now
        }
        // Your values, from the buttons' lore.
        slots[COLOR_SLOT].item.loreLines.firstNotNullOfOrNull { colorRegex.find(it) }?.let { m ->
            color.ours = COLORS.indexOf(m.groupValues[1].trim().lowercase().replace(' ', '_')).takeIf { it >= 0 }
        }
        slots[SPEED_SLOT].item.loreLines.firstNotNullOfOrNull { speedRegex.find(it) }?.let { m ->
            speed.ours = (m.groupValues[1].toInt() - 1).takeIf { it in SPEED_TICKS.indices }
        }
        slots[PITCH_SLOT].item.loreLines.firstNotNullOfOrNull { pitchRegex.find(it) }?.let { m ->
            pitch.ours = PITCHES.indexOfFirst { it.first == m.groupValues[1].lowercase() }.takeIf { it >= 0 }
        }
    }

    /** Median gap, then the nearest of the five speeds (the outliers from lag are dropped like SkyHanni does). */
    private fun estimateSpeed() {
        val sorted = gaps.sorted()
        val median = sorted[sorted.size / 2].toDouble()
        val good = gaps.filter { it.toDouble() in (median * 0.8)..(median * 1.2) }.ifEmpty { return }
        val avg = good.average()
        speed.ref = SPEED_TICKS.indices.minBy { abs(SPEED_TICKS[it] - avg) }
    }

    /** Click [slot], after the cursor has glided to it when Smooth Cursor is on. */
    private fun clickSlot(screen: AbstractContainerScreen<*>, slot: Int, button: Int, glide: Boolean = true, after: () -> Unit) {
        val player = mc.player ?: return
        val click = {
            mc.gameMode?.handleContainerInput(screen.menu.containerId, slot, button, ContainerInput.PICKUP, player)
            lastClickMs = System.currentTimeMillis()
            after()
        }
        if (glide && smoothCursor.value) CursorGlide.moveTo(screen, screen.menu.slots[slot], clickDelay.value.toLong(), click) else click()
    }

    private fun solve(screen: AbstractContainerScreen<*>) {
        if (CursorGlide.busy) return
        val now = System.currentTimeMillis()
        // The lore lags the click: wait for the value to change (or give up) before the next one.
        pending?.let { p ->
            if (p.axis.ours != p.before || now - p.at > 1200) pending = null else return
        }
        if (now - lastClickMs < clickDelay.value.toLong()) return
        val axis = axes.firstOrNull { (it.offset() ?: 0) != 0 }
        if (axis == null) {
            // Nothing left to change: wait until color and speed are known, then line the panes up (pitch can stay unheard).
            if (color.offset() == null || speed.offset() == null) {
                if (now - lastStatusMs > 2000) { lastStatusMs = now; dbg("waiting: color ${color.ref}/${color.ours} speed ${speed.ref}/${speed.ours} pitch ${pitch.ref}/${pitch.ours}") }
                return
            }
            align(screen)
            return
        }
        phase = 0
        val off = axis.offset() ?: return
        val want = if (off > 0) 1 else -1
        val button = buttons.first { it.effect == want }
        val before = axis.ours ?: return
        clickSlot(screen, axis.slot, button.button) {
            pending = Pending(axis, button, before, System.currentTimeMillis())
            dbg("${axis.name} ref=${axis.ref} ours=$before offset=$off -> ${button.label} click")
        }
    }

    /** Values match: pause your pane, then unpause it the moment the reference is in its column going the same way. */
    private fun align(screen: AbstractContainerScreen<*>) {
        val slots = screen.menu.slots
        val isPaused = paused(slots)
        // The unpause is timed to the panes, so it clicks at once: the cursor is already on the button from pausing.
        fun clickPause(why: String, glide: Boolean = true) = clickSlot(screen, PAUSE_SLOT, 0, glide) { dbg(why) }
        when (phase) {
            0, 1 -> {
                phase = 1
                if (isPaused) { phase = 2; dbg("paused, waiting for the panes to line up") }
                else if (System.currentTimeMillis() - lastClickMs > 1000) clickPause("values match -> pausing your pane")
            }
            2 -> {
                if (!isPaused) { phase = 3; return }
                val same = lastMatchSlot - MATCH.first == lastChangeSlot - CHANGE.first && lastMatchSlot >= 0 && lastChangeSlot >= 0
                if (same) { clickPause("lined up (column ${lastMatchSlot - MATCH.first}) -> unpausing", glide = false); phase = 3 }
            }
        }
    }
}
//#endif
