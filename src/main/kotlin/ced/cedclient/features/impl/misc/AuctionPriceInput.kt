package ced.cedclient.features.impl.misc

import ced.cedclient.config.ConfigManager
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.ui.search.PriceInputScreen
import ced.cedclient.utils.LowestBin
import ced.cedclient.utils.loreLines
import ced.cedclient.utils.skyblockId
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.SignBlockEntity
import org.lwjgl.glfw.GLFW
import java.math.BigDecimal

/**
 * Replaces Hypixel's Auction House price signs (starting bid, BIN price, bids) with an input box
 * that understands 1.5m / 250k / 2b / 1,000,000 and shows the full number before it's sent.
 *
 * Hypixel's signs all look the same: line 1 is the input, line 2 is "^^^^^^^^^^^^^^^", lines 3-4 say what it's for.
 * The plain number is sent back, so the shortcuts never depend on what Hypixel itself accepts.
 */
object AuctionPriceInput : Module(
    "Auction Price Input",
    Category.Skyblock,
    "Input box with 1.5m / 250k / 2b shortcuts for Auction House price / bid signs"
) {
    private val mc = Minecraft.getInstance()
    private val undercutDefault = BooleanSetting("Undercut By Default", false, "Open in undercut mode (typed amount is taken off the lowest BIN) when the item has one. Tab or the Undercut button switches.")
    private val rememberLast = BooleanSetting("Remember Last Amount", true, "The box opens with what you typed last time (selected, so typing replaces it)")
    private val enterSubmits = BooleanSetting("Enter Creates Auction", true, "Enter in the Create Auction menu clicks Create, Enter in Confirm clicks Confirm")
    private val lastAmount = TextSetting("Last Amount", "", maxLength = 15).also { it.hide() }
    val undercutByDefault get() = undercutDefault.value

    /** What to prefill the box with ("" when Remember Last Amount is off). */
    val startText: String get() = if (rememberLast.value) lastAmount.value else ""

    /** PriceInputScreen: Enter sent [typed]. */
    fun onSubmit(typed: String) {
        if (rememberLast.value && typed.isNotBlank() && typed != lastAmount.value) {
            lastAmount.value = typed
            ConfigManager.saveModulesOnly()
        }
    }

    /** Called from LocalPlayerSignMixin after SearchOverlay. True = we replaced the sign. */
    @JvmStatic
    fun tryOpen(sign: SignBlockEntity, front: Boolean): Boolean {
        if (!isEnabled) return false
        val lines = sign.getText(front).getMessages(false).map { it.string }
        if (!lines[1].startsWith("^^^")) return false
        // Auction House only. Hypixel closes the menu before the sign opens, so use what the last AH menu showed.
        if (System.currentTimeMillis() - lastAuctionAt > 3000) return false
        val hint = (lines[2] + " " + lines[3]).trim()
        mc.gui.setScreen(PriceInputScreen(sign, front, hint, lastAuctionItem))
        return true
    }

    private var lastAuctionItem: ItemStack? = null
    private var lastAuctionAt = 0L

    private fun auctionScreen(): AbstractContainerScreen<*>? =
        (mc.gui.screen() as? AbstractContainerScreen<*>)?.takeIf { "auction" in it.title.string.lowercase() }

    /** The item being sold / bid on: slot 13 in Create Auction and Auction View, else any SkyBlock item in the menu. */
    private fun auctionItem(screen: AbstractContainerScreen<*>): ItemStack? {
        val slots = screen.menu.slots.filter { it.container !== mc.player?.inventory }
        val stack = slots.getOrNull(13)?.item?.takeIf { it.skyblockId != null }
            ?: slots.firstOrNull { it.item.skyblockId != null }?.item
        return stack?.copy()
    }

    /** Slot whose lore has [line] (Hypixel's "Click to submit!" / "Click to confirm!"). */
    private fun buttonSlot(screen: AbstractContainerScreen<*>, line: String) =
        screen.menu.slots.firstOrNull { s -> s.container !== mc.player?.inventory && s.item.loreLines.any { line in it.lowercase() } }

    private fun clickButton(screen: AbstractContainerScreen<*>): Boolean {
        val title = screen.title.string
        val slot = when {
            title.startsWith("Create") -> buttonSlot(screen, "click to submit")
            title.startsWith("Confirm") -> buttonSlot(screen, "click to confirm")
            else -> null
        } ?: return false
        val player = mc.player ?: return false
        ced.cedclient.utils.Debug.line("Auction Price Input", "clicked slot ${slot.index} in \"$title\"")
        mc.gameMode?.handleContainerInput(screen.menu.containerId, slot.index, 0, ContainerInput.PICKUP, player)
        return true
    }

    /** "1.5m" -> 1500000, "1,000,000" -> 1000000, "12.5" -> 12.5; null if it isn't a number. */
    fun parse(input: String): BigDecimal? {
        val m = Regex("""^(\d*\.?\d+)([kmb])?$""").matchEntire(input.lowercase().replace(Regex("[,_ ]"), "")) ?: return null
        val mult = when (m.groupValues[2]) { "k" -> 1_000L; "m" -> 1_000_000L; "b" -> 1_000_000_000L; else -> 1L }
        return BigDecimal(m.groupValues[1]).multiply(BigDecimal.valueOf(mult)).stripTrailingZeros()
    }

    init {
        addSettings(undercutDefault, rememberLast, enterSubmits, lastAmount)
        ClientTickEvents.END_CLIENT_TICK.register {
            if (!isEnabled) return@register
            val screen = auctionScreen() ?: return@register
            lastAuctionAt = System.currentTimeMillis()
            auctionItem(screen)?.let { lastAuctionItem = it }
        }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            ScreenKeyboardEvents.allowKeyPress(screen).register { s, event ->
                val enter = event.key == GLFW.GLFW_KEY_ENTER || event.key == GLFW.GLFW_KEY_KP_ENTER
                !(isEnabled && enterSubmits.value && enter && "auction" in s.title.string.lowercase() && clickButton(s as AbstractContainerScreen<*>))
            }
        }
        // Fetched from startup so prices are there on the first sign.
        LowestBin.start()
        check(parse("1.5m")?.toPlainString() == "1500000")
        check(parse("250K")?.toPlainString() == "250000")
        check(parse("2b")?.toPlainString() == "2000000000")
        check(parse("1,000,000")?.toPlainString() == "1000000")
        check(parse("12.5")?.toPlainString() == "12.5")
        check(parse("abc") == null && parse("1mm") == null && parse("") == null)
    }
}
