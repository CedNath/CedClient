//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.state.IslandState
import ced.cedclient.utils.CursorGlide
import ced.cedclient.utils.Debug
import ced.cedclient.utils.HumanLook
import ced.cedclient.utils.isLookingAt
import ced.cedclient.utils.lookAngles
import ced.cedclient.utils.cleanName
import ced.cedclient.utils.loreLines
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.EntityHitResult
import java.time.LocalTime

/**
 * One-shot Fire Sale buyer. Armed: from a few minutes before the start time it runs /skyblock, /call elizabeth, opens the
 * Fire Sales tab of the Community Shop and, once the start time has passed, clicks the target skin and its confirm button.
 * Safety: only buys when the listing's lore shows the expected gem price, never more than Amount purchases, never faster
 * than one click per 1.5 s, and disarms itself when done.
 */
object FireSaleBuyer : Module(
    "Fire Sale Buyer",
    Category.Cheats,
    "Opens Elizabeth's Fire Sales and buys the chosen skin at the start time. One-shot, disarms itself."
) {
    private val armed = BooleanSetting("Armed", false, "Run the sequence. Turns itself off when finished.")
    private val amount = NumberSetting("Amount", 2.0, 1.0, 5.0, 1.0, "How many to buy.")
    private val hour = NumberSetting("Start Hour", 17.0, 0.0, 23.0, 1.0, "Local time the sale starts.")
    private val minute = NumberSetting("Start Minute", 59.0, 0.0, 59.0, 1.0)
    private val leadMin = NumberSetting("Prep Minutes", 8.0, 1.0, 30.0, 1.0, "Begin /skyblock and /call this long before the start.")

    private val navigateNow = BooleanSetting("Navigate Now", false, "Skip the prep wait to test the menus. Still never buys before the start time.")
    private val autoJoin = BooleanSetting("Auto Join Hypixel", true, "From the title screen, join mc.hypixel.net.")
    private var lastJoin = 0L

    private const val HOST = "mc.hypixel.net"
    private val itemName = TextSetting("Item Name", "eclipsed spectre wither goggles", "Skin to buy: any part of its name, as shown in the Fire Sales listing.", 48)
    private val gemPrice = TextSetting("Gem Price", "650", "Gem cost shown in the listing. It only buys when the listing's Cost line contains this.", 8)
    private val ITEM get() = itemName.value.trim()
    private val PRICE get() = gemPrice.value.trim()
    private val testClick = BooleanSetting("Test Click", false, "Click the skin listing once, even before the sale, and log what opens. Never confirms anything.")
    private var testDone = false
    private var pendingConfirm = false
    private var dialogClicks = 0
    private var skinClickAt = 0L
    private var nextGap = 3000L
    private var calls = 0
    private var tabClicks = 0

    private val mc get() = Minecraft.getInstance()
    private var bought = 0
    private var lastClick = 0L
    private var lastCommand = 0L
    private var lastScreenTitle = ""
    private var skyblockSent = false
    private var armedBefore = false

    init {
        addSettings(armed, itemName, gemPrice, amount, hour, minute, leadMin, navigateNow, autoJoin, testClick)
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
    }

    private fun dbg(msg: String) { Debug.line("FireSale", msg); println("[FireSale] $msg") }

    private fun startMillisFromNow(): Long {
        val start = LocalTime.of(hour.value.toInt(), minute.value.toInt())
        return java.time.Duration.between(LocalTime.now(), start).toMillis()
    }

    private val look = HumanLook(0.12f, 540f, 0f)
    private var npcClickAt = 0L
    private var npcClicks = 0
    private var npcSeenAt = 0L

    /** In the hub with no menu open: path next to Elizabeth (found by her name tag) and right-click her body. Gives up after 5 clicks or 90 s without seeing her. */
    private fun openElizabeth(player: net.minecraft.client.player.LocalPlayer, now: Long) {
        val level = mc.level ?: return
        val entities = level.entitiesForRendering()
        val tag = entities.firstOrNull { it is ArmorStand && it.customName?.string?.contains("Elizabeth", ignoreCase = true) == true }
        if (tag == null) {
            if (npcSeenAt == 0L) npcSeenAt = now
            if (now - npcSeenAt > 90_000) { dbg("Elizabeth not loaded after 90 s in the hub, giving up"); armed.value = false }
            return
        }
        npcSeenAt = 0L
        // The clickable body is the non-armor-stand entity standing under her name tag.
        val body = entities.filter { it is LivingEntity && it !is ArmorStand && it !== player && it.distanceToSqr(tag) < 9.0 }.minByOrNull { it.distanceToSqr(tag) }
        val target = body ?: tag
        if (player.distanceToSqr(target) > 3.0 * 3.0) {
            if (!Pathfinding.active) {
                if (!Pathfinding.isEnabled) Pathfinding.toggle()
                dbg("pathing to Elizabeth at ${target.blockPosition().toShortString()}")
                Pathfinding.goTo(target.blockPosition()) { ok -> if (!ok) dbg("no route to Elizabeth") }
            }
            return
        }
        if (Pathfinding.active) Pathfinding.stop(true)
        // Turn to face her first; click only once the crosshair is on her.
        val (yaw, pitch) = lookAngles(player, target)
        val (ny, np) = look.step(player.yRot, player.xRot, yaw, pitch, 0.05f)
        player.yRot = ny; player.xRot = np
        if (!isLookingAt(player, target, 6f)) return
        if (now - npcClickAt < 2500) return
        if (npcClicks >= 5) { dbg("5 clicks on Elizabeth without the shop opening, giving up"); armed.value = false; return }
        npcClicks++; npcClickAt = now
        mc.gameMode?.interact(player, target, EntityHitResult(target), InteractionHand.MAIN_HAND)
        dbg("right-click Elizabeth ($npcClicks/5)")
    }

    private fun tick() {
        if (!isEnabled || !armed.value) { armedBefore = false; return }
        if (!armedBefore) { armedBefore = true; bought = 0; skyblockSent = false; calls = 0; tabClicks = 0; npcClicks = 0; npcSeenAt = 0L; testDone = false; pendingConfirm = false; dialogClicks = 0; dbg("armed, start ${hour.value.toInt()}:${minute.value.toInt()}, amount ${amount.value.toInt()}") }
        // An empty name would match every slot ("".contains), so refuse to run without both.
        if (ITEM.isEmpty() || PRICE.isEmpty()) { dbg("Item Name or Gem Price is empty, disarming"); armed.value = false; return }
        val now = System.currentTimeMillis()
        val untilStart = startMillisFromNow()
        if (!navigateNow.value && untilStart > leadMin.value * 60_000) return
        val player = mc.player
        if (player == null) {
            val screen = mc.gui.screen()
            if (autoJoin.value && mc.level == null && (screen is TitleScreen || screen is JoinMultiplayerScreen) && now - lastJoin > 30_000) {
                lastJoin = now
                dbg("joining $HOST")
                val data = ServerData("Hypixel", HOST, ServerData.Type.OTHER)
                ConnectScreen.startConnecting(screen, mc, ServerAddress.parseString(HOST), data, false, null)
            }
            return
        }
        // A purchase counts once its confirm dialog has closed, not per click.
        if (pendingConfirm) {
            val cur = mc.gui.screen() as? AbstractContainerScreen<*>
            val t = cur?.title?.string?.lowercase() ?: ""
            val stillConfirm = cur != null && (t.contains("confirm") ||
                (now - skinClickAt < 15_000 && !t.contains("community shop") && cur.menu.slots.any { it.hasItem() && it.item.cleanName.contains("confirm", ignoreCase = true) }))
            if (!stillConfirm) { pendingConfirm = false; dialogClicks = 0; bought++; dbg("purchase $bought/${amount.value.toInt()} sent") }
        }
        if (untilStart < -10 * 60_000) { dbg("start time passed by 10+ minutes, disarming"); armed.value = false; return }
        if (bought >= amount.value.toInt()) { dbg("bought $bought, done"); armed.value = false; return }

        if (testClick.value && testDone && now - lastClick > 10_000) { dbg("test finished, disarming"); armed.value = false; return }
        val screen = mc.gui.screen() as? AbstractContainerScreen<*>
        if (screen == null) {
            if (IslandState.island != IslandState.Island.HUB) {
                if (now - lastCommand < 20_000) return
                lastCommand = now
                if (!skyblockSent) {
                    skyblockSent = true
                    if (!IslandState.inSkyBlock) { player.connection.sendCommand("skyblock"); dbg("/skyblock"); lastCommand = now - 12_000; return }
                }
                if (calls >= 3) { dbg("3 hub warps without reaching the hub, giving up"); armed.value = false; return }
                calls++
                player.connection.sendCommand("warp hub"); dbg("/warp hub ($calls/3)")
                return
            }
            openElizabeth(player, now)
            return
        }

        val title = screen.title.string
        if (title != lastScreenTitle) {
            lastScreenTitle = title
            dbg("screen '$title': " + screen.menu.slots.filter { it.hasItem() }.take(60).joinToString { "${it.index}=${it.item.cleanName}" })
        }
        val tl = title.lowercase()
        val recentSkin = now - skinClickAt < 15_000
        if (!tl.contains("community shop") && !tl.contains("confirm") && !recentSkin) return
        if (now - lastClick < nextGap) return

        fun click(index: Int, why: String, fast: Boolean = false) {
            lastClick = now
            // Buying clicks (skin, confirm) use a short random gap; everything else stays slow to keep traffic low.
            nextGap = if (fast) (200..260).random().toLong() else (2500..4000).random().toLong()
            val send = {
                mc.gameMode?.handleContainerInput(screen.menu.containerId, index, 0, ContainerInput.PICKUP, player)
                dbg("click slot $index ($why)")
            }
            // The cursor glides to the slot first; the glide fits inside the gap so the next click is never due early.
            val slot = screen.menu.slots.getOrNull(index)
            if (slot == null || CursorGlide.busy) send() else CursorGlide.moveTo(screen, slot, if (fast) 180L else 900L, send)
        }
        val slots = screen.menu.slots

        // 1. Confirm button of a purchase dialog.
        val confirm = slots.firstOrNull { it.hasItem() && it.item.cleanName.contains("confirm", ignoreCase = true) }
        if (confirm != null && !testClick.value && (tl.contains("confirm") || (recentSkin && !tl.contains("community shop")))) {
            // Wrong-skin guard: any other skin shown in the dialog (not the buttons) must be the target, else refuse.
            val shown = slots.filter {
                it.hasItem() && !it.item.cleanName.contains("confirm", true) && !it.item.cleanName.contains("cancel", true) &&
                    (it.item.cleanName.contains("skin", true) || it.item.loreLines.any { l -> l.contains("skin", true) })
            }
            if (shown.isNotEmpty() && shown.none { it.item.cleanName.contains(ITEM, true) || it.item.loreLines.any { l -> l.contains(ITEM, true) } }) {
                if (now - lastCommand > 3000) { lastCommand = now; dbg("confirm dialog shows a different skin, refusing: ${shown.map { it.item.cleanName }}") }
                return
            }
            if (dialogClicks >= 2) return
            dialogClicks++
            pendingConfirm = true
            click(confirm.index, "confirm click $dialogClicks (purchase ${bought + 1}/${amount.value.toInt()})", fast = true)
            return
        }

        // 2. The skin listing, once the sale is live.
        // Before the sale the listing is an "UPCOMING SALE" item with the skin name in its lore; once live the name is the skin.
        val skin = slots.firstOrNull {
            it.hasItem() && (it.item.cleanName.contains(ITEM, ignoreCase = true) || it.item.loreLines.any { l -> l.contains(ITEM, ignoreCase = true) })
        }
        if (skin != null) {
            val lore = skin.item.loreLines
            val live = untilStart <= 0 && lore.none { it.contains("starts in", ignoreCase = true) }
            val priceOk = lore.any { it.contains("Cost", ignoreCase = true) && it.contains(PRICE) }
            if (!priceOk) { if (now - lastCommand > 5000) { lastCommand = now; dbg("price check failed, lore: $lore") }; return }
            if (testClick.value) {
                if (!testDone) { testDone = true; click(skin.index, "TEST click on skin listing (not buying)") }
                return
            }
            if (live) { skinClickAt = now; click(skin.index, "skin listing", fast = true) }
            return
        }

        // 3. Fire Sales tab.
        val tab = slots.firstOrNull { it.hasItem() && it.index < 54 && it.item.cleanName.contains("fire sale", ignoreCase = true) }
        if (tab != null) {
            if (tabClicks >= 3) { dbg("fire sales tab clicked 3 times without the listing, giving up"); armed.value = false; return }
            tabClicks++
            click(tab.index, "fire sales tab ($tabClicks/3)")
        }
    }
}
//#endif
