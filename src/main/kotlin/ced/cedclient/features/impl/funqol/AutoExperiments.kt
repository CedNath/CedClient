package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.mixin.accessor.AbstractContainerScreenAccessor
import ced.cedclient.utils.CursorGlide
import ced.cedclient.utils.cleanName
import ced.cedclient.utils.loreLines
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Solves the Chronomatron and Ultrasequencer experiments at the Experimentation Table: highlights
 * the next slots to click and, with Auto Click on, clicks them itself.
 */
object AutoExperiments : Module(
    "Auto Experiments",
    Category.Cheats,
    "Shows and automatically clicks the Chronomatron and Ultrasequencer solutions"
) {
    private val mc: Minecraft = Minecraft.getInstance()

    private val autoClick = BooleanSetting("Auto Click", true)
    private val showSolver = BooleanSetting("Show Solver", true)
    private val clickDelay = NumberSetting("Click Delay ms", 400.0, 0.0, 1000.0, 10.0)
    private val autoClose = BooleanSetting("Auto Close", true)
    private val serumCount = NumberSetting("Serum Count", 0.0, 0.0, 3.0, 1.0)
    private val getMaxXp = BooleanSetting("Get Max XP", false)
    private val superpairsSolver = BooleanSetting("Superpairs Solver", true)

    private val numberName = Regex("\\d+")

    // Next, second and third click.
    private val highlightColors = intArrayOf(0xA000E000.toInt(), 0xA0E0E000.toInt(), 0x70E05000)

    private val chronomatronOrder = ArrayList<Int>()
    private val ultrasequencerOrder = HashMap<Int, Int>()
    private var lastScreen: Any? = null
    private var lastClickTime = 0L
    private var hasAdded = false
    private var lastAdded = -1
    private var clicks = 0

    // Set each tick: which experiment is open and whether it's your turn (clock in the center).
    private var solving: String? = null
    private var yourTurn = false

    /** Toggled by "/cc dev expdebug" (until restart): logs solver state changes and clicks to chat and the log. */
    var debug = false
    private var lastCenter: Any? = null

    private fun dbg(msg: String) {
        if (!debug) return
        println("[AutoExperiments] $msg")
        mc.player?.sendSystemMessage(net.minecraft.network.chat.Component.literal("§8[exp] §7$msg"))
    }

    init {
        addSettings(autoClick, showSolver, clickDelay, autoClose, serumCount, getMaxXp, superpairsSolver)

        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            if (!isEnabled) return@register
            tick()
        }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            ScreenMouseEvents.allowMouseClick(screen).register { s, event ->
                onMouseClick(s as AbstractContainerScreen<*>, event.x(), event.y())
            }
        }
    }

    // Superpairs: the last card seen face-up in each slot, kept after it flips back face-down.
    private val superpairsSeen = HashMap<Int, ItemStack>()
    private val superpairsCards = HashSet<Int>()
    private val hiddenCardName = Regex("\\?|Click any button!|Click a second button!|Next button is instantly rewarded!")
    private const val SEEN_COLOR = 0xC0C8B400.toInt()
    private const val MATCH_COLOR = 0xC000B400.toInt()
    private const val POWERUP_COLOR = 0xC00090E0.toInt()

    private fun reset() {
        superpairsSeen.clear()
        superpairsCards.clear()
        chronomatronOrder.clear()
        ultrasequencerOrder.clear()
        hasAdded = false
        lastAdded = -1
        clicks = 0
    }

    private fun tick() {
        val screen = mc.screen
        if (screen !== lastScreen) {
            lastScreen = screen
            if (solving != null) dbg("screen changed to '${screen?.title?.string}', state reset")
            reset()
        }
        solving = null
        yourTurn = false
        if (screen !is AbstractContainerScreen<*>) return
        val slots = screen.menu.slots
        if (slots.size < 54) return

        val title = screen.title.string
        val center = slots[49].item.item
        if (center != lastCenter) {
            lastCenter = center
            if (title.startsWith("Chronomatron (") || title.startsWith("Ultrasequencer ("))
                dbg("center -> $center, clicks=$clicks, chrono=$chronomatronOrder, ultra=$ultrasequencerOrder, glideBusy=${CursorGlide.busy}")
        }
        when {
            title.startsWith("Chronomatron (") -> solveChronomatron(screen, slots)
            title.startsWith("Ultrasequencer (") -> solveUltrasequencer(screen, slots)
            title.startsWith("Superpairs (") -> readSuperpairs(slots)
        }
    }

    private fun ItemStack.isHiddenCard() = hiddenCardName.matches(cleanName)

    private fun ItemStack.isPowerup() = loreLines.any { it.contains("powerup", ignoreCase = true) }

    // Same reward = same pair. Books all share a name, so their enchant (lore line 3) tells them apart.
    private fun ItemStack.pairKey() = "$item|$cleanName|" + (if (cleanName == "Enchanted Book") loreLines.getOrNull(2) else "")

    // Clicks left in slot 4; once it's 0 Hypixel flips every card, which isn't a real reveal.
    private fun readSuperpairs(slots: List<Slot>) {
        if (slots[4].item.cleanName.contains("Remaining Clicks: 0")) return
        for (slot in slots) {
            if (slot.container === mc.player?.inventory) continue
            val stack = slot.item
            if (stack.isEmpty) continue
            // Only cards: a slot counts once it has been seen face-down.
            if (stack.isHiddenCard()) superpairsCards.add(slot.index)
            else if (slot.index in superpairsCards) superpairsSeen[slot.index] = stack.copy()
        }
    }

    // Center slot 49: glowstone = watch the pattern, clock = your turn.
    private fun solveChronomatron(screen: AbstractContainerScreen<*>, slots: List<Slot>) {
        val center = slots[49].item.item
        val max = if (getMaxXp.value) 15 else 11 - serumCount.value.toInt()
        solving = "chrono"
        yourTurn = center == Items.CLOCK

        // Close once the last click of the final round is in (one Click Delay after it, so it registers).
        // Waiting for the next round's glowstone could miss the reset and never close.
        if (autoClose.value && hasAdded && chronomatronOrder.size > max && clicks >= chronomatronOrder.size && canClick()) {
            dbg("chrono auto close (round ${chronomatronOrder.size} > $max)")
            mc.player?.closeContainer()
            return
        }
        if (lastAdded != -1 && center == Items.GLOWSTONE && !slots[lastAdded].item.hasFoil()) hasAdded = false
        if (!hasAdded && center == Items.CLOCK) {
            slots.firstOrNull { it.index in 10..43 && it.item.hasFoil() }?.let {
                chronomatronOrder.add(it.index)
                lastAdded = it.index
                hasAdded = true
                clicks = 0
                dbg("chrono added slot ${it.index}, order=$chronomatronOrder")
            }
        }
        if (autoClick.value && hasAdded && center == Items.CLOCK && clicks < chronomatronOrder.size && canClick()) {
            click(screen, chronomatronOrder[clicks])
        }
    }

    private fun solveUltrasequencer(screen: AbstractContainerScreen<*>, slots: List<Slot>) {
        val center = slots[49].item.item
        val max = if (getMaxXp.value) 20 else 9 - serumCount.value.toInt()
        solving = "ultra"
        yourTurn = center == Items.CLOCK

        if (center == Items.CLOCK) hasAdded = false

        // Re-read every tick while watching and keep the fullest read: the numbers can show up over a few ticks,
        // and the first glowstone tick may still show last round's board.
        if (center == Items.GLOWSTONE) {
            val read = HashMap<Int, Int>()
            for (slot in slots) {
                if (slot.index !in 9..44) continue
                val name = ChatFormatting.stripFormatting(slot.item.hoverName.string) ?: continue
                if (name.matches(numberName)) read[slot.item.count - 1] = slot.index
            }
            if (read.isNotEmpty() && (!hasAdded || read.size > ultrasequencerOrder.size)) {
                ultrasequencerOrder.clear()
                ultrasequencerOrder.putAll(read)
                hasAdded = true
                clicks = 0
                dbg("ultra read order=${ultrasequencerOrder.toSortedMap()}")
                if (autoClose.value && ultrasequencerOrder.size > max) { dbg("ultra auto close (${ultrasequencerOrder.size} > $max)"); mc.player?.closeContainer(); return }
            }
        }
        if (autoClick.value && center == Items.CLOCK && canClick()) {
            ultrasequencerOrder[clicks]?.let { click(screen, it) }
        }
    }

    /** Called by ContainerContentsMixin right after the slots are drawn, so tooltips stay on top. */
    fun drawOverlays(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor) {
        if (!isEnabled) return
        if (showSolver.value) drawSolver(screen, g)
        if (superpairsSolver.value) drawSuperpairs(screen, g)
    }

    /** Slots still to click this round, in order. Empty unless it's your turn. */
    private fun pendingSlots(): List<Int> {
        if (!yourTurn) return emptyList()
        return when (solving) {
            "chrono" -> if (hasAdded) chronomatronOrder.drop(clicks) else emptyList()
            "ultra" -> generateSequence(clicks) { it + 1 }.map { ultrasequencerOrder[it] }.takeWhile { it != null }.filterNotNull().toList()
            else -> emptyList()
        }
    }

    private fun drawSolver(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor) {
        if (screen !== mc.screen) return
        val pending = pendingSlots()
        if (pending.isEmpty()) return
        val acc = screen as AbstractContainerScreenAccessor
        val font = mc.font
        pending.take(highlightColors.size).forEachIndexed { i, index ->
            val slot = screen.menu.slots.getOrNull(index) ?: return@forEachIndexed
            val x = acc.`ced$getLeftPos`() + slot.x
            val y = acc.`ced$getTopPos`() + slot.y
            g.fill(x, y, x + 16, y + 16, highlightColors[i])
            val label = (clicks + i + 1).toString()
            g.text(font, label, x + 17 - font.width(label), y + 9, -1, true)
        }
    }

    /**
     * Face-down cards you've already seen show their item again:
     * green = its pair is known too, yellow = seen once, blue = powerup.
     */
    private fun drawSuperpairs(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor) {
        if (screen !== mc.screen || superpairsSeen.isEmpty() || !screen.title.string.startsWith("Superpairs (")) return
        val slots = screen.menu.slots
        val hidden = superpairsSeen.filterKeys { slots.getOrNull(it)?.item?.isHiddenCard() == true }
        val keyCounts = hidden.values.groupingBy { it.pairKey() }.eachCount()
        val acc = screen as AbstractContainerScreenAccessor
        for ((index, stack) in hidden) {
            val slot = slots[index]
            val x = acc.`ced$getLeftPos`() + slot.x
            val y = acc.`ced$getTopPos`() + slot.y
            val color = when {
                stack.isPowerup() -> POWERUP_COLOR
                (keyCounts[stack.pairKey()] ?: 0) >= 2 -> MATCH_COLOR
                else -> SEEN_COLOR
            }
            g.fill(x, y, x + 16, y + 16, color)
            g.item(stack, x, y)
        }
    }

    /**
     * Auto Click on: your own clicks are blocked during your turn so they can't break the order.
     * Auto Click off: a click on the right slot moves the solver on to the next one.
     */
    private fun onMouseClick(screen: AbstractContainerScreen<*>, mx: Double, my: Double): Boolean {
        if (!isEnabled || screen !== mc.screen) return true
        val pending = pendingSlots()
        if (pending.isEmpty()) return true
        if (autoClick.value) return false

        val acc = screen as AbstractContainerScreenAccessor
        val slot = screen.menu.slots.getOrNull(pending[0]) ?: return true
        val x = acc.`ced$getLeftPos`() + slot.x
        val y = acc.`ced$getTopPos`() + slot.y
        if (mx >= x && mx < x + 16 && my >= y && my < y + 16) {
            clicks++
            lastClickTime = System.currentTimeMillis()
        }
        return true
    }

    private fun canClick() = !CursorGlide.busy && System.currentTimeMillis() - lastClickTime >= clickDelay.value.toLong()

    // Middle click (clone): never moves an item, so a mis-click can't pick anything up. The cursor travels there first.
    // Click Delay counts from the glide's start (the glide fits inside it): clicks stay one delay apart,
    // not glide + delay, which was slow enough to run out the experiment timer.
    private fun click(screen: AbstractContainerScreen<*>, slot: Int) {
        val id = screen.menu.containerId
        val start = System.currentTimeMillis()
        lastClickTime = start
        dbg("glide -> slot $slot (click #$clicks)")
        // No "clicked" line after a "glide" line = the glide was dropped (screen changed mid-glide).
        CursorGlide.moveTo(screen, screen.menu.slots[slot], clickDelay.value.toLong()) {
            val player = mc.player ?: return@moveTo
            mc.gameMode?.handleContainerInput(id, slot, 2, ContainerInput.CLONE, player)
            clicks++ // counted on landing, so the solver keeps showing the slot the cursor is still heading to
            dbg("clicked slot $slot after ${System.currentTimeMillis() - start}ms, center=${screen.menu.slots[49].item.item}")
        }
    }
}
