package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.RenderableSetting
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.state.DungeonState.DungeonClass
import ced.cedclient.state.DungeonTeam
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.SettingsPopup
//#if CHEAT
import ced.cedclient.data.ClickType
import ced.cedclient.events.ItemClickEvent
import ced.cedclient.events.core.on
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.skyblockId
import net.minecraft.world.InteractionHand
//#endif
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items

/**
 * Leap Menu: the Spirit Leap / Infinileap chest becomes four big teammate boxes (click, or press 1-4).
 *
 * Speed: the boxes come from the tab list, so they are there the frame the menu opens, before the chest's items
 * arrive. A pick made that early is kept and clicked the first frame the teammate's head shows up. The click goes
 * straight out as a middle click on the head's slot (no item lands on the cursor), at most once per 400 ms.
 * Teammates are always in the same order (by class, then name), so a box's spot stays put run to run. Each box owns
 * its whole quarter of the screen, so a click that misses the box still leaps.
 * Whoever opened the last wither door gets a gold box, until blood opens or the world changes. Leap Announce says
 * who you leap to in party chat. All options sit in the "Leap Options" popup.
//#if CHEAT
 *
 * Quick Leap: left-click with the leap item opens the menu (a right-click) and picks the door opener through the
 * same early-pick path, after the Quick Leap Speed delay (Slow 350, Normal 150, Fast 50 ms).
//#endif
 */
