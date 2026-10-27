package ced.cedclient.features.impl.funqol

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.NumberSetting
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import kotlin.random.Random

/**
 * Practice the F7 terminals offline: pick one in this module's settings and it opens as a chest-style menu with a timer.
 * Built from how Noamm's and Athen's terminals / simulators lay them out: Click In Order (10 numbers, 5x2), Starts With,
 * Select All Colors, Correct All The Panes, Change All To Same Color and Melody (3 rounds). The chest has the real
 * terminal titles, so Terminal Solver works on it. Best times are kept; Simulated Ping delays each click's result.
 */
object TerminalSimulator : Module("Terminal Simulator", Category.Dungeons, "Practice the F7 terminals offline with a timer and best times.") {
    private val mc get() = Minecraft.getInstance()
    private val gson = Gson()
    private val file by lazy { CedPaths.file("terminal_sim.json") }
    private val best = LinkedHashMap<String, Long>()
    private val pingMs = NumberSetting("Simulated Ping (ms)", 0.0, 0.0, 500.0, 10.0, "How long a click takes to show its result, like a laggy connection (clicks are ignored meanwhile).")

    fun bestOf(name: String): Long? = best[name]
    fun ping(): Long = pingMs.value.toLong()

    fun record(name: String, ms: Long): Boolean {
        val isBest = best[name]?.let { ms < it } ?: true
        if (isBest) { best[name] = ms; runCatching { file.parentFile?.mkdirs(); file.writeText(gson.toJson(best)) } }
        return isBest
    }

    private fun open(kind: Int) {
        if (mc.player == null) return // the chest needs a world (singleplayer is enough)
        TerminalSimScreen.open(kind)
    }

    // The command runs while the chat screen is still closing, which would wipe a screen opened right away: open on the next tick.
    @Volatile private var pendingKind = -2

    /** /cc termsim [order|startswith|colors|panes|rubix|melody]: no argument = a random terminal. */
    fun commandTree(): LiteralArgumentBuilder<FabricClientCommandSource> {
        val root = ClientCommands.literal("termsim").executes { pendingKind = -1; 1 }
        listOf("order", "startswith", "colors", "panes", "rubix", "melody").forEachIndexed { kind, name ->
            root.then(ClientCommands.literal(name).executes { pendingKind = kind; 1 })
        }
        return root
    }

    init {
        ClientTickEvents.END_CLIENT_TICK.register {
            if (pendingKind != -2) { val k = pendingKind; pendingKind = -2; open(k) }
        }
        runCatching {
            val map: Map<String, Long> = gson.fromJson(file.readText(), object : TypeToken<Map<String, Long>>() {}.type)
            best.putAll(map)
        }
        addSettings(
            pingMs,
            ActionSetting("Random Terminal", "A random terminal.") { open(-1) },
            ActionSetting("Click In Order", "Click the numbers 1 to 10 in order.") { open(0) },
            ActionSetting("Starts With", "Click every item that starts with the letter.") { open(1) },
            ActionSetting("Select All Colors", "Click every item of the color.") { open(2) },
            ActionSetting("Correct All The Panes", "Turn every red pane green.") { open(3) },
            ActionSetting("Change All To Same Color", "Left / right click to cycle the 3x3 until they match.") { open(4) },
            ActionSetting("Melody", "Click the button while the moving pane is on the lit column. 3 rounds.") { open(5) },
        )
    }
}

/** An item by its constant name ("RED_WOOL"), or null when this game version has no such item. */
private fun item(name: String): Item? = runCatching { Items::class.java.getField(name).get(null) as Item }.getOrNull()

private abstract class Terminal(val name: String, val rows: Int, val title: String) {
    val slots = Array(rows * 9) { blank() }
    val selected = BooleanArray(rows * 9)
    var done = false
    var misses = 0

    /** Chest title: the real terminal's, so Terminal Solver knows it. */
    open val header: String get() = title

    abstract fun click(slot: Int, button: Int)

    /** Called every frame while running with the ms since the start, for terminals that move on their own. */
    open fun tick(ms: Long) {}

    fun isBlankSlot(slot: Int) = slots[slot].`is`(Items.BLACK_STAINED_GLASS_PANE)

