//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.CursorGlide
import ced.cedclient.utils.enchantments as sbEnchants // ItemStack has its own enchantments member
import ced.cedclient.utils.skyblockId
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Open the SkyBlock Anvil and it merges the single-enchant books in your inventory: two of the same enchant and
 * level become one a level higher, lowest levels first, until no pair is left below [maxLevel].
 * Stops when you close the menu, or when a click doesn't lead anywhere (not enough XP, enchant already maxed).
 */
object AnvilHelper : Module(
    "Anvil Helper",
    Category.Cheats,
    "Combines matching enchanted books in your inventory in the Anvil, up to the highest level they can reach"
) {
    private val mc: Minecraft = Minecraft.getInstance()

    private val maxLevel = NumberSetting("Max Level", 5.0, 2.0, 10.0, 1.0, "Stop combining once a book reaches this level.")
    private val clickDelay = NumberSetting("Click Delay ms", 400.0, 100.0, 1500.0, 50.0)
    private val smoothMouse = BooleanSetting("Smooth Mouse", true, "Move the cursor to each slot before clicking it")

    // SkyBlock Anvil (54-slot chest): left input, right input, combine button, result.
    // shortcut: slot numbers from memory of the menu; confirm in game (run/TESTING.md) and fix here if they moved.
    private const val LEFT = 29
    private const val RIGHT = 33
    private const val COMBINE = 22
    private const val RESULT = 13
    private const val STUCK_MS = 2500L

    private var running = false
    private var lastClickAt = 0L
    private var lastState = ""

    init {
        addSettings(maxLevel, clickDelay, smoothMouse)
        ClientTickEvents.END_CLIENT_TICK.register { _ -> if (isEnabled) tick() }
    }

    /** (enchant, level) of a book with exactly one enchant, else null. */
    private fun book(stack: ItemStack): Pair<String, Int>? {
        if (stack.skyblockId != "ENCHANTED_BOOK") return null
        return stack.sbEnchants.entries.singleOrNull()?.toPair()
    }

    private fun tick() {
        val screen = mc.screen as? AbstractContainerScreen<*>
        if (screen == null || screen.title.string.trim() != "Anvil" || screen.menu.slots.size < 54 + 36) {
            stop("menu closed")
            return
        }
        val player = mc.player ?: return
        val now = System.currentTimeMillis()
        if (CursorGlide.busy || now - lastClickAt < clickDelay.value.toLong()) return

        val slots = screen.menu.slots
        val inv = slots.filter { it.container === player.inventory && book(it.item) != null }
        val left = book(slots[LEFT].item)
        val right = book(slots[RIGHT].item)
        val state = listOf(RESULT, LEFT, RIGHT).joinToString { slots[it].item.toString() } + inv.size
        if (running && state == lastState && now - lastClickAt > STUCK_MS) {
            stop("nothing happened - not enough XP or already max level?")
            return
        }

        val target: Pair<Slot, ContainerInput> = when {
            // Finished book waiting in the result slot: take it back into the inventory.
            book(slots[RESULT].item) != null && left == null && right == null -> slots[RESULT] to ContainerInput.QUICK_MOVE
            left != null && right != null -> slots[COMBINE] to ContainerInput.PICKUP
            // One side filled: add its twin.
            left != null || right != null -> {
                val twin = inv.firstOrNull { book(it.item) == (left ?: right) }
                if (twin == null) { stop("no matching book for the anvil"); return }
                twin to ContainerInput.QUICK_MOVE
            }
            else -> {
                val pair = inv.groupBy { book(it.item)!! }
                    .filter { (k, v) -> v.size >= 2 && k.second < maxLevel.value.toInt() }
                    .minByOrNull { it.key.second }
                if (pair == null) { stop("done"); return }
                pair.value.first() to ContainerInput.QUICK_MOVE
            }
        }
        if (!running) {
            running = true
            player.sendSystemMessage(ChatPrefix.msgFor("Anvil Helper", "§aAnvil Helper combining books §7(close the menu to stop)"))
        }
        val id = screen.menu.containerId
        val click = {
            mc.gameMode?.handleContainerInput(id, target.first.index, 0, target.second, player)
            ced.cedclient.utils.Debug.line("Anvil Helper", "clicked slot ${target.first.index}")
            lastClickAt = System.currentTimeMillis()
            lastState = state
        }
        // With Smooth Mouse the cursor travels to the slot first, then clicks it.
        if (smoothMouse.value) CursorGlide.moveTo(screen, target.first, clickDelay.value.toLong()) { click() } else click()
    }

    private fun stop(reason: String) {
        if (!running) return
        running = false
        CursorGlide.cancel()
        mc.player?.sendSystemMessage(ChatPrefix.msgFor("Anvil Helper", "§cAnvil Helper off §7($reason)"))
    }
}
//#endif
