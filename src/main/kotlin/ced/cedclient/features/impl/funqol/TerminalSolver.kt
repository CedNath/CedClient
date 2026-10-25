package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.HeaderSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Color
import ced.cedclient.utils.Debug
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import kotlin.math.abs

/**
 * Terminal Solver (Floor 7), after Athen's terminal solvers: swaps the terminal chest for a small panel with only what to
 * click. Six solvers: Numbers (Click in order), Panes, Colors (Select all the <color> items), Name (What starts with),
 * Rubix (Change all to same color) and Melody. Only the first number is clickable; clicks wait for the first-click delay
 * and for the server's answer (resync timeout); the Panes terminal is limited by the click delay instead.
 * Clicking a lit tile (or, in the Cheat build, dragging over them with left click held, or Auto Terminals) sends that click.
 */
object TerminalSolver : Module(
    "Terminal Solver",
    Category.Skyblock,
    "Shows only the slots to click in F7 terminals, in a clean panel."
) {
    // ---- general
    private val firstDelay = NumberSetting("First Click Delay", 350.0, 150.0, 1000.0, 10.0, "Milliseconds after a terminal opens before a click is accepted.")
    private val clickDelay = NumberSetting("Click Delay", 100.0, 0.0, 500.0, 10.0, "Milliseconds between clicks (Correct All The Panes only).")
    private val resync = NumberSetting("Resync Timeout", 800.0, 0.0, 2000.0, 50.0, "Forget clicks the server never answered after this long. 0: off.")
    private val allowDrop = BooleanSetting("Allow Drop Key", true, "The drop key clicks the tile under the mouse (left; right with Ctrl).")
    private val keyLeft = KeybindSetting("Left Click Key", GLFW.GLFW_KEY_UNKNOWN, "Left clicks the tile under the mouse.")
    private val keyRight = KeybindSetting("Right Click Key", GLFW.GLFW_KEY_UNKNOWN, "Right clicks the tile under the mouse.")
    private val onNumbers = BooleanSetting("Click In Order", true, "Solve this terminal.")
    private val onPanes = BooleanSetting("Correct All The Panes", true, "Solve this terminal.")
    private val onRubix = BooleanSetting("Change All To Same Color", true, "Solve this terminal.")
    private val onName = BooleanSetting("What Starts With", true, "Solve this terminal.")
    private val onColors = BooleanSetting("Select All The Color Items", true, "Solve this terminal.")
    private val onMelody = BooleanSetting("Click The Button On Time", true, "Solve this terminal.")
    private val clickSound = BooleanSetting("Click Sound", false, "A click sound when the solver sends a click.")

    // ---- rubix / melody
    private val rubixLeft = BooleanSetting("Rubix Left Click Only", true, "Only left clicks, any mouse button works. Off: the shorter way round, left or right.")
    private val melodyPrevent = BooleanSetting("Melody Prevent Wrong Clicks", false, "The button only works while the lime pane is on the magenta one.")
    private val melodyKeys = BooleanSetting("Melody Number Keys", false, "Keys click the button of lane 1-4.")
    private val melodyBinds = (1..4).map { KeybindSetting("Melody Key $it", GLFW.GLFW_KEY_0 + it, "Clicks the button of lane $it.").withDependency { melodyKeys.value } }

    // ---- gui
    private val scale = NumberSetting("Scale", 1.0, 0.1, 4.0, 0.1, "Size of the panel (also grows when your GUI scale is below 4).")
    private val roundness = NumberSetting("Roundness", 4.0, 0.0, 10.0, 1.0, "Corner radius of the panel.")
    private val borderWidth = NumberSetting("Border Width", 1.0, 0.5, 5.0, 0.5, "Outline width of the panel.")
    private val padding = NumberSetting("Padding", 5.0, 0.0, 20.0, 1.0, "Space between the panel edge and the tiles.")
    private val slotGap = NumberSetting("Slot Gap", 2.0, 0.0, 10.0, 1.0, "Space between tiles.")
    private val melodyGap = NumberSetting("Melody Gap", 2.0, 0.0, 10.0, 1.0, "Space between tiles in Melody.")
    private val screenDim = NumberSetting("Screen Dim", 60.0, 0.0, 100.0, 5.0, "How dark the screen behind the panel gets.")
    private val offsetX = NumberSetting("Offset X", 0.0, -400.0, 400.0, 5.0, "Move the panel sideways (screen pixels).")
    private val offsetY = NumberSetting("Offset Y", 0.0, -300.0, 300.0, 5.0, "Move the panel up / down (screen pixels).")
    private val bgColor = ColorSetting("Background", Color(0xAF0C0C0F.toInt()), "Panel background.")
    private val borderColor = ColorSetting("Border", Color(0xDC000000.toInt()), "Panel outline.")
    private val slotFill = BooleanSetting("Slot Fill", true, "Fill the lit tiles. Off: outline only.")
    private val slotRoundness = NumberSetting("Slot Roundness", 3.0, 0.0, 10.0, 1.0, "Corner radius of the tiles.")
    private val showText = BooleanSetting("Numbers Show Text", true, "The click number on the tiles in Click In Order.")
    private val hideHeader = BooleanSetting("Hide Header", true, "No title bar above the panel.")
    private val hideTitle = BooleanSetting("Hide Title", true, "No title text in the title bar.").withDependency { !hideHeader.value }
    private val titleColor = ColorSetting("Title Color", Color(0xE6F0F0F5.toInt()), "Title text.").withDependency { !hideHeader.value }
    private val headerColor = ColorSetting("Header Color", Color(0xC80A0A0C.toInt()), "Title bar background.").withDependency { !hideHeader.value }

    // ---- solver colors
    private val cSolution = ColorSetting("Solution", Color(0xDC2ECC71.toInt()), "Colors, Name and Panes.")
    private val cFirst = ColorSetting("Numbers 1st", Color(0xDC2ECC71.toInt()), "The number to click now.")
    private val cSecond = ColorSetting("Numbers 2nd", Color(0xDCF1C40F.toInt()), "The number after.")
    private val cThird = ColorSetting("Numbers 3rd", Color(0xDCE74C3C.toInt()), "The number after that.")
    private val cPositive = ColorSetting("Rubix Positive", Color(0xDC3498DB.toInt()), "Left clicks.")
    private val cNegative = ColorSetting("Rubix Negative", Color(0xDCE74C3C.toInt()), "Right clicks.")
    private val cMelodyFill = ColorSetting("Melody Fill", Color(0xDC9B59B6.toInt()), "The magenta target column.")
    private val cMelodyCorrect = ColorSetting("Melody Correct", Color(0xDC2ECC71.toInt()), "Lime pane on the target: click now.")
    private val cMelodyWrong = ColorSetting("Melody Wrong", Color(0xDCE74C3C.toInt()), "Lime pane off the target.")
    private val cMelodyOther = ColorSetting("Melody Other", Color(0xC818181C.toInt()), "Everything else.")
//#if CHEAT
    private val dragClick = BooleanSetting("Drag Click", false, "Hold left click and move over the lit tiles to click them one after another.")
    private val dragDelay = NumberSetting("Drag Click Delay", 150.0, 60.0, 400.0, 10.0, "Milliseconds between drag clicks.")
    private val autoClick = BooleanSetting("Auto Terminals", false, "Solves the terminal for you: clicks the next tile by itself.")
    private val autoDelay = NumberSetting("Auto Click Delay", 150.0, 50.0, 500.0, 10.0, "Milliseconds between automatic clicks.").withDependency { autoClick.value }
//#endif

    private enum class Kind(val title: String) {
        NUMBERS("Numbers"), PANES("Panes"), COLORS("Colors"), NAME("Name"), RUBIX("Rubix"), MELODY("Melody")
    }

    /** [slot] to click with [button]; [color] ARGB; [label] drawn on the tile; [clickable] false for tiles that only show. */
    private class Tile(val slot: Int, val button: Int, val color: Int, val label: String = "", val clickable: Boolean = true)

    private val STARTS = Regex("""^What starts with: '(.+)'\?$""")
    private val SELECT = Regex("""^Select all the (.+) items!$""")
    private val CODES = Regex("§.")

    // Change all to same color: left click moves a pane one step along this order, right click one step back.
    private val CYCLE = listOf(Items.RED_STAINED_GLASS_PANE, Items.ORANGE_STAINED_GLASS_PANE, Items.YELLOW_STAINED_GLASS_PANE, Items.GREEN_STAINED_GLASS_PANE, Items.BLUE_STAINED_GLASS_PANE)
    private val RUBIX_SLOTS = listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)
    private val NAME_FIX = listOf("light gray" to "silver", "wool" to "white", "bone" to "white", "ink" to "black", "lapis" to "blue", "cocoa" to "brown", "dandelion" to "yellow", "rose" to "red", "cactus" to "green")

    private val mc get() = Minecraft.getInstance()

    // State of the terminal that is open.
    private var openId = -1
    private var openAt = 0L
    private var pending = false
    private var lastClick = 0L
    private var sig = 0
    private var sigAtClick = 0
    private val clicked = HashMap<Int, Int>() // slot -> stack hash when clicked: hidden until that slot changes
    private var rubixTarget = -1
    private var lastLog = ""

    init {
        val list = mutableListOf<ced.cedclient.features.settings.Setting<*>>(
            HeaderSetting("Terminals"), firstDelay, clickDelay, resync, allowDrop, keyLeft, keyRight, clickSound,
            onNumbers, onPanes, onRubix, onName, onColors, onMelody,
            HeaderSetting("Rubix / Melody"), rubixLeft, melodyPrevent, melodyKeys, *melodyBinds.toTypedArray(),
            HeaderSetting("Panel"), scale, roundness, borderWidth, padding, slotGap, melodyGap, screenDim, offsetX, offsetY, bgColor, borderColor,
            slotFill, slotRoundness, showText, hideHeader, hideTitle, titleColor, headerColor,
            HeaderSetting("Colors"), cSolution, cFirst, cSecond, cThird, cPositive, cNegative, cMelodyFill, cMelodyCorrect, cMelodyWrong, cMelodyOther,
        )
//#if CHEAT
        list += listOf(dragClick, dragDelay, autoClick, autoDelay)
//#endif
        addSettings(*list.toTypedArray())
    }

    private fun kind(screen: AbstractContainerScreen<*>): Kind? {
        val t = screen.title.string.replace(CODES, "").trim()
        return when {
            t == "Click in order!" -> Kind.NUMBERS.takeIf { onNumbers.value }
            t == "Correct all the panes!" -> Kind.PANES.takeIf { onPanes.value }
            t == "Change all to same color!" -> Kind.RUBIX.takeIf { onRubix.value }
            t == "Click the button on time!" -> Kind.MELODY.takeIf { onMelody.value }
            STARTS.matches(t) -> Kind.NAME.takeIf { onName.value }
            SELECT.matches(t) -> Kind.COLORS.takeIf { onColors.value }
            else -> null
        }
    }

    fun handles(screen: AbstractContainerScreen<*>): Boolean = isEnabled && screen is ContainerScreen && kind(screen) != null

    private fun containerSlots(screen: AbstractContainerScreen<*>) = screen.menu.slots.size - 36

    private fun filler(stack: ItemStack) = stack.isEmpty || stack.`is`(Items.BLACK_STAINED_GLASS_PANE)

    /** A terminal marks a picked item with a glint; items that always glint (enchanted books ...) don't count. */
    private fun picked(stack: ItemStack) = stack.hasFoil() && !stack.item.defaultInstance.hasFoil()

    private fun nameOf(stack: ItemStack) = stack.hoverName.string.replace(CODES, "").trim()

    private fun fix(s: String): String = NAME_FIX.fold(s.lowercase()) { acc, (from, to) -> acc.replace(from, to) }

    private fun stackHash(s: ItemStack) = s.item.hashCode() * 31 + s.count + if (s.hasFoil()) 1000 else 0

    /** New container -> reset; a changed container answers the pending click; an unanswered click is dropped after the resync timeout. */
    private fun sync(screen: AbstractContainerScreen<*>) {
        val now = System.currentTimeMillis()
        val id = screen.menu.containerId
        if (id != openId) {
            openId = id; openAt = now; pending = false; clicked.clear(); rubixTarget = -1
        }
        var h = 0
        for (i in 0 until containerSlots(screen)) h = h * 31 + stackHash(screen.menu.slots[i].item)
        sig = h
        if (pending && sig != sigAtClick) pending = false
        if (resync.value > 0.0 && pending && now - lastClick > resync.value) {
            pending = false; clicked.clear()
            Debug.line("TerminalSolver", "resync")
        }
    }

    private fun solve(screen: AbstractContainerScreen<*>, kind: Kind): List<Tile> {
        val slots = screen.menu.slots
        val n = containerSlots(screen)
        val title = screen.title.string.replace(CODES, "").trim()
        val out = ArrayList<Tile>()
        when (kind) {
            Kind.NUMBERS -> {
                val red = (0 until n).filter { slots[it].item.`is`(Items.RED_STAINED_GLASS_PANE) && !hidden(it, slots[it].item) }.sortedBy { slots[it].item.count }
                val colors = listOf(cFirst, cSecond, cThird)
                red.take(3).forEachIndexed { i, s ->
                    out += Tile(s, 0, colors[i].value.rgba, if (showText.value) "${slots[s].item.count}" else "", i == 0)
                }
            }
            Kind.PANES -> (0 until n).filter { slots[it].item.`is`(Items.RED_STAINED_GLASS_PANE) && !hidden(it, slots[it].item) }.forEach { out += Tile(it, 0, cSolution.value.rgba) }
            Kind.COLORS -> {
                val color = fix(SELECT.find(title)!!.groupValues[1])
                (0 until n).filter { s -> val st = slots[s].item; !filler(st) && !picked(st) && !hidden(s, st) && fix(nameOf(st)).startsWith(color) }
                    .forEach { out += Tile(it, 0, cSolution.value.rgba) }
            }
            Kind.NAME -> {
                val letter = STARTS.find(title)!!.groupValues[1].lowercase()
                (0 until n).filter { s -> val st = slots[s].item; !filler(st) && !picked(st) && !hidden(s, st) && nameOf(st).lowercase().startsWith(letter) }
                    .forEach { out += Tile(it, 0, cSolution.value.rgba) }
            }
            Kind.RUBIX -> {
                val panes = RUBIX_SLOTS.filter { it < n }.mapNotNull { s -> CYCLE.indexOf(slots[s].item.item).takeIf { it >= 0 }?.let { s to it } }
                if (panes.isEmpty()) return out
                // Steps from one colour to another: positive = left clicks, negative = right clicks (the short way round).
                fun dist(from: Int, to: Int): Int { val d = (to - from + 5) % 5; return if (!rubixLeft.value && d > 2) d - 5 else d }
                fun need(t: Int) = panes.sumOf { abs(dist(it.second, t)) }
                // Keep the target while it still needs clicks, so the solution doesn't flip between equally cheap ones.
                if (rubixTarget < 0 || need(rubixTarget) == 0) rubixTarget = CYCLE.indices.minBy { need(it) }
                for ((s, c) in panes) {
                    val d = dist(c, rubixTarget)
                    if (d == 0 || hidden(s, slots[s].item)) continue
                    out += Tile(s, if (d > 0) 0 else 1, (if (d > 0) cPositive else cNegative).value.rgba, "${abs(d)}")
                }
            }
            Kind.MELODY -> {
                val rows = n / 9
                var correct = -1
                for (c in 1..5) for (r in intArrayOf(0, rows - 1)) if (slots[r * 9 + c].item.`is`(Items.MAGENTA_STAINED_GLASS_PANE)) correct = c
                var row = -1; var current = -1
                for (r in 1..rows - 2) for (c in 1..5) if (slots[r * 9 + c].item.`is`(Items.LIME_STAINED_GLASS_PANE)) { row = r; current = c }
                if (row < 0 || correct < 0) return out
                val aligned = current == correct
                for (c in 1..5) {
                    val color = if (c == current) (if (aligned) cMelodyCorrect else cMelodyWrong) else if (c == correct) cMelodyFill else cMelodyOther
                    out += Tile(row * 9 + c, 0, color.value.rgba, clickable = false)
                }
                for (r in 1..rows - 2) {
                    if (r != row) out += Tile(r * 9 + 7, 0, cMelodyOther.value.rgba, clickable = false)
                    else out += Tile(r * 9 + 7, 0, (if (aligned) cMelodyCorrect else cMelodyOther).value.rgba, clickable = aligned || !melodyPrevent.value)
                }
            }
        }
        return out
    }

    /** Clicked and not answered yet: the slot still holds the stack it had when we clicked. */
    private fun hidden(slot: Int, stack: ItemStack) = clicked[slot] == stackHash(stack)

    /** Where the panel and the tile grid sit this frame, in scaled GUI units. */
    private class Geo(val colStart: Int, val cols: Int, val rowStart: Int, val rows: Int, val pitch: Float, val gx: Float, val gy: Float,
                      val hx: Float, val hy: Float, val mx: Float, val my: Float, val mw: Float, val mh: Float, val sc: Float) {
        fun cellX(col: Int) = gx + (col - colStart) * pitch
        fun cellY(row: Int) = gy + (row - rowStart) * pitch
    }

    private fun geo(screen: AbstractContainerScreen<*>, kind: Kind): Geo {
        val rows = containerSlots(screen) / 9
        var colStart = 1; var cols = 7; val rowStart = 1; var rowCount = rows - 2
        when (kind) {
            Kind.PANES -> { colStart = 2; cols = 5 }
            Kind.RUBIX -> { colStart = 3; cols = 3; rowCount = 3 }
            else -> {}
        }
        val sc = (scale.value * maxOf(1.0, 4.0 / mc.window.guiScale)).toFloat()
        val gap = (if (kind == Kind.MELODY) melodyGap.value else slotGap.value).toFloat()
        val pitch = 16f + gap
        val pad = padding.value.toFloat()
        val mw = cols * pitch - gap + pad * 2
        val mh = rowCount * pitch - gap + pad * 2
        val headBlock = if (hideHeader.value) 0f else 26f
        val top = (screen.height / sc - (headBlock + mh)) / 2f + offsetY.value.toFloat() / sc
        val left = (screen.width / sc - mw) / 2f + offsetX.value.toFloat() / sc
        return Geo(colStart, cols, rowStart, rowCount, pitch, left + pad, top + headBlock + pad, left, top, left, top + headBlock, mw, mh, sc)
    }

    private fun slotAt(g: Geo, mx: Double, my: Double): Int? {
        val x = mx / g.sc; val y = my / g.sc
        for (r in 0 until g.rows) for (c in 0 until g.cols) {
            val cx = g.cellX(g.colStart + c); val cy = g.cellY(g.rowStart + r)
            if (x >= cx && x < cx + 16f && y >= cy && y < cy + 16f) return (g.rowStart + r) * 9 + g.colStart + c
        }
        return null
    }

    private fun mouse() = mc.mouseHandler.xpos() * mc.window.guiScaledWidth / mc.window.screenWidth to
        mc.mouseHandler.ypos() * mc.window.guiScaledHeight / mc.window.screenHeight

    fun render(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val kind = kind(screen) ?: return
        sync(screen)
        val tiles = solve(screen, kind)
        val logSig = "${kind.title}/${tiles.count { it.clickable }}"
        if (logSig != lastLog) { lastLog = logSig; Debug.line("TerminalSolver", "${kind.title} -> ${tiles.count { it.clickable }} to click") }
        val g = geo(screen, kind)
//#if CHEAT
        val now = System.currentTimeMillis()
        if (dragClick.value && GLFW.glfwGetMouseButton(mc.window.handle(), 0) == GLFW.GLFW_PRESS && now - lastClick >= dragDelay.value) {
            slotAt(g, mouseX.toDouble(), mouseY.toDouble())?.let { tryClick(screen, kind, it, 0) }
        }
        if (autoClick.value && now - lastClick >= autoDelay.value) {
            tiles.firstOrNull { it.clickable }?.let { tryClick(screen, kind, it.slot, it.button) }
        }
//#endif
        val round = roundness.value.toFloat()
        val slotRound = slotRoundness.value.toFloat()
        HudCard.draw(graphics, 0, 0) {
            if (screenDim.value > 0.0) NVGRenderer.rect(0f, 0f, screen.width.toFloat(), screen.height.toFloat(), ((screenDim.value / 100.0 * 255).toInt() shl 24))
            NVGRenderer.scale(g.sc, g.sc)
            if (!hideHeader.value) {
                NVGRenderer.rect(g.hx, g.hy, g.mw, 20f, headerColor.value.rgba, round)
                NVGRenderer.hollowRect(g.hx, g.hy, g.mw, 20f, borderWidth.value.toFloat(), borderColor.value.rgba, round)
                if (!hideTitle.value) HudCard.text(kind.title, g.hx + (g.mw - HudCard.width(kind.title, 12f)) / 2f, g.hy + 4f, 12f, titleColor.value.rgba)
            }
            NVGRenderer.rect(g.mx, g.my, g.mw, g.mh, bgColor.value.rgba, round)
            NVGRenderer.hollowRect(g.mx, g.my, g.mw, g.mh, borderWidth.value.toFloat(), borderColor.value.rgba, round)
            for (t in tiles) {
                val x = g.cellX(t.slot % 9); val y = g.cellY(t.slot / 9)
                if (slotFill.value) NVGRenderer.rect(x, y, 16f, 16f, t.color, slotRound)
                else NVGRenderer.hollowRect(x, y, 16f, 16f, 1f, t.color, slotRound)
                if (t.label.isNotEmpty()) HudCard.text(t.label, x + (16f - HudCard.width(t.label, 11f)) / 2f, y + (16f - 11f) / 2f, 11f, 0xFFFFFFFF.toInt())
            }
        }
    }

    fun mouseClicked(screen: AbstractContainerScreen<*>, mx: Double, my: Double, button: Int) {
        val kind = kind(screen) ?: return
        sync(screen)
        slotAt(geo(screen, kind), mx, my)?.let { tryClick(screen, kind, it, button) }
    }

    /** Key presses: true when the solver used the key (the screen must not see it). */
    fun keyPressed(screen: AbstractContainerScreen<*>, event: KeyEvent): Boolean {
        val kind = kind(screen) ?: return false
        sync(screen)
        val key = event.key
        if (kind == Kind.MELODY && melodyKeys.value) {
            val lane = melodyBinds.indexOfFirst { it.value == key && key != GLFW.GLFW_KEY_UNKNOWN }
            if (lane >= 0) { tryClick(screen, kind, (lane + 1) * 9 + 7, 0); return true }
        }
        val (mx, my) = mouse()
        val hover = slotAt(geo(screen, kind), mx, my)
        val button = when {
            keyLeft.value != GLFW.GLFW_KEY_UNKNOWN && key == keyLeft.value -> 0
            keyRight.value != GLFW.GLFW_KEY_UNKNOWN && key == keyRight.value -> 1
            allowDrop.value && mc.options.keyDrop.matches(event) -> if (event.hasControlDown()) 1 else 0
            else -> return false
        }
        if (hover != null) tryClick(screen, kind, hover, button)
        return true
    }

    private fun tryClick(screen: AbstractContainerScreen<*>, kind: Kind, slot: Int, button: Int) {
        val now = System.currentTimeMillis()
        if (now - openAt < firstDelay.value) return
        val tile = solve(screen, kind).firstOrNull { it.slot == slot && it.clickable } ?: return
        val anyButton = (kind == Kind.RUBIX && rubixLeft.value) || kind == Kind.MELODY
        if (!anyButton && button != tile.button) return
        if (if (kind == Kind.PANES) now - lastClick < clickDelay.value else pending) return
        lastClick = now
        pending = true
        sigAtClick = sig
        clicked[slot] = stackHash(screen.menu.slots[slot].item)
        if (clickSound.value) mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
        Debug.line("TerminalSolver", "click slot $slot button ${tile.button}")
        // The Terminal Simulator is not a server menu: its click goes to the simulator.
        (screen as? TerminalHost)?.let { it.hostClick(slot, tile.button); return }
        val player = mc.player ?: return
        // Like Athen: a left click goes out as a middle click (clone), the terminal reads both the same.
        if (tile.button == 0) mc.gameMode?.handleContainerInput(screen.menu.containerId, slot, 2, ContainerInput.CLONE, player)
        else mc.gameMode?.handleContainerInput(screen.menu.containerId, slot, tile.button, ContainerInput.PICKUP, player)
    }
}