    protected fun blank() = pane(Items.BLACK_STAINED_GLASS_PANE)

    protected fun pane(item: Item, name: String = " ", count: Int = 1) =
        ItemStack(item, count).also { it.set(DataComponents.CUSTOM_NAME, Component.literal(name)) }

    protected fun select(slot: Int) {
        selected[slot] = true
        slots[slot].set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
    }

    companion object {
        /** Rows 1-4, columns 1-7 of the chest. */
        val INNER4 = (1..4).flatMap { r -> (1..7).map { c -> r * 9 + c } }
        val INNER3 = (1..3).flatMap { r -> (1..7).map { c -> r * 9 + c } }
        val CYCLE = listOf(Items.RED_STAINED_GLASS_PANE, Items.ORANGE_STAINED_GLASS_PANE, Items.YELLOW_STAINED_GLASS_PANE, Items.GREEN_STAINED_GLASS_PANE, Items.BLUE_STAINED_GLASS_PANE)
    }
}

/** Click In Order: 10 red panes (stack size = the number) on a 5x2 block; only the lowest number can be clicked, and it turns lime. */
private class ClickInOrder : Terminal("Click In Order", 4, "Click in order!") {
    init {
        val spots = (11..15) + (20..24)
        (1..10).shuffled().zip(spots).forEach { (n, slot) -> slots[slot] = pane(Items.RED_STAINED_GLASS_PANE, "§a$n", n) }
    }

    override fun click(slot: Int, button: Int) {
        val st = slots[slot]
        if (!st.`is`(Items.RED_STAINED_GLASS_PANE)) return
        val lowest = slots.filter { it.`is`(Items.RED_STAINED_GLASS_PANE) }.minOf { it.count }
        if (st.count != lowest) { misses++; return }
        slots[slot] = pane(Items.LIME_STAINED_GLASS_PANE, " ", st.count)
        if (slots.none { it.`is`(Items.RED_STAINED_GLASS_PANE) }) done = true
    }
}

/** What Starts With: a 7x3 block, one guaranteed match and every other slot a match 30% of the time; clicked items glow. */
private class StartsWith : Terminal("Starts With", 5, "") {
    private val letter: Char
    private val titleText: String
    private val matches = HashSet<Int>()
    override val header get() = titleText

    init {
        letter = LETTERS.filter { l -> POOL.any { first(it) == l } }.random()
        titleText = "What starts with: '$letter'?"
        val yes = POOL.filter { first(it) == letter }
        val no = POOL.filter { first(it) != letter }
        val guaranteed = INNER3.random()
        for (slot in INNER3) {
            val match = slot == guaranteed || Random.nextDouble() < 0.3
            slots[slot] = ItemStack((if (match) yes else no).random())
            if (match) matches += slot
        }
    }

    override fun click(slot: Int, button: Int) {
        if (selected[slot]) return
        if (slot !in matches) { misses++; return }
        select(slot)
        if (matches.all { selected[it] }) done = true
    }

    companion object {
        private val LETTERS = listOf('A', 'B', 'C', 'D', 'E', 'G', 'M', 'N', 'R', 'S', 'T', 'W')
        private fun first(i: Item) = ItemStack(i).hoverName.string.first().uppercaseChar()
        private val POOL: List<Item> by lazy {
            listOf(
                "APPLE", "ARROW", "AMETHYST_SHARD", "BOW", "BONE", "BREAD", "BRICK", "BUCKET", "BOOK", "BOWL", "CARROT", "CLOCK", "COAL", "COMPASS",
                "COOKIE", "CHEST", "COBBLESTONE", "CLAY_BALL", "DIAMOND", "DIRT", "DRIED_KELP", "EGG", "EMERALD", "ENDER_PEARL", "GOLD_INGOT", "GUNPOWDER",
                "GLASS_BOTTLE", "GLOWSTONE_DUST", "GRAVEL", "MELON_SLICE", "MILK_BUCKET", "MUSHROOM_STEW", "MAGMA_CREAM", "NETHER_STAR", "NAME_TAG",
                "NETHERITE_INGOT", "REDSTONE", "RAW_IRON", "ROTTEN_FLESH", "RABBIT_FOOT", "SADDLE", "SLIME_BALL", "STICK", "STRING", "SUGAR", "SNOWBALL",
                "SHEARS", "SPIDER_EYE", "STONE", "TORCH", "TNT", "TRIDENT", "WHEAT", "WHEAT_SEEDS", "WATER_BUCKET", "WOODEN_SWORD",
            ).mapNotNull { item(it) }
        }
    }
}

