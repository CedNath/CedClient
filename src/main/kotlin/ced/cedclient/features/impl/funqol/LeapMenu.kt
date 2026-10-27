package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.RenderableSetting
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.state.DungeonState.DungeonClass
import ced.cedclient.state.DungeonTeam
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.SettingsPopup
import ced.cedclient.data.ClickType
import ced.cedclient.events.ItemClickEvent
import ced.cedclient.events.core.on
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.skyblockId
//#if CHEAT
import ced.cedclient.state.DungeonState
import net.minecraft.world.InteractionHand
//#endif
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
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
 * Whoever opened the last wither door gets a gold box, until blood opens or the world changes. Leap Announce and
 * Hide Players go off Hypixel's "You have teleported to X!", so they only happen when a leap went through.
 * All options sit in the "Leap Options" popup.
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
    private val numberKeys = BooleanSetting("Number Keys", false, "Number keys leap (see Key Mode).")
    private val keyMode = DropdownSetting("Key Mode", listOf("Corners", "Class"), "Corners", "Corners: 1-4 = top-left, top-right, bottom-left, bottom-right. Class: 1 Archer, 2 Berserk, 3 Healer, 4 Mage, 5 Tank.")
    private val queueClicks = BooleanSetting("Queue Clicks While Laggy", false, "A click made while the server is stalling is held and sent as soon as the server catches up, instead of getting lost.")
    private val leftClickOnly = BooleanSetting("Left Click Only", false, "Only a left click leaps (right / middle clicks do nothing).")
    private val showHeads = BooleanSetting("Player Heads", false, "Show each teammate's skin head in their box.")
    private val tintDead = BooleanSetting("Tint Dead Players", true, "Grey out dead teammates' boxes.")
    private val hidePlayers = BooleanSetting("Hide Players", false, "Hide other players for a moment after you leap, so they don't block your view.")
    private val hideTime = NumberSetting("Hide Time", 3.5, 0.5, 5.0, 0.1, "Seconds players stay hidden after a leap.")
    private val highlightOpener = BooleanSetting("Highlight Door Opener", true, "Gold box on whoever opened the last wither door (cleared once blood opens).")
    private val menuScale = NumberSetting("Menu Size", 1.0, 0.5, 2.0, 0.05, "Size of the leap boxes (the click areas stay the screen quarters).")
    private val announce = BooleanSetting("Leap Announce", false, "Says the Announce Message in party chat when you leap.")
    private val announceText = TextSetting("Announce Message", "Leaping to {name}", "Party chat line; {name} = who you leap to, {class} = their class.", 64)
    private val sortBy = DropdownSetting("Sort By", listOf("Class Order", "A-Z Class", "A-Z Name", "Custom", "No Sorting"), "A-Z Class",
        "Box order (top-left, top-right, bottom-left, bottom-right). No Sorting = the tab list's order.")
    private val classOrder = TextSetting("Class Order", "ABHMT", "For Sort By Class Order: first letters of the classes in box order (Archer Berserk Healer Mage Tank).", 5)
    private val customOrder = TextSetting("Custom Order", "", "For Sort By Custom: names, comma separated, in box order. Anyone not listed fills the rest A-Z.", 80)
//#if CHEAT
    private val quickLeap = BooleanSetting("Quick Leap", false, "Left-click with Spirit Leap / Infinileap leaps straight to whoever opened the last wither door.")
    private val quickSpeed = DropdownSetting("Quick Leap Speed", listOf("Slow", "Normal", "Fast"), "Normal", "How long the menu stays open before Quick Leap clicks: Slow 350 ms, Normal 150 ms, Fast 50 ms.")
    /** Quick Leap target, waiting for the menu it opened (until [quickUntil]). */
    private var quickTarget: String? = null
    private var quickUntil = 0L
