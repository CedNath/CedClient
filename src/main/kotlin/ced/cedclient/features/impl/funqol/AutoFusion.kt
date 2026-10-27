//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.mixin.accessor.AbstractContainerScreenAccessor
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.CursorGlide
import ced.cedclient.utils.cleanName
import ced.cedclient.utils.loreLines
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Items

/**
 * Repeats a shard fusion: do one fusion by hand (Fusion Box > pick > Confirm Fusion), then it keeps clicking
 * "Repeat Previous Fusion" (right of the sign) and the "Click to fuse!" button in Confirm Fusion.
 * Stops when you close the menu, or when a click doesn't lead anywhere (out of shards).
 */
object AutoFusion : Module(
    "Auto Fusion",
    Category.Cheats,
    "Repeats your last shard fusion in the Fusion Box until you close the menu"
) {
    private val mc: Minecraft = Minecraft.getInstance()

    private val clickDelay = NumberSetting("Click Delay ms", 300.0, 100.0, 1500.0, 50.0)
    private val smoothMouse = BooleanSetting("Smooth Mouse", true, "Move the cursor to each button before clicking it")

    private const val STUCK_MS = 2500L
    private const val CLOSED_TICKS = 10

    private var armed = false
    private var lastClickAt = 0L
    private var lastClickTitle: String? = null
    private var closedTicks = 0

    init {
        addSettings(clickDelay, smoothMouse)

        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            if (!isEnabled || !armed) return@register
            tick()
        }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            // Your own fusion arms it: clicking Confirm (or Repeat) yourself.
            ScreenMouseEvents.allowMouseClick(screen).register { s, event ->
                if (isEnabled && !armed) {
                    val cs = s as AbstractContainerScreen<*>
                    val slot = slotAt(cs, event.x(), event.y())
                    if (slot != null && (slot == confirmSlot(cs) || slot == repeatSlot(cs))) {
                        armed = true
                        closedTicks = 0
                        lastClickAt = System.currentTimeMillis()
                        lastClickTitle = cs.title.string
                        mc.player?.sendSystemMessage(ChatPrefix.msgFor("Auto Fusion", "§aAuto Fusion on §7(close the menu to stop)"))
                    }
                }
                true
            }
        }
    }

    private fun stop(reason: String) {
        armed = false
        lastClickTitle = null
        CursorGlide.cancel()
        mc.player?.sendSystemMessage(ChatPrefix.msgFor("Auto Fusion", "§cAuto Fusion off §7($reason)"))
    }

    private fun tick() {
        val screen = mc.gui.screen() as? AbstractContainerScreen<*>
        if (screen == null) {
            // Short gaps happen between Hypixel menus; only a real close stops it.
            if (++closedTicks >= CLOSED_TICKS) stop("menu closed")
            return
        }
        closedTicks = 0
        val title = screen.title.string
        val now = System.currentTimeMillis()
        if (lastClickTitle == title && now - lastClickAt > STUCK_MS) {
            stop("nothing happened - out of shards?")
            return
        }
        if (now - lastClickAt < clickDelay.value.toLong()) return
        if (CursorGlide.busy) return
        val target = confirmSlot(screen) ?: repeatSlot(screen) ?: return
        val id = screen.menu.containerId
        // Plain left click, same as clicking the button yourself; with Smooth Mouse the cursor travels there first.
        val click = {
            mc.player?.let { player ->
                mc.gameMode?.handleContainerInput(id, target.index, 0, ContainerInput.PICKUP, player)
                ced.cedclient.utils.Debug.line("Auto Fusion", "clicked slot ${target.index}")
                lastClickAt = System.currentTimeMillis()
                lastClickTitle = title
            }
        }
        if (smoothMouse.value) CursorGlide.moveTo(screen, target, clickDelay.value.toLong()) { click() } else click()
    }

    private fun menuSlots(screen: AbstractContainerScreen<*>) = screen.menu.slots.filter { it.container !== mc.player?.inventory }

    private fun confirmSlot(screen: AbstractContainerScreen<*>): Slot? {
        if ("Confirm Fusion" !in screen.title.string) return null
        return menuSlots(screen).firstOrNull { s -> s.item.loreLines.any { "click to fuse" in it.lowercase() } }
            ?: menuSlots(screen).firstOrNull { it.item.`is`(Items.DYED_TERRACOTTA.green()) }
    }

    /** "Repeat Previous Fusion" ("Click to repeat this fusion!"). */
    private fun repeatSlot(screen: AbstractContainerScreen<*>): Slot? {
        if ("Fusion Box" !in screen.title.string) return null
        return menuSlots(screen).firstOrNull { s ->
            s.item.cleanName.equals("Repeat Previous Fusion", ignoreCase = true) ||
                s.item.loreLines.any { "click to repeat this fusion" in it.lowercase() }
        }
    }

    private fun slotAt(screen: AbstractContainerScreen<*>, mx: Double, my: Double): Slot? {
        val acc = screen as AbstractContainerScreenAccessor
        return screen.menu.slots.firstOrNull {
            val x = acc.`ced$getLeftPos`() + it.x
            val y = acc.`ced$getTopPos`() + it.y
            mx >= x && mx < x + 16 && my >= y && my < y + 16
        }
    }
}
//#endif
