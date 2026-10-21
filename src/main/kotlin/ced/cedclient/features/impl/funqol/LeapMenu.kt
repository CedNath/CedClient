package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.state.DungeonState.DungeonClass
import ced.cedclient.utils.TabListCache
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items

/**
 * Leap Menu: the Spirit Leap / Infinileap chest becomes four big teammate boxes (click, or press 1-4).
 *
 * Speed: the boxes come from the tab list, so they are there the frame the menu opens, before the chest's items
 * arrive. A pick made that early is kept and clicked the first frame the teammate's head shows up. The click goes
 * straight out as a middle click on the head's slot (no item lands on the cursor), at most once per 400 ms.
 * Teammates are always in the same order (by class, then name), so a box's spot stays put run to run.
 */
object LeapMenu : Module(
    "Leap Menu",
    Category.Skyblock,
    "Replaces the Spirit Leap menu with big teammate boxes. Click one or press 1-4."
) {
    private val numberKeys = BooleanSetting("Number Keys", true, "1-4 leap to the top-left, top-right, bottom-left, bottom-right box.")

    private val TITLES = setOf("Spirit Leap", "Teleport to Player")
    private val CODES = Regex("§.")
    /** Dungeon tab row: "[312] Name ♲ (Mage XXV)" -> name, class ("DEAD" / "EMPTY" when not playing). */
    private val TAB_ROW = Regex("""^\[\d+] (\w{1,16})\b.*\((\w+)(?: [IVXLCDM]+)?\)$""")
    private val ORDER = listOf(DungeonClass.ARCHER, DungeonClass.BERSERK, DungeonClass.HEALER, DungeonClass.MAGE, DungeonClass.TANK)

    private const val BOX_W = 170f
    private const val BOX_H = 56f
    private const val GAP = 8f

    private class Mate(val name: String, val cls: DungeonClass?, val dead: Boolean, var slot: Int = -1)

    private val mc get() = Minecraft.getInstance()

    /** A pick made before the heads arrived: the teammate's name, for this container id. */
    private var pending: String? = null
    private var pendingContainer = -1
    private var lastClick = 0L

    init {
        addSettings(numberKeys)
    }

    fun handles(screen: AbstractContainerScreen<*>): Boolean =
        isEnabled && screen is ContainerScreen && screen.title.string in TITLES

    private fun classOf(word: String) = DungeonClass.entries.firstOrNull { it.name.equals(word, ignoreCase = true) }

    /** Up to four teammates in box order, with the slot of their head once the chest has items. */
    private fun mates(screen: AbstractContainerScreen<*>): List<Mate> {
        val self = mc.player?.gameProfile?.name
        val mates = TabListCache.entries.mapNotNull { e ->
            val m = TAB_ROW.find(e.stripped.trim()) ?: return@mapNotNull null
            val name = m.groupValues[1]
            if (name.equals(self, ignoreCase = true)) return@mapNotNull null
            val word = m.groupValues[2]
            Mate(name, classOf(word), word.equals("DEAD", ignoreCase = true))
        }.distinctBy { it.name.lowercase() }
            .sortedWith(compareBy<Mate>({ ORDER.indexOf(it.cls).let { i -> if (i < 0) 99 else i } }, { it.name.lowercase() }))
            .toMutableList()

        val menu = screen.menu
        for (i in 0 until menu.slots.size - 36) {
            val stack = menu.slots[i].item
            if (!stack.`is`(Items.PLAYER_HEAD)) continue
            // Head name is the (coloured) player name; the last word survives any prefix.
            val name = stack.hoverName.string.replace(CODES, "").trim().substringAfterLast(' ')
            val mate = mates.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: Mate(name, null, false).also { mates += it }
            mate.slot = i
        }
        return mates.take(4)
    }

    private fun loaded(screen: AbstractContainerScreen<*>) =
        (0 until screen.menu.slots.size - 36).any { !screen.menu.slots[it].item.isEmpty }

    /** Box [index]'s top-left, in GUI units: a 2x2 grid around the screen centre. */
    private fun boxAt(index: Int, screenW: Int, screenH: Int): Pair<Float, Float> {
        val x = screenW / 2f + if (index % 2 == 0) -BOX_W - GAP / 2 else GAP / 2
        val y = screenH / 2f + if (index < 2) -BOX_H - GAP / 2 else GAP / 2
        return x to y
    }

    private fun indexAt(screen: AbstractContainerScreen<*>, mx: Double, my: Double): Int = (0 until 4).firstOrNull { i ->
        val (x, y) = boxAt(i, screen.width, screen.height)
        mx >= x && mx < x + BOX_W && my >= y && my < y + BOX_H
    } ?: -1

    fun render(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val mates = mates(screen)
        val id = screen.menu.containerId
        // An early pick goes out the frame its head shows up.
        pending?.let { name ->
            if (pendingContainer != id) pending = null
            else mates.firstOrNull { it.name == name && it.slot >= 0 }?.let { leap(screen, it) }
        }
        val loaded = loaded(screen)
        val hovered = indexAt(screen, mouseX.toDouble(), mouseY.toDouble())
        HudCard.draw(g, 0, 0) {
            NVGRenderer.rect(0f, 0f, screen.width.toFloat(), screen.height.toFloat(), 0x88000000.toInt())
            if (mates.isEmpty()) {
                val text = if (loaded) "No one to leap to" else "Loading..."
                HudCard.text(text, (screen.width - HudCard.width(text, 12f)) / 2, screen.height / 2f - 6f, 12f, 0xFFBBBBBB.toInt())
                return@draw
            }
            mates.forEachIndexed { i, mate ->
                val (x, y) = boxAt(i, screen.width, screen.height)
                // Once the heads are in, a teammate without one can't be leaped to.
                val usable = !mate.dead && (!loaded || mate.slot >= 0)
                val accent = classColor(mate.cls)
                NVGRenderer.rect(x, y, BOX_W, BOX_H, if (i == hovered && usable) 0xF0303040.toInt() else HudCard.BACKGROUND, 8f)
                NVGRenderer.hollowRect(x, y, BOX_W, BOX_H, if (pending == mate.name) 2f else 1f, if (usable) accent else 0xFF555555.toInt(), 8f)
                NVGRenderer.rect(x + 8f, y + 10f, 4f, BOX_H - 20f, if (usable) accent else 0xFF555555.toInt(), 2f)
                HudCard.text(mate.name, x + 20f, y + 12f, 14f, if (usable) -1 else 0xFF777777.toInt())
                val sub = when {
                    mate.dead -> "Dead"
                    loaded && mate.slot < 0 -> "Not in menu"
                    else -> mate.cls?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""
                }
                HudCard.text(sub, x + 20f, y + 32f, 10f, if (usable) accent else 0xFF777777.toInt())
                if (numberKeys.value) HudCard.text("${i + 1}", x + BOX_W - 16f, y + 8f, 10f, 0xFF888888.toInt())
            }
        }
    }

    private fun classColor(cls: DungeonClass?): Int = when (cls) {
        DungeonClass.ARCHER -> 0xFFFFAA00.toInt()
        DungeonClass.BERSERK -> 0xFFFF5555.toInt()
        DungeonClass.HEALER -> 0xFFFF55FF.toInt()
        DungeonClass.MAGE -> 0xFF55FFFF.toInt()
        DungeonClass.TANK -> 0xFF55FF55.toInt()
        null -> 0xFFBBBBBB.toInt()
    }

    fun mouseClicked(screen: AbstractContainerScreen<*>, mx: Double, my: Double) = pick(screen, indexAt(screen, mx, my))

    /** 1-4 (number row or numpad) leap; anything else goes on to the screen (Escape closes). */
    fun keyPressed(screen: AbstractContainerScreen<*>, key: Int): Boolean {
        if (!numberKeys.value) return false
        val index = when (key) {
            in 49..52 -> key - 49 // GLFW_KEY_1..4
            in 321..324 -> key - 321 // GLFW_KEY_KP_1..4
            else -> return false
        }
        pick(screen, index)
        return true
    }

    private fun pick(screen: AbstractContainerScreen<*>, index: Int) {
        val mate = mates(screen).getOrNull(index) ?: return
        if (mate.dead) return
        if (mate.slot >= 0) leap(screen, mate)
        else if (!loaded(screen)) { pending = mate.name; pendingContainer = screen.menu.containerId }
    }

    private fun leap(screen: AbstractContainerScreen<*>, mate: Mate) {
        pending = null
        val now = System.currentTimeMillis()
        if (now - lastClick < 400) return
        lastClick = now
        val player = mc.player ?: return
        mc.gameMode?.handleContainerInput(screen.menu.containerId, mate.slot, 2, ContainerInput.CLONE, player)
    }
}
