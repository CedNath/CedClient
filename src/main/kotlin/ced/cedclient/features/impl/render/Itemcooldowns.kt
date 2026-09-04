package ced.cedclient.features.impl.render

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import kotlin.math.ceil

/**
 * Tracks how long each item's ability is still on cooldown for, keyed by the
 * item's clean (color-code-free) display name.
 *
 * Hypixel-style cooldown chat messages never say WHICH item they're about --
 * only that "an" ability is on cooldown -- so, same as SkyHanni, cooldowns
 * are attributed to whatever item the player had in their main hand at the
 * moment the message arrived (that's always the item that was just
 * right-clicked to trigger it), then looked up again by name wherever that
 * item currently sits (hotbar or inventory), so the countdown keeps
 * following it if it gets moved around.
 */
private object ItemCooldownTracker {

    private val cooldownEndMillis = mutableMapOf<String, Long>()

    fun start(itemName: String, seconds: Double) {
        if (itemName.isBlank() || seconds <= 0.0) return
        cooldownEndMillis[itemName] = System.currentTimeMillis() + (seconds * 1000).toLong()
    }

    /** Whole seconds left (rounded up), or null if not tracked / already expired. */
    fun secondsLeft(itemName: String): Int? {
        val end = cooldownEndMillis[itemName] ?: return null
        val remainingMs = end - System.currentTimeMillis()
        if (remainingMs <= 0) {
            cooldownEndMillis.remove(itemName)
            return null
        }
        return ceil(remainingMs / 1000.0).toInt()
    }
}

/**
 * SkyHanni-style item ability cooldown overlay: draws a countdown number on
 * top of any hotbar/inventory/armor slot holding an item whose ability is
 * currently on cooldown.
 *
 * CAVEAT -- exact chat wording: the regexes below are inferred from the
 * wildcard prefixes already sitting in ChatFilter's hide-list
 * (Chatfilter.kt: "This item is on cooldown.+", "This ability is on
 * cooldown.+", "Your Ultimate is currently on cooldown for .+ more
 * seconds."), since that's all the confirmation available about this
 * server's real message text. The generic regex only anchors on "cooldown
 * for X (more) seconds", ignoring whatever comes before it, which should
 * survive most prefix wording -- but if cooldowns never start appearing,
 * turn on "Log Unmatched Cooldown Messages", trigger an ability while it's
 * down, and check the console for the exact unformatted text so the pattern
 * can be adjusted.
 *
 * CAVEAT -- field names: main-hand/hotbar access below mirrors what
 * CoralotHelper/Pangolincatcher already use (`player.mainHandItem`,
 * `player.inventory.getItem(slot)`), and armor/offhand access mirrors
 * EntityUtils' `getItemBySlot(EquipmentSlot.X)` -- both existing patterns in
 * this codebase, so these should hold, but worth a quick check against the
 * decompiled sources if anything fails to compile.
 */
