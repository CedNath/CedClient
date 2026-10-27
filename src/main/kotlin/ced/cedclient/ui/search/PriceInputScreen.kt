package ced.cedclient.ui.search

import ced.cedclient.utils.textOf
import ced.cedclient.utils.signSlot
import ced.cedclient.utils.Keys
import ced.cedclient.features.impl.misc.AuctionPriceInput
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.utils.Color.Companion.withAlpha
import ced.cedclient.utils.Colors
import ced.cedclient.utils.LowestBin
import ced.cedclient.utils.ui.TextInputHandler
import ced.cedclient.utils.ui.clickGuiScale
import ced.cedclient.utils.ui.mouseX as cedMouseX
import ced.cedclient.utils.ui.mouseY as cedMouseY
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.SignBlockEntity
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat

/**
 * Stands in for Hypixel's price / amount sign, same look as [SearchOverlayScreen]. Enter sends the parsed number
 * (or the raw text if it doesn't parse, so Hypixel's own words like "all" still work); Esc sends an empty sign.
 * With an AH item: shows its lowest BIN and its tooltip next to the box, and Tab / the toggle button switch undercut mode
 * (the typed amount is taken off the lowest BIN).
 */
class PriceInputScreen(
    private val sign: SignBlockEntity,
    private val front: Boolean,
    private val hint: String,
    private val item: ItemStack?,
) : Screen(Component.literal("Price Input")) {

    private val mc = Minecraft.getInstance()
    private val font get() = NVGRenderer.defaultFont
    private val scale get() = clickGuiScale * ZOOM

    private var text = AuctionPriceInput.startText
    private val input = TextInputHandler(textProvider = { text }, textSetter = { text = it.take(MAX_INPUT) })
    private var value = ""
    private val lowestBin = item?.let(LowestBin::of)
    private var undercut = lowestBin != null && AuctionPriceInput.undercutByDefault
    private var panelX = 0f
    private var panelY = 0f

    init {
        input.focus(selectAll = true)
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        NVGSpecialRenderer.draw(context, 0, 0, context.guiWidth(), context.guiHeight(), renderContent = {
            val mx = cedMouseX / scale
            val my = cedMouseY / scale
            NVGRenderer.scale(scale, scale)
            val cw = NVGRenderer.canvasWidth / scale
            val ch = NVGRenderer.canvasHeight / scale
            NVGRenderer.rect(0f, 0f, cw, ch, Colors.BLACK.withAlpha(0.45f).rgba)
            panelX = (cw - WIDTH) / 2f
            panelY = (ch - HEIGHT) / 2f
            draw(panelX, panelY, mx, my)
        })
        if (item != null && !item.isEmpty) {
            // Same layout maths as the NVG panel (canvas = real pixels), done here so it doesn't wait on the canvas.
            val guiScale = mc.window.guiScale.toFloat()
            val toGui = scale / guiScale
            val px = (context.guiWidth() * guiScale / scale - WIDTH) / 2f
            val py = (context.guiHeight() * guiScale / scale - HEIGHT) / 2f
            // The positioner adds +12 / -12 to these; +8 leaves a gap between the box and the tooltip.
            context.setTooltipForNextFrame(mc.font, item, ((px + WIDTH) * toGui).toInt() + 8, (py * toGui).toInt() + 12)
        }
    }

    private fun draw(x: Float, y: Float, mx: Float, my: Float) {
        val accent = ClickGUI.clickGUIColor
        NVGRenderer.dropShadow(x, y, WIDTH, HEIGHT, 18f, 4f, RADIUS)
        NVGRenderer.rect(x, y, WIDTH, HEIGHT, Colors.gray26.withAlpha(0.97f).rgba, RADIUS)
        NVGRenderer.hollowRect(x, y, WIDTH, HEIGHT, 1f, accent.withAlpha(0.55f).rgba, RADIUS)
        NVGRenderer.drawHalfRoundedRect(x, y, WIDTH, HEADER_H, accent.rgba, RADIUS, true)
        NVGRenderer.text(if (undercut) "Undercut lowest BIN by" else hint.ifEmpty { "Enter amount" }, x + PAD, y + 9f, 16f, Colors.WHITE.rgba, font)
        val esc = "Esc to cancel"
        NVGRenderer.text(esc, x + WIDTH - PAD - NVGRenderer.textWidth(esc, 11f, font), y + 12f, 11f, Colors.WHITE.withAlpha(0.7f).rgba, font)

        val fy = y + HEADER_H + PAD
        NVGRenderer.rect(x + PAD, fy, WIDTH - PAD * 2, FIELD_H, 0xFF141414.toInt(), 6f)
        NVGRenderer.hollowRect(x + PAD, fy, WIDTH - PAD * 2, FIELD_H, 1.5f, accent.rgba, 6f)
        if (text.isEmpty()) NVGRenderer.text("e.g. 1.5m, 250k, 2b", x + PAD + 10f, fy + 8f, 14f, Colors.MINECRAFT_DARK_GRAY.rgba, font)
        input.x = x + PAD + 6f
        input.y = fy + 6f
        input.width = WIDTH - PAD * 2 - 12f
        input.height = FIELD_H - 12f
        input.draw(mx, my)

        val result = result()
        when {
            text.isBlank() -> {}
            result != null -> NVGRenderer.text("= ${GROUPED.format(result)}", x + PAD, fy + FIELD_H + 10f, 14f, Colors.MINECRAFT_GREEN.rgba, font)
            else -> NVGRenderer.text("Not a number - Enter sends it as typed", x + PAD, fy + FIELD_H + 10f, 14f, Colors.MINECRAFT_RED.rgba, font)
        }
        if (lowestBin != null) {
            NVGRenderer.text("Lowest BIN: ${GROUPED.format(lowestBin)}", x + PAD, fy + FIELD_H + 30f, 14f, Colors.MINECRAFT_GOLD.rgba, font)
            // Undercut toggle: click it or press Tab.
            val label = if (undercut) "Undercut: On (Tab)" else "Undercut: Off (Tab)"
            toggleW = NVGRenderer.textWidth(label, 11f, font) + 16f
            toggleX = x + WIDTH - PAD - toggleW
            toggleY = fy + FIELD_H + 27f
            val hovered = overToggle(mx, my)
            NVGRenderer.rect(toggleX, toggleY, toggleW, TOGGLE_H, (if (undercut) accent.withAlpha(if (hovered) 0.85f else 0.65f) else Colors.WHITE.withAlpha(if (hovered) 0.18f else 0.1f)).rgba, 5f)
            NVGRenderer.text(label, toggleX + 8f, toggleY + 5f, 11f, Colors.WHITE.rgba, font)
        }
    }

    private var toggleX = 0f
    private var toggleY = 0f
    private var toggleW = 0f

    private fun overToggle(mx: Float, my: Float) =
        lowestBin != null && mx in toggleX..toggleX + toggleW && my in toggleY..toggleY + TOGGLE_H

    /** The number that gets sent: typed amount, or lowest BIN minus it in undercut mode. */
    private fun result(): BigDecimal? {
        val parsed = AuctionPriceInput.parse(text) ?: return null
        val lbin = lowestBin ?: return parsed
        return if (undercut) BigDecimal.valueOf(lbin).subtract(parsed).max(BigDecimal.ZERO).setScale(0, RoundingMode.DOWN) else parsed
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        when (keyEvent.key) {
            Keys.ESCAPE -> { onClose(); return true }
            Keys.TAB -> { if (lowestBin != null) undercut = !undercut; return true }
            Keys.ENTER, Keys.KP_ENTER -> {
                value = result()?.toPlainString() ?: text.trim()
                AuctionPriceInput.onSubmit(text.trim())
                onClose()
                return true
            }
        }
        if (input.keyPressed(keyEvent)) return true
        return super.keyPressed(keyEvent)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        if (input.keyTyped(characterEvent)) return true
        return super.charTyped(characterEvent)
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        if (mouseButtonEvent.button() == Keys.MOUSE_LEFT && overToggle(cedMouseX / scale, cedMouseY / scale)) {
            undercut = !undercut
            input.focus()
            return true
        }
        input.mouseClicked(cedMouseX / scale, cedMouseY / scale, mouseButtonEvent)
        input.focus()
        return true
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        input.mouseReleased()
        return true
    }

    override fun removed() {
        val lines = sign.textOf(front).getMessages(false)
        mc.connection?.send(ServerboundSignUpdatePacket(sign.blockPos, listOf(value.take(MAX_INPUT), lines[1].string, lines[2].string, lines[3].string), signSlot(front)))
    }

    override fun isPauseScreen(): Boolean = false

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {}

    companion object {
        private const val ZOOM = 1.45f
        private const val WIDTH = 300f
        private const val RADIUS = 8f
        private const val PAD = 14f
        private const val HEADER_H = 34f
        private const val FIELD_H = 32f
        private const val TOGGLE_H = 20f
        private const val HEIGHT = HEADER_H + PAD + FIELD_H + 58f
        // A sign line holds 15 characters; Hypixel reads the input from the first line only.
        private const val MAX_INPUT = 15
        private val GROUPED = DecimalFormat("#,##0.##")
    }
}