object LeapMenu : Module(
    "Leap Menu",
    Category.Skyblock,
    "Replaces the Spirit Leap menu with big teammate boxes. Click one or press 1-4."
) {
    private val numberKeys = BooleanSetting("Number Keys", true, "1-4 leap to the top-left, top-right, bottom-left, bottom-right box.")
    private val highlightOpener = BooleanSetting("Highlight Door Opener", true, "Gold box on whoever opened the last wither door (cleared once blood opens).")
    private val menuScale = NumberSetting("Menu Size", 1.0, 0.5, 2.0, 0.05, "Size of the leap boxes (the click areas stay the screen quarters).")
    private val announce = BooleanSetting("Leap Announce", false, "Says \"Leaping to <name>\" in party chat when you leap.")
//#if CHEAT
    private val quickLeap = BooleanSetting("Quick Leap", false, "Left-click with Spirit Leap / Infinileap leaps straight to whoever opened the last wither door.")
    private val quickSpeed = DropdownSetting("Quick Leap Speed", listOf("Slow", "Normal", "Fast"), "Normal", "How long the menu stays open before Quick Leap clicks: Slow 350 ms, Normal 150 ms, Fast 50 ms.")
    private val LEAP_ITEMS = setOf("SPIRIT_LEAP", "INFINITE_SPIRIT_LEAP")
    /** Quick Leap target, waiting for the menu it opened (until [quickUntil]). */
    private var quickTarget: String? = null
    private var quickUntil = 0L
//#endif

    private val TITLES = setOf("Spirit Leap", "Teleport to Player")
    private val CODES = Regex("§.")
    private val WITHER_OPENED = Regex("""^(?:\[.+?] )?(\w+) opened a WITHER door!$""")
    private const val BLOOD_OPENED = "The BLOOD DOOR has been opened!"
    private const val OPENER_COLOR = 0xFFFFD700.toInt()
    private val ORDER = listOf(DungeonClass.ARCHER, DungeonClass.BERSERK, DungeonClass.HEALER, DungeonClass.MAGE, DungeonClass.TANK)

    private const val BOX_W = 170f
    private const val BOX_H = 56f
    private const val GAP = 8f

    private class Mate(val name: String, val cls: DungeonClass?, val dead: Boolean, var slot: Int = -1)

    private val mc get() = Minecraft.getInstance()

    /** A pick made before the heads arrived: the teammate's name, for this container id. */
    private var pending: String? = null
    private var pendingContainer = -1
    /** The early pick isn't sent before this (Quick Leap Speed). */
    private var pendingNotBefore = 0L
    private var lastClick = 0L
    private var doorOpener: String? = null
    private var openerLevel: ClientLevel? = null

    init {
//#if CHEAT
        addPopupSettings("Menu" to listOf(menuScale, numberKeys, highlightOpener), "Leaping" to listOf(announce, quickLeap, quickSpeed))
        on<ItemClickEvent> { event ->
            if (event.clickType != ClickType.LEFT_CLICK || !isEnabled || !quickLeap.value || !DungeonState.inDungeon) return@on
            if (event.itemInHand?.skyblockId !in LEAP_ITEMS) return@on
            val target = doorOpener.takeIf { openerLevel === mc.level }
            if (target == null) { mc.player?.sendSystemMessage(ChatPrefix.msg("§cNo wither door opened yet")); return@on }
            val player = mc.player ?: return@on
            quickTarget = target
            quickUntil = System.currentTimeMillis() + 2000
            mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
        }
//#else
//$        addPopupSettings("Menu" to listOf(menuScale, numberKeys, highlightOpener), "Leaping" to listOf(announce))
//#endif
        ClientReceiveMessageEvents.GAME.register { message, _ ->
            val plain = message.string.replace(CODES, "").trim()
            WITHER_OPENED.find(plain)?.let { doorOpener = it.groupValues[1]; openerLevel = mc.level }
            if (plain == BLOOD_OPENED) doorOpener = null
        }
    }

    /** One "Leap Options" button in the panel; [sections] open in a popup from it. */
    private fun addPopupSettings(vararg sections: Pair<String, List<RenderableSetting<*>>>) {
        addSettings(ActionSetting("Leap Options", "Number keys, door opener highlight, leap announce.") {
            (mc.screen as? ClickGUI)?.openPopup(SettingsPopup("Leap Menu", sections.map { (title, list) -> listOf(SettingsPopup.Section(title, list)) }))
        })
        val all = sections.flatMap { it.second }
        addSettings(*all.toTypedArray())
        all.forEach { it.inPopup = true }
    }

    /** Last wither door opener in this world, if the highlight is on. */
    private fun opener(): String? = doorOpener.takeIf { highlightOpener.value && openerLevel === mc.level }

    fun handles(screen: AbstractContainerScreen<*>): Boolean =
        isEnabled && screen is ContainerScreen && screen.title.string in TITLES

    /** Up to four teammates in box order, with the slot of their head once the chest has items. */
    private fun mates(screen: AbstractContainerScreen<*>): List<Mate> {
        val mates = DungeonTeam.members().map { Mate(it.name, it.cls, it.dead) }
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

    /** The box whose quarter of the screen ([mx], [my]) is in: left/right of the centre, then top/bottom. */
    private fun indexAt(screen: AbstractContainerScreen<*>, mx: Double, my: Double): Int =
        (if (mx < screen.width / 2.0) 0 else 1) + (if (my < screen.height / 2.0) 0 else 2)

    fun render(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val mates = mates(screen)
        val id = screen.menu.containerId
//#if CHEAT
        // The menu Quick Leap opened: its target becomes an early pick.
        quickTarget?.let { target ->
            quickTarget = null
            if (System.currentTimeMillis() < quickUntil) {
                pending = target
                pendingContainer = id
                pendingNotBefore = System.currentTimeMillis() + when (quickSpeed.value) { "Slow" -> 350L; "Normal" -> 150L; else -> 50L }
            }
        }
//#endif
        // An early pick goes out the frame its head shows up.
        pending?.let { name ->
            if (pendingContainer != id) pending = null
            else if (System.currentTimeMillis() >= pendingNotBefore)
                mates.firstOrNull { it.name.equals(name, ignoreCase = true) && it.slot >= 0 }?.let { leap(screen, it) }
        }
        val loaded = loaded(screen)
        val hovered = indexAt(screen, mouseX.toDouble(), mouseY.toDouble())
        val opener = opener()
        HudCard.draw(g, 0, 0) {
            NVGRenderer.rect(0f, 0f, screen.width.toFloat(), screen.height.toFloat(), 0x88000000.toInt())
            // Menu Size: scale the boxes around the screen centre.
            val sc = menuScale.value.toFloat()
            NVGRenderer.translate(screen.width / 2f, screen.height / 2f)
            NVGRenderer.scale(sc, sc)
            NVGRenderer.translate(-screen.width / 2f, -screen.height / 2f)
            if (mates.isEmpty()) {
                val text = if (loaded) "No one to leap to" else "Loading..."
                HudCard.text(text, (screen.width - HudCard.width(text, 12f)) / 2, screen.height / 2f - 6f, 12f, 0xFFBBBBBB.toInt())
                return@draw
            }
            mates.forEachIndexed { i, mate ->
                val (x, y) = boxAt(i, screen.width, screen.height)
                // Once the heads are in, a teammate without one can't be leaped to.
                val usable = !mate.dead && (!loaded || mate.slot >= 0)
                val accent = DungeonTeam.classColor(mate.cls)
                val isOpener = mate.name.equals(opener, ignoreCase = true)
                NVGRenderer.rect(x, y, BOX_W, BOX_H, if (i == hovered && usable) 0xF0303040.toInt() else HudCard.BACKGROUND, 8f)
                val border = if (!usable) 0xFF555555.toInt() else if (isOpener) OPENER_COLOR else accent
                NVGRenderer.hollowRect(x, y, BOX_W, BOX_H, if (pending == mate.name || isOpener) 2f else 1f, border, 8f)
                NVGRenderer.rect(x + 8f, y + 10f, 4f, BOX_H - 20f, if (usable) accent else 0xFF555555.toInt(), 2f)
                HudCard.text(mate.name, x + 20f, y + 12f, 14f, if (usable) -1 else 0xFF777777.toInt())
                val sub = when {
                    mate.dead -> "Dead"
                    loaded && mate.slot < 0 -> "Not in menu"
                    else -> mate.cls?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""
                }
                HudCard.text(sub, x + 20f, y + 32f, 10f, if (usable) accent else 0xFF777777.toInt())
                if (numberKeys.value) HudCard.text("${i + 1}", x + BOX_W - 16f, y + 8f, 10f, 0xFF888888.toInt())
                if (isOpener) HudCard.text("Door opener", x + BOX_W - 8f - HudCard.width("Door opener", 9f), y + BOX_H - 16f, 9f, OPENER_COLOR)
            }
        }
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
        else if (!loaded(screen)) { pending = mate.name; pendingContainer = screen.menu.containerId; pendingNotBefore = 0L }
    }

    private fun leap(screen: AbstractContainerScreen<*>, mate: Mate) {
        pending = null
        val now = System.currentTimeMillis()
        if (now - lastClick < 400) return
        lastClick = now
        val player = mc.player ?: return
        mc.gameMode?.handleContainerInput(screen.menu.containerId, mate.slot, 2, ContainerInput.CLONE, player)
        if (announce.value) player.connection.sendCommand("pc Leaping to ${mate.name}")
    }
}