/** Select All Colors: a 7x4 block, one guaranteed item of the color and 1 in 4 others; clicked items glow. */
private class SelectColors : Terminal("Select All Colors", 6, "") {
    private val targetSlots = HashSet<Int>()
    private val titleText: String
    override val header get() = titleText

    init {
        val target = COLORS.random()
        titleText = "Select all the ${target.replace("LIGHT_GRAY", "SILVER").replace('_', ' ')} items!"
        val yes = itemsOf(target)
        val no = COLORS.filter { it != target }.flatMap { itemsOf(it) }
        val guaranteed = INNER4.random()
        for (slot in INNER4) {
            val match = slot == guaranteed || Random.nextInt(4) == 0
            slots[slot] = ItemStack((if (match) yes else no).random())
            if (match) targetSlots += slot
        }
    }

    override fun click(slot: Int, button: Int) {
        if (selected[slot]) return
        if (slot !in targetSlots) { misses++; return }
        select(slot)
        if (targetSlots.all { selected[it] }) done = true
    }

    companion object {
        private val COLORS = listOf("WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY", "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK")
        private val EXTRA = mapOf("WHITE" to "BONE_MEAL", "BLUE" to "LAPIS_LAZULI", "BLACK" to "INK_SAC", "BROWN" to "COCOA_BEANS")

        private fun itemsOf(color: String): List<Item> =
            (listOf("${color}_STAINED_GLASS", "${color}_WOOL", "${color}_CONCRETE", "${color}_DYE") + listOfNotNull(EXTRA[color])).mapNotNull { item(it) }
    }
}

/** Correct All The Panes: a 5x3 block of red (75%) and lime panes; each click flips one; done when none are red. */
private class CorrectPanes : Terminal("Correct All The Panes", 5, "Correct all the panes!") {
    private val spots = (1..3).flatMap { r -> (2..6).map { c -> r * 9 + c } }

    init {
        spots.forEach { slots[it] = pane(if (Random.nextDouble() < 0.25) Items.LIME_STAINED_GLASS_PANE else Items.RED_STAINED_GLASS_PANE) }
        if (spots.none { slots[it].`is`(Items.RED_STAINED_GLASS_PANE) }) slots[spots.random()] = pane(Items.RED_STAINED_GLASS_PANE)
    }

    override fun click(slot: Int, button: Int) {
        if (slot !in spots) return
        slots[slot] = pane(if (slots[slot].`is`(Items.LIME_STAINED_GLASS_PANE)) Items.RED_STAINED_GLASS_PANE else Items.LIME_STAINED_GLASS_PANE)
        if (spots.none { slots[it].`is`(Items.RED_STAINED_GLASS_PANE) }) done = true
    }
}

/** Change All To Same Color: a 3x3 of five pane colors; left click moves one forward in the cycle, right click back. */
private class Rubix : Terminal("Change All To Same Color", 5, "Change all to same color!") {
    private val spots = listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)
    private val state = HashMap<Int, Int>()

    init {
        do { spots.forEach { state[it] = Random.nextInt(CYCLE.size) } } while (state.values.toSet().size == 1)
        refresh()
    }

    private fun refresh() = spots.forEach { slots[it] = pane(CYCLE[state[it]!!]) }

    override fun click(slot: Int, button: Int) {
        if (slot !in spots) return
        state[slot] = Math.floorMod(state[slot]!! + if (button == 1) -1 else 1, CYCLE.size)
        refresh()
        if (state.values.toSet().size == 1) done = true
    }
}

/**
 * Melody: 3 rounds, one row each. The target column is shown in magenta on the top and bottom rows; a lime pane bounces
 * across the active row's five columns, a step every 0.5 s; click that row's button (right edge) while the lime pane is on
 * the target column. A hit moves to the next row and picks a new target column.
 */