object ItemCooldowns : Module(
    "Item Cooldowns",
    Category.Render,
    "Shows a countdown on items whose ability is on cooldown, inspired by SkyHanni.",
    defaultEnabled = false
) {

    private val showOnHotbar = BooleanSetting("Show On Hotbar", true)
    private val showInInventory = BooleanSetting("Show In Inventory", true)
    private val logUnmatched = BooleanSetting(
        "Log Unmatched Cooldown Messages",
        false,
        "Prints any chat message containing \"cooldown\" to the console -- use this to find the exact " +
                "wording this server sends if cooldowns aren't being detected."
    )

    // Anchors only on the "cooldown for X (more) seconds" core, so it survives
    // most variation in the prefix wording ("This item is...", "This ability
    // is...", etc.) -- see the class-level caveat above.
    private val genericCooldownRegex =
        Regex("""cooldown for (?<seconds>[\d.]+) (?:more )?seconds?""", RegexOption.IGNORE_CASE)

    // Dungeon "Ultimate" enchant cooldown -- confirmed wording from Chatfilter.kt.
    private val ultimateCooldownRegex =
        Regex("""Your Ultimate is currently on cooldown for (?<seconds>[\d.]+) more seconds\.""")

    private val armorSlotsTopToBottom = listOf(
        EquipmentSlot.HEAD,
        EquipmentSlot.CHEST,
        EquipmentSlot.LEGS,
        EquipmentSlot.FEET
    )

    init {
        on<ChatMessageEvent> { event ->
            val text = event.unformattedText

            if (logUnmatched.value && text.contains("cooldown", ignoreCase = true)) {
                println("[ItemCooldowns] cooldown message: \"$text\"")
            }

            val match = ultimateCooldownRegex.find(text) ?: genericCooldownRegex.find(text) ?: return@on
            val seconds = match.groups["seconds"]?.value?.toDoubleOrNull() ?: return@on

            val heldItem = Minecraft.getInstance().player?.mainHandItem ?: return@on
            val name = cleanName(heldItem) ?: return@on
            ItemCooldownTracker.start(name, seconds)
        }

        addSettings(showOnHotbar, showInInventory, logUnmatched)
    }

    private fun cleanName(stack: ItemStack): String? {
        if (stack.isEmpty) return null
        return stack.hoverName.string.trim().takeIf { it.isNotEmpty() }
    }

    // --- Hotbar overlay: registered via HudElementRegistry in CedClient.kt ---

    fun render(g: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
        if (!isEnabled || !showOnHotbar.value) return

        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val font = mc.font

        val screenWidth = mc.window.guiScaledWidth
        val screenHeight = mc.window.guiScaledHeight
        val hotbarLeft = screenWidth / 2 - 91
        val hotbarTop = screenHeight - 22

        for (slot in 0..8) {
            val x = hotbarLeft + slot * 20 + 3
            val y = hotbarTop + 3
            drawSlotIfOnCooldown(g, font, player.inventory.getItem(slot), x, y)
        }
    }

    // --- Inventory-screen overlay: called from AbstractContainerScreenMixin ---

    fun renderInInventory(g: GuiGraphicsExtractor, leftPos: Int, topPos: Int) {
        if (!isEnabled || !showInInventory.value) return

        val player = Minecraft.getInstance().player ?: return
        val font = Minecraft.getInstance().font

        // Hotbar row (bottom of the inventory screen).
        for (i in 0..8) {
            drawSlotIfOnCooldown(g, font, player.inventory.getItem(i), leftPos + 8 + i * 18, topPos + 142)
        }

        // Main inventory, 3 rows x 9 columns, slots 9-35.
        for (row in 0..2) {
            for (col in 0..8) {
                val index = 9 + row * 9 + col
                val x = leftPos + 8 + col * 18
                val y = topPos + 84 + row * 18
                drawSlotIfOnCooldown(g, font, player.inventory.getItem(index), x, y)
            }
        }

        // Armor, top to bottom: head, chest, legs, feet.
        for ((i, slot) in armorSlotsTopToBottom.withIndex()) {
            drawSlotIfOnCooldown(g, font, player.getItemBySlot(slot), leftPos + 8, topPos + 8 + i * 18)
        }

        // Offhand.
        drawSlotIfOnCooldown(g, font, player.getItemBySlot(EquipmentSlot.OFFHAND), leftPos + 77, topPos + 62)
    }

    private fun drawSlotIfOnCooldown(g: GuiGraphicsExtractor, font: Font, stack: ItemStack?, x: Int, y: Int) {
        if (stack == null) return
        val name = cleanName(stack) ?: return
        val seconds = ItemCooldownTracker.secondsLeft(name) ?: return
        drawCooldownNumber(g, font, x + 8, y + 8, seconds)
    }

    private fun drawCooldownNumber(g: GuiGraphicsExtractor, font: Font, centerX: Int, centerY: Int, seconds: Int) {
        val text = seconds.toString()
        val textWidth = font.width(text)

        g.pose().pushMatrix()
        g.pose().translate(centerX.toFloat(), centerY.toFloat())
        g.pose().scale(1.4f, 1.4f)
        g.text(font, text, -textWidth / 2, -font.lineHeight / 2, 0xFFFFFFFF.toInt())
        g.pose().popMatrix()
    }
}