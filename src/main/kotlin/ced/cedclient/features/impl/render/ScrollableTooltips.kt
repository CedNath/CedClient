package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.Minecraft
import org.joml.Vector2i
import org.joml.Vector2ic

/**
 * Moves any tooltip with the mouse wheel while it shows: wheel = up / down, Ctrl + wheel = left / right.
 * A tooltip taller than the screen opens at the top so its name is visible. Placed by TooltipScrollMixin.
 */
object ScrollableTooltips : Module(
    "Scrollable Tooltips",
    Category.Render,
    "Scroll wheel moves tooltips up / down, Ctrl + scroll moves them left / right",
    defaultEnabled = true
) {
    private val speed = NumberSetting("Scroll Speed", 12.0, 4.0, 40.0, 2.0, "Pixels per wheel step")
    private val fitToScreen = BooleanSetting("Fit To Screen", false, "Tooltips taller than the screen shrink to fit (down to half size)")

    private val mc = Minecraft.getInstance()

    private var scrollX = 0
    private var scrollY = 0
    private var lastKey: Any? = null
    private var lastTooltipAt = 0L

    // Name of the item whose tooltip is being built this frame (ItemTooltipCallback runs just before it's placed).
    private var itemName: String? = null
    private var itemNameAt = 0L

    /**
     * Which tooltip this is: the hovered item's name, so Shift (Item Worth breakdown, Hypixel's extra lines) changing
     * the size doesn't count as a new tooltip. Non-item tooltips fall back to their size.
     */
    @JvmStatic
    fun tooltipKey(w: Int, h: Int): Any =
        itemName?.takeIf { System.currentTimeMillis() - itemNameAt < 50 } ?: ((w.toLong() shl 32) or h.toLong())

    /** Size the tooltip is drawn at (TooltipScrollMixin scales around its top-left corner). */
    @JvmStatic
    var drawScale = 1f
        private set

    init {
        addSettings(speed, fitToScreen)
        ItemTooltipCallback.EVENT.register { stack, _, _, _ ->
            itemName = stack.hoverName.string
            itemNameAt = System.currentTimeMillis()
        }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            scrollX = 0
            scrollY = 0
            // Not in chat: the wheel scrolls chat history there, and chat hover tooltips stay vanilla.
            if (screen !is ChatScreen) ScreenMouseEvents.allowMouseScroll(screen).register { _, _, _, _, amount -> !scroll(amount) }
        }
    }

    /** Where the tooltip goes after the wheel moved it; kept on screen (a too-big one can slide its whole size past). */
    @JvmStatic
    fun position(pos: Vector2ic, screenW: Int, screenH: Int, w: Int, h: Int): Vector2ic {
        drawScale = 1f
        if (!isEnabled || mc.gui.screen() is ChatScreen) return pos
        lastTooltipAt = System.currentTimeMillis()
        val key = tooltipKey(w, h)
        if (key != lastKey) {
            lastKey = key
            scrollX = 0
            scrollY = 0
        }
        val scale = if (fitToScreen.value && h + 8 > screenH) ((screenH - 8f) / h).coerceAtLeast(0.5f) else 1f
        drawScale = scale
        val sw = (w * scale).toInt()
        val sh = (h * scale).toInt()

        // Vanilla clamps with the unscaled size, and a too-tall one ends up past the top: start it at the top edge.
        var x = pos.x().coerceAtMost(screenW - 4 - sw).coerceAtLeast(4)
        var y = if (sh + 8 > screenH) 4 else pos.y().coerceAtMost(screenH - 4 - sh).coerceAtLeast(4)
        scrollX = scrollX.coerceIn(minOf(4, screenW - 4 - sw) - x, maxOf(4, screenW - 4 - sw) - x)
        scrollY = scrollY.coerceIn(minOf(4, screenH - 4 - sh) - y, maxOf(4, screenH - 4 - sh) - y)
        x += scrollX
        y += scrollY
        return if (x == pos.x() && y == pos.y()) pos else Vector2i(x, y)
    }

    /** True when a tooltip is showing and the wheel went to it. Wheel down = reveal more below (tooltip moves up). */
    private fun scroll(amount: Double): Boolean {
        if (!isEnabled || System.currentTimeMillis() - lastTooltipAt > 100) return false
        val step = (amount * speed.value).toInt()
        if (mc.hasControlDown()) scrollX += step else scrollY += step
        return true
    }
}