//#endif

    private val TITLES = setOf("Spirit Leap", "Teleport to Player")
    val LEAP_ITEMS = setOf("SPIRIT_LEAP", "INFINITE_SPIRIT_LEAP")
    private val CODES = Regex("§.")
    private val TELEPORTED = Regex("""^You have teleported to (\w+)!?$""")
    private val WITHER_OPENED = Regex("""^(?:\[.+?] )?(\w+) opened a WITHER door!$""")
    private const val BLOOD_OPENED = "The BLOOD DOOR has been opened!"
    private const val OPENER_COLOR = 0xFFFFD700.toInt()

    private const val BOX_W = 170f
    private const val BOX_H = 56f
    private const val HEAD = 32f
    private const val GAP = 8f

    private class Mate(val name: String, val cls: DungeonClass?, val dead: Boolean, var slot: Int = -1)

    private val mc get() = Minecraft.getInstance()

    /** A pick made before the heads arrived: the teammate's name, for this container id. */
    private var pending: String? = null
    private var pendingContainer = -1
    /** The early pick isn't sent before this (Quick Leap Speed). */
    private var pendingNotBefore = 0L
    private var lastClick = 0L

    /** "/cc dev leap": a chat line per leap with how long each step took. */
    var debug = false
    /** Debug timestamps (ms): leap item used, menu's first frame, heads in, your pick; [seenContainer] = that menu. */
    private var usedAt = 0L
    private var openedAt = 0L
    private var headsAt = 0L
    private var pickedAt = 0L
    private var seenContainer = -1
    private var doorOpener: String? = null
    private var openerLevel: ClientLevel? = null
    /** Hide Players: other players stay hidden until this time (ms). */
    private var hideUntil = 0L

    init {
        // Debug "use" time: a right-click aimed at a block or a player never reaches useItem (ItemClickEvent).
        on<ItemClickEvent> { event -> if (event.clickType == ClickType.RIGHT_CLICK) markUse(event.itemInHand) }
        UseBlockCallback.EVENT.register(UseBlockCallback { player, level, hand, _ ->
            if (level.isClientSide) markUse(player.getItemInHand(hand))
            InteractionResult.PASS
        })
        UseEntityCallback.EVENT.register(UseEntityCallback { player, level, hand, _, _ ->
            if (level.isClientSide) markUse(player.getItemInHand(hand))
            InteractionResult.PASS
        })
//#if CHEAT
        addPopupSettings("Menu" to listOf(menuScale, sortBy, classOrder, customOrder, highlightOpener, tintDead, showHeads), "Leaping" to listOf(numberKeys, keyMode, leftClickOnly, queueClicks, announce, announceText, hidePlayers, hideTime, quickLeap, quickSpeed))
        on<ItemClickEvent> { event ->
            if (event.clickType != ClickType.LEFT_CLICK || !isEnabled || !quickLeap.value || !DungeonState.inDungeon) return@on
            if (event.itemInHand?.skyblockId !in LEAP_ITEMS) return@on
            val target = doorOpener.takeIf { openerLevel === mc.level }
            if (target == null) { mc.player?.sendSystemMessage(ChatPrefix.msgFor("Leap Menu", "§cNo wither door opened yet")); return@on }
            val player = mc.player ?: return@on
            quickTarget = target
            quickUntil = System.currentTimeMillis() + 2000
            pickedAt = quickUntil - 2000
            mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
        }
//#else
//$        addPopupSettings("Menu" to listOf(menuScale, sortBy, classOrder, customOrder, highlightOpener, tintDead, showHeads), "Leaping" to listOf(numberKeys, keyMode, leftClickOnly, queueClicks, announce, announceText, hidePlayers, hideTime))
//#endif
        ClientReceiveMessageEvents.GAME.register { message, _ ->
            val plain = message.string.replace(CODES, "").trim()
            WITHER_OPENED.find(plain)?.let { doorOpener = it.groupValues[1]; openerLevel = mc.level }
            if (plain == BLOOD_OPENED) doorOpener = null
            TELEPORTED.find(plain)?.let { onTeleported(it.groupValues[1]) }
        }
    }

    /** One "Leap Options" button in the panel; [sections] open in a popup from it. */
    private fun addPopupSettings(vararg sections: Pair<String, List<RenderableSetting<*>>>) {
        addSettings(ActionSetting("Leap Options", "Number keys, door opener highlight, leap announce.") {
            (mc.gui.screen() as? ClickGUI)?.openPopup(SettingsPopup("Leap Menu", sections.map { (title, list) -> listOf(SettingsPopup.Section(title, list)) }))
        })
        val all = sections.flatMap { it.second }
        addSettings(*all.toTypedArray())
        all.forEach { it.inPopup = true }
    }

    /** A leap went through: announce it and start hiding players. */
    private fun onTeleported(name: String) {
        if (!isEnabled) return
        if (hidePlayers.value) hideUntil = System.currentTimeMillis() + (hideTime.value * 1000).toLong()
        if (!announce.value) return
        val cls = DungeonTeam.members().firstOrNull { it.name.equals(name, ignoreCase = true) }?.cls
            ?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""
        mc.player?.connection?.sendCommand("pc " + announceText.value.replace("{name}", name).replace("{class}", cls))
    }

    /** Hide Players: true for other players right after a leap (EntityRenderDispatcherMixin skips them). */
    @JvmStatic
    fun hideEntity(entity: net.minecraft.world.entity.Entity): Boolean =
        hideUntil != 0L && entity is net.minecraft.world.entity.player.Player && entity !== mc.player && System.currentTimeMillis() < hideUntil

    private fun markUse(stack: ItemStack?) {
        if (debug && stack?.skyblockId in LEAP_ITEMS) usedAt = System.currentTimeMillis()
    }

    /** Last wither door opener in this world, if the highlight is on. */
    private fun opener(): String? = doorOpener.takeIf { highlightOpener.value && openerLevel === mc.level }

    fun handles(screen: AbstractContainerScreen<*>): Boolean =
        isEnabled && screen is ContainerScreen && screen.title.string in TITLES

    /** HUD panels deferred by [defer]: drawn first in the menu's own NVG pass, so they sit under its backdrop and boxes. */
    private val behind = ArrayList<() -> Unit>()

    /** While the menu is open: queues [draw] (a full-canvas NVG draw) to render behind the menu; false when it isn't open. */
    fun defer(draw: () -> Unit): Boolean {
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return false
        if (!handles(screen)) return false
        behind += draw
        return true
    }

    /** Up to four teammates in box order, with the slot of their head once the chest has items. */
    private fun mates(screen: AbstractContainerScreen<*>): List<Mate> {
        val mates = DungeonTeam.members().map { Mate(it.name, it.cls, it.dead) }
            .let { sorted(it) }
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

    /** Sort By: Class Order (then name), A-Z by class or name, a custom name list, or the tab list as is. */
    private fun sorted(mates: List<Mate>): List<Mate> = when (sortBy.value) {
        "A-Z Name" -> mates.sortedBy { it.name.lowercase() }
        "A-Z Class" -> mates.sortedWith(compareBy<Mate>({ it.cls?.name ?: "~" }, { it.name.lowercase() }))
        "No Sorting" -> mates
        "Custom" -> {
            val order = customOrder.value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            mates.sortedWith(compareBy<Mate>({ order.indexOf(it.name.lowercase()).let { i -> if (i < 0) 99 else i } }, { it.name.lowercase() }))
        }
        else -> {
            val order = classOrder.value.uppercase()
            fun rank(cls: DungeonClass?) = cls?.let { order.indexOf(it.name[0]) }?.takeIf { it >= 0 } ?: 99
            mates.sortedWith(compareBy<Mate>({ rank(it.cls) }, { it.name.lowercase() }))
        }
    }

    /** No server tick packet for over 150 ms (they normally come every 50). */
    private fun laggy() = ced.cedclient.utils.TpsTracker.msSinceTick() > 150

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
        if (id != seenContainer) { seenContainer = id; openedAt = System.currentTimeMillis(); headsAt = 0L }
        if (headsAt == 0L && loaded(screen)) headsAt = System.currentTimeMillis()
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
            else if (System.currentTimeMillis() >= pendingNotBefore && !(queueClicks.value && laggy()))
                mates.firstOrNull { it.name.equals(name, ignoreCase = true) && it.slot >= 0 }?.let { leap(screen, it) }
        }
        val loaded = loaded(screen)
        val hovered = indexAt(screen, mouseX.toDouble(), mouseY.toDouble())
        val opener = opener()
        HudCard.draw(g, 0, 0) {
            // The queued panels expect a fresh full canvas (they scale by the GUI ratio themselves).
            val ratio = NVGRenderer.canvasWidth / g.guiWidth().toFloat()
            for (panel in behind) { NVGRenderer.push(); NVGRenderer.scale(1 / ratio, 1 / ratio); panel(); NVGRenderer.pop() }
            behind.clear()
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
                // Dead teammates still can't be picked; Tint Dead Players only decides whether they look it.
                val usable = !(mate.dead && tintDead.value) && (!loaded || mate.slot >= 0)
                val accent = DungeonTeam.classColor(mate.cls)
                val isOpener = mate.name.equals(opener, ignoreCase = true)
                NVGRenderer.rect(x, y, BOX_W, BOX_H, if (i == hovered && usable) 0xF0303040.toInt() else HudCard.BACKGROUND, 8f)
                val border = if (!usable) 0xFF555555.toInt() else if (isOpener) OPENER_COLOR else accent
                NVGRenderer.hollowRect(x, y, BOX_W, BOX_H, if (pending == mate.name || isOpener) 2f else 1f, border, 8f)
                NVGRenderer.rect(x + 8f, y + 10f, 4f, BOX_H - 20f, if (usable) accent else 0xFF555555.toInt(), 2f)
                val textX = if (showHeads.value) x + 20f + HEAD + 8f else x + 20f
                HudCard.text(mate.name, textX, y + 12f, 14f, if (usable) -1 else 0xFF777777.toInt())
                val sub = when {
                    mate.dead -> "Dead"
                    loaded && mate.slot < 0 -> "Not in menu"
                    else -> mate.cls?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""
                }
                HudCard.text(sub, textX, y + 32f, 10f, if (usable) accent else 0xFF777777.toInt())
                val key = if (keyMode.value == "Class") mate.cls?.let { CLASS_KEYS.indexOf(it) + 1 } else i + 1
                if (numberKeys.value && key != null) HudCard.text("$key", x + BOX_W - 16f, y + 8f, 10f, 0xFF888888.toInt())
                if (isOpener) HudCard.text("Door opener", x + BOX_W - 8f - HudCard.width("Door opener", 9f), y + BOX_H - 16f, 9f, OPENER_COLOR)
            }
        }
        if (!showHeads.value || mates.isEmpty()) return
        // Heads are vanilla sprites: a stratum above the NanoVG boxes so they land on top of them.
        g.nextStratum()
        val sc = menuScale.value.toFloat()
        g.pose().pushMatrix()
        g.pose().translate(screen.width / 2f, screen.height / 2f)
        g.pose().scale(sc, sc)
        g.pose().translate(-screen.width / 2f, -screen.height / 2f)
        mates.forEachIndexed { i, mate ->
            val skin = mc.connection?.getPlayerInfo(mate.name)?.skin ?: return@forEachIndexed
            val (x, y) = boxAt(i, screen.width, screen.height)
            PlayerFaceExtractor.extractRenderState(g, skin, (x + 20f).toInt(), (y + (BOX_H - HEAD) / 2).toInt(), HEAD.toInt())
        }
        g.pose().popMatrix()
    }

    fun mouseClicked(screen: AbstractContainerScreen<*>, mx: Double, my: Double, button: Int) {
        if (leftClickOnly.value && button != 0) return
        pick(screen, indexAt(screen, mx, my))
    }

    private val CLASS_KEYS = listOf(DungeonClass.ARCHER, DungeonClass.BERSERK, DungeonClass.HEALER, DungeonClass.MAGE, DungeonClass.TANK)

    /** Number keys (row or numpad) leap; anything else goes on to the screen (Escape closes). */
    fun keyPressed(screen: AbstractContainerScreen<*>, key: Int): Boolean {
        if (!numberKeys.value) return false
        val n = when (key) {
            in 49..53 -> key - 49 // GLFW_KEY_1..5
            in 321..325 -> key - 321 // GLFW_KEY_KP_1..5
            else -> return false
        }
        if (keyMode.value == "Class") {
            val cls = CLASS_KEYS[n]
            val index = mates(screen).indexOfFirst { it.cls == cls }
            if (index >= 0) pick(screen, index)
        } else if (n < 4) pick(screen, n)
        return true
    }

    private fun pick(screen: AbstractContainerScreen<*>, index: Int) {
        pickedAt = System.currentTimeMillis()
        val mate = mates(screen).getOrNull(index) ?: return
        if (mate.dead) return
        if (mate.slot >= 0) {
            // Server stalling: hold the click (drawn as a picked box) and let render() send it once ticks flow again.
            if (queueClicks.value && laggy()) { pending = mate.name; pendingContainer = screen.menu.containerId; pendingNotBefore = 0L }
            else leap(screen, mate)
        }
        else if (!loaded(screen)) { pending = mate.name; pendingContainer = screen.menu.containerId; pendingNotBefore = 0L }
    }

    private fun leap(screen: AbstractContainerScreen<*>, mate: Mate) {
        pending = null
        val now = System.currentTimeMillis()
        if (now - lastClick < 400) return
        lastClick = now
        val player = mc.player ?: return
        mc.gameMode?.handleContainerInput(screen.menu.containerId, mate.slot, 2, ContainerInput.CLONE, player)
        ced.cedclient.utils.Debug.line("Leap Menu", "leap click: ${mate.name} (slot ${mate.slot})")
        if (debug) {
            fun ms(from: Long, to: Long) = if (from == 0L || to == 0L) "?" else "${to - from}ms"
            player.sendSystemMessage(ChatPrefix.msgFor("Leap Menu", 
                "§7Leap §f${mate.name}§7: menu §f${ms(usedAt, openedAt)}§7 after use, heads §f${ms(openedAt, headsAt)}§7 after open, " +
                    "you picked §f${ms(openedAt, pickedAt)}§7 after open, sent §f${ms(pickedAt, now)}§7 after pick, §e${ms(usedAt, now)}§7 total"))
            usedAt = 0L
        }
    }
}
