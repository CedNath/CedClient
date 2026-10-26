//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import java.util.Random
import kotlin.math.roundToInt

/**
 * Auto Terminals, after Noamm's AutoTerminal: clicks the open F7 terminal's solution for you. Needs Terminal Solver on (it
 * reads the terminal); its First Click Delay is the wait before the first click. Between clicks it waits a fixed
 * Click Delay, or a random one (normal distribution between min and max). Click Order picks which tile comes next.
 * Melody clicks the button once the lime pane is on the magenta one.
 * shortcut: Human / Skizo order is "nearest / furthest from the last click" only; Noamm's also weighs neighbours. Fake InvWalk and Melody Skip are not here.
 */
object AutoTerminals : Module(
    "Auto Terminals",
    Category.Cheats,
    "Clicks the F7 terminals for you. Needs Terminal Solver on."
) {
    private val randomDelay = BooleanSetting("Random Delay", true, "Normal distributed by min and max.")
    private val clickDelay = NumberSetting("Click Delay", 150.0, 80.0, 500.0, 10.0, "Fixed delay between clicks in milliseconds.").withDependency { !randomDelay.value }
    private val minDelay = NumberSetting("Min Random Delay", 120.0, 50.0, 500.0, 10.0, "The minimum possible delay.").withDependency { randomDelay.value }
    private val maxDelay = NumberSetting("Max Random Delay", 150.0, 80.0, 500.0, 10.0, "The maximum possible delay.").withDependency { randomDelay.value }
    private val order = DropdownSetting("Click Order", listOf("None", "Random", "Human", "Skizo"), "Human", "Human: the nearest tile to your last click. Skizo: the furthest.")
    private val numbers = BooleanSetting("Numbers", true, "Click In Order.")
    private val colors = BooleanSetting("Colors", true, "Select All The Color Items.")
    private val rubix = BooleanSetting("Rubix", true, "Change All To Same Color.")
    private val redGreen = BooleanSetting("Red-Green", true, "Correct All The Panes.")
    private val startWith = BooleanSetting("Start-With", true, "What Starts With.")
    private val melody = BooleanSetting("Melody", true, "Click the button when the lime pane is on the magenta one.")

    private val rng = Random()
    private var lastClick = 0L
    private var lastSlot = -1
    private var wait = 0L

    init {
        addSettings(randomDelay, clickDelay, minDelay, maxDelay, order, numbers, colors, rubix, redGreen, startWith, melody)
    }

    private fun on(kind: String) = when (kind) {
        "Numbers" -> numbers.value
        "Colors" -> colors.value
        "Rubix" -> rubix.value
        "Panes" -> redGreen.value
        "Name" -> startWith.value
        else -> melody.value
    }

    private fun nextWait(): Long {
        if (!randomDelay.value) return clickDelay.value.toLong()
        val lo = minOf(minDelay.value, maxDelay.value); val hi = maxOf(minDelay.value, maxDelay.value)
        return (rng.nextGaussian() * (hi - lo) / 4 + (lo + hi) / 2).coerceIn(lo, hi).roundToInt().toLong()
    }

    private fun dist(a: Int, b: Int) = (a % 9 - b % 9).let { it * it } + (a / 9 - b / 9).let { it * it }

    /** Called every frame by Terminal Solver with the slots that can be clicked now. */
    fun tick(screen: AbstractContainerScreen<*>, kind: String, slots: List<Int>) {
        if (!isEnabled || slots.isEmpty() || !on(kind)) return
        val now = System.currentTimeMillis()
        // The first click of a terminal waits for the solver's First Click Delay (already a gate there).
        if (TerminalSolver.sinceOpen() < TerminalSolver.firstClickDelay()) { lastClick = now; wait = 0L; return }
        if (now - lastClick < wait) return
        val from = if (lastSlot >= 0) lastSlot else 22
        val slot = when (order.value) {
            "Random" -> slots.random()
            "Human" -> slots.minBy { dist(it, from) }
            "Skizo" -> slots.maxBy { dist(it, from) }
            else -> slots.first()
        }
        if (TerminalSolver.autoClick(screen, slot)) { lastClick = now; lastSlot = slot; wait = nextWait() }
    }
}
//#endif