private class Melody : Terminal("Melody", 5, "Click the button on time!") {
    private var round = 0
    private var magenta = Random.nextInt(1, 6)
    private var lime = 1
    private var dir = 1
    private var steps = 0L

    init { draw() }

    private fun draw() {
        for (i in slots.indices) slots[i] = blank()
        for (c in 1..5) {
            val p = if (c == magenta) Items.MAGENTA_STAINED_GLASS_PANE else Items.BLACK_STAINED_GLASS_PANE
            slots[c] = pane(p); slots[4 * 9 + c] = pane(p)
        }
        for (r in 1..3) slots[r * 9 + 7] = pane(if (r == round + 1) Items.GREEN_TERRACOTTA else Items.RED_TERRACOTTA, if (r == round + 1) "Click me!" else " ")
        if (round < 3) for (c in 1..5) slots[(round + 1) * 9 + c] = pane(if (c == lime) Items.LIME_STAINED_GLASS_PANE else Items.RED_STAINED_GLASS_PANE)
    }

    override fun tick(ms: Long) {
        val target = ms / 500
        if (target == steps) return
        while (steps < target) {
            steps++
            lime += dir
            if (lime == 1 || lime == 5) dir = -dir
        }
        draw()
    }

    override fun click(slot: Int, button: Int) {
        if (round >= 3) return
        if (slot != (round + 1) * 9 + 7) { if (slot % 9 == 7) misses++; return }
        if (lime != magenta) { misses++; return }
        round++
        magenta = Random.nextInt(1, 6)
        lime = 1; dir = 1
        if (round >= 3) done = true
        draw()
    }
}

/** Implemented by the simulator screen so Terminal Solver's clicks land in the sim instead of being sent to a server. */
interface TerminalHost {
    fun hostClick(slot: Int, button: Int)
}

private fun makeTerminal(kind: Int): Terminal = when (kind) {
    0 -> ClickInOrder()
    1 -> StartsWith()
    2 -> SelectColors()
    3 -> CorrectPanes()
    4 -> Rubix()
    else -> Melody()
}

/**
 * A real chest screen over a client-only menu, with the title the real terminals use, so Terminal Solver recognises it and
 * draws its panel exactly as in Hypixel. With the solver off (or for Melody) the plain chest drawing below is used.
 */
private class TerminalSimScreen private constructor(
    private val kind: Int,
    private var term: Terminal,
    private val container: SimpleContainer,
    menu: ChestMenu,
    inventory: Inventory,
) : ContainerScreen(menu, inventory, Component.literal(term.header)), TerminalHost {
    private val startAt = System.nanoTime()
    private var finishedMs = 0L
    private var wasBest = false
    private val fnt get() = Minecraft.getInstance().font

    // A click waits here for the simulated ping; no other click is taken meanwhile.
    private var dueAt = 0L
    private var pendingSlot = -1
    private var pendingButton = 0

    private val left get() = (width - 176) / 2
    private val top get() = (height - (17 + term.rows * 18 + 7)) / 2

    private fun slotAt(mx: Double, my: Double): Int? {
        val c = Math.floorDiv((mx - left - 8).toInt(), 18)
        val r = Math.floorDiv((my - top - 18).toInt(), 18)
        if (mx < left + 8 || my < top + 18 || c !in 0..8 || r !in 0 until term.rows) return null
        return r * 9 + c
    }

    override fun hostClick(slot: Int, button: Int) {
        if (term.done || dueAt != 0L || slot !in 0 until term.rows * 9 || term.isBlankSlot(slot)) return
        val ping = TerminalSimulator.ping()
        if (ping <= 0) apply(slot, button)
        else { dueAt = System.currentTimeMillis() + ping; pendingSlot = slot; pendingButton = button }
    }

    private fun apply(slot: Int, button: Int) {
        term.click(slot, button)
        if (term.done) {
            finishedMs = (System.nanoTime() - startAt) / 1_000_000
            wasBest = TerminalSimulator.record(term.name, finishedMs)
            ced.cedclient.utils.Debug.line("Terminal Simulator", "${term.name}: ${finishedMs}ms misses=${term.misses}")
        }
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        if (dueAt != 0L && System.currentTimeMillis() >= dueAt) { dueAt = 0L; apply(pendingSlot, pendingButton) }
        if (!term.done) term.tick((System.nanoTime() - startAt) / 1_000_000)
        for (i in 0 until term.rows * 9) container.setItem(i, term.slots[i])
        if (TerminalSolver.handles(this)) super.extractRenderState(g, mouseX, mouseY, deltaTicks) else drawChest(g, mouseX, mouseY)

        val elapsed = if (term.done) finishedMs else (System.nanoTime() - startAt) / 1_000_000
        val time = "%.2fs".format(java.util.Locale.ROOT, elapsed / 1000.0)
        val best = TerminalSimulator.bestOf(term.name)?.let { "  best %.2fs".format(java.util.Locale.ROOT, it / 1000.0) } ?: ""
        g.text(fnt, "$time$best   misses ${term.misses}", 8, 8, 0xFFFFFFFF.toInt())
        if (term.done) {
            val msg = (if (wasBest) "New best! " else "Done! ") + time
            g.text(fnt, msg, width / 2 - fnt.width(msg) / 2, height - 36, 0xFF55FF55.toInt())
            val hint = "Click or R for another, Esc to leave"
            g.text(fnt, hint, width / 2 - fnt.width(hint) / 2, height - 24, 0xFFAAAAAA.toInt())
        }
    }

    private fun drawChest(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        g.fill(0, 0, width, height, 0x99000000.toInt())
        val x = left
        val y = top
        val h = 17 + term.rows * 18 + 7
        g.fill(x, y, x + 176, y + h, 0xFFC6C6C6.toInt())
        g.fill(x, y, x + 176, y + 1, 0xFFFFFFFF.toInt()); g.fill(x, y, x + 1, y + h, 0xFFFFFFFF.toInt())
        g.fill(x, y + h - 1, x + 176, y + h, 0xFF555555.toInt()); g.fill(x + 175, y, x + 176, y + h, 0xFF555555.toInt())
        g.text(fnt, term.header, x + 8, y + 6, 0xFF404040.toInt())
        val hover = slotAt(mouseX.toDouble(), mouseY.toDouble())
        for (slot in 0 until term.rows * 9) {
            val sx = x + 8 + slot % 9 * 18
            val sy = y + 18 + slot / 9 * 18
            g.fill(sx, sy, sx + 16, sy + 16, 0xFF8B8B8B.toInt())
            g.item(term.slots[slot], sx, sy)
            g.itemDecorations(fnt, term.slots[slot], sx, sy)
            if (slot == hover) g.fill(sx, sy, sx + 16, sy + 16, 0x80FFFFFF.toInt())
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (term.done) { open(kind); return true }
        // Solver on: its panel (via the mixin) works out the click and calls hostClick.
        if (TerminalSolver.handles(this)) return super.mouseClicked(event, doubleClick)
        val slot = slotAt(event.x(), event.y()) ?: return true
        hostClick(slot, event.button())
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key == GLFW.GLFW_KEY_R) { open(kind); return true }
        return super.keyPressed(event)
    }

    /** Vanilla slot logic (hotbar keys, drop key ...) would send a click for a menu the server does not know. */
    override fun slotClicked(slot: Slot, slotId: Int, buttonNum: Int, containerInput: ContainerInput) {}

    override fun isPauseScreen(): Boolean = false

    /** No vanilla blur behind the chest; the dim drawn by the chest / solver is the background. */
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {}

    companion object {
        private var lastRandom = 0
        private var ids = 0

        /** Opens terminal [kind] (-1 = random). Needs a world: the chest is a real container screen. */
        fun open(kind: Int) {
            val mc = Minecraft.getInstance()
            val player = mc.player ?: return
            if (kind < 0) lastRandom = Random.nextInt(6)
            val term = makeTerminal(if (kind < 0) lastRandom else kind)
            val container = SimpleContainer(term.rows * 9)
            val menu = ChestMenu(MenuType.GENERIC_9x6, 900 + ids++ % 50, player.inventory, container, term.rows)
            mc.gui.setScreen(TerminalSimScreen(kind, term, container, menu, player.inventory))
        }
    }
}
