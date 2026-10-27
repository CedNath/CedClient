package ced.cedclient.ui.search

import ced.cedclient.utils.Keys
import ced.cedclient.features.impl.misc.SearchOverlay
import ced.cedclient.render.nvg.Gradient
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.utils.Color
import ced.cedclient.utils.Color.Companion.withAlpha
import ced.cedclient.utils.Colors
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

/**
 * Stands in for Hypixel's "Enter query" sign, drawn in the ClickGUI's style (NVG, accent colour).
 * Whatever is in [query] when the screen goes away is sent back as the sign's text (empty on Esc,
 * same as closing the vanilla sign blank).
 *
 * Layout is in canvas units (raw pixels / [scale]), like CosmeticPresetScreen but zoomed; item icons
 * are drawn with vanilla afterwards, converted to GUI units.
 */
class SearchOverlayScreen(
    private val sign: SignBlockEntity,
    private val front: Boolean,
    private val location: SearchOverlay.Location,
) : Screen(Component.literal("Search")) {

    private val mc = Minecraft.getInstance()
    private val font get() = NVGRenderer.defaultFont

    // ClickGUI scale times our own zoom; all layout below is in these units.
    private val scale get() = clickGuiScale * ZOOM

    private var text = ""
    private val input = TextInputHandler(textProvider = { text }, textSetter = {
        text = it.take(MAX_QUERY)
        refresh()
    })

    private class Row(val text: String, val display: String, val entry: SearchOverlay.Entry?, val recent: Boolean)

    private var rows: List<Row> = emptyList()
    private var selected = 0
    private var query = ""

    // Layout, canvas units, set every frame.
    private var x = 0f
    private var y = 0f
    private var listY = 0f
    private var animY = -1f
    private val openedAt = System.nanoTime()

    init {
        input.focus()
        refresh()
    }

    private fun refresh() {
        rows = if (text.isBlank()) {
            SearchOverlay.history(location).map { h ->
                Row(h, h, SearchOverlay.suggest(h, location, 1).firstOrNull { it.name.equals(h, true) }, true)
            }
        } else {
            SearchOverlay.suggest(text, location, MAX_ROWS).map { Row(it.name, it.display, it, false) }
        }
        selected = 0
    }

    // ---------------------------------------------------------------- render

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val t = (System.nanoTime() - openedAt) / 1_000_000f
        val p = easeOutCubic(t / 180f)
        NVGSpecialRenderer.draw(context, 0, 0, context.guiWidth(), context.guiHeight(), renderContent = {
            val mx = cedMouseX / scale
            val my = cedMouseY / scale
            NVGRenderer.scale(scale, scale)
            val cw = NVGRenderer.canvasWidth / scale
            val ch = NVGRenderer.canvasHeight / scale

            NVGRenderer.rect(0f, 0f, cw, ch, Colors.BLACK.withAlpha(0.45f * easeOutCubic(t / 160f)).rgba)
            x = (cw - WIDTH) / 2f
            // Centred on the tallest the panel gets (full list), so the top doesn't jump while typing.
            y = (ch - maxPanelHeight()) / 2f + (1f - p) * 12f
            NVGRenderer.push()
            NVGRenderer.globalAlpha(p)
            draw(mx, my)
            NVGRenderer.pop()
        })
        if (p >= 0.99f && NVGRenderer.canvasWidth > 0f) {
            context.nextStratum()
            drawIcons(context)
        }
    }

    private fun maxPanelHeight(): Float = HEADER_H + PAD + FIELD_H + 26f + MAX_ROWS * ROW_H + FOOTER_H

    private fun panelHeight(): Float = HEADER_H + PAD + FIELD_H + 26f + listHeight() + FOOTER_H

    private fun listHeight(): Float = if (rows.isEmpty()) 44f else rows.size * ROW_H

    private fun draw(mx: Float, my: Float) {
        val accent = ClickGUI.clickGUIColor
        val h = panelHeight()

        NVGRenderer.dropShadow(x, y, WIDTH, h, 18f, 4f, RADIUS)
        NVGRenderer.rect(x, y, WIDTH, h, Colors.gray26.withAlpha(0.97f).rgba, RADIUS)
        NVGRenderer.hollowRect(x, y, WIDTH, h, 1f, accent.withAlpha(0.55f).rgba, RADIUS)

        // Header: accent strip with a soft fade into the panel.
        NVGRenderer.drawHalfRoundedRect(x, y, WIDTH, HEADER_H, accent.rgba, RADIUS, true)
        NVGRenderer.gradientRect(x, y + HEADER_H, WIDTH, 10f, accent.withAlpha(0.25f).rgba, Colors.TRANSPARENT.rgba, Gradient.TopToBottom, 0f)
        val title = if (location == SearchOverlay.Location.BAZAAR) "Bazaar Search" else "Auction House Search"
        NVGRenderer.text(title, x + PAD, y + 9f, 16f, Colors.WHITE.rgba, font)
        val esc = "Esc to cancel"
        NVGRenderer.text(esc, x + WIDTH - PAD - NVGRenderer.textWidth(esc, 11f, font), y + 12f, 11f, Colors.WHITE.withAlpha(0.7f).rgba, font)

        // Search field
        val fy = y + HEADER_H + PAD
        NVGRenderer.rect(x + PAD, fy, WIDTH - PAD * 2, FIELD_H, 0xFF141414.toInt(), 6f)
        NVGRenderer.hollowRect(x + PAD, fy, WIDTH - PAD * 2, FIELD_H, 1.5f, accent.rgba, 6f)
        magnifier(x + PAD + 14f, fy + FIELD_H / 2f, Colors.MINECRAFT_GRAY.rgba)
        val fieldX = x + PAD + 26f
        if (text.isEmpty()) {
            NVGRenderer.text("Search items...", fieldX + 4f, fy + 8f, 14f, Colors.MINECRAFT_DARK_GRAY.rgba, font)
        }
        input.x = fieldX
        input.y = fy + 6f
        input.width = WIDTH - PAD * 2 - 34f
        input.height = FIELD_H - 12f
        input.draw(mx, my)

        // Section label
        val ly = fy + FIELD_H + 10f
        NVGRenderer.text(if (text.isBlank()) "RECENT SEARCHES" else "SUGGESTIONS", x + PAD, ly, 10f, accent.rgba, font)
        if (rows.isNotEmpty()) {
            val count = "${rows.size}"
            NVGRenderer.text(count, x + WIDTH - PAD - NVGRenderer.textWidth(count, 10f, font), ly, 10f, Colors.MINECRAFT_DARK_GRAY.rgba, font)
        }

        listY = ly + 16f
        if (rows.isEmpty()) {
            val msg = when {
                text.isBlank() -> "Start typing to search"
                !SearchOverlay.hasItems(location) -> "No item repo found - install SkyHanni"
                else -> "No matches - Enter searches \"${text.trim()}\" anyway"
            }
            NVGRenderer.text(msg, x + WIDTH / 2f - NVGRenderer.textWidth(msg, 13f, font) / 2f, listY + 14f, 13f, Colors.MINECRAFT_GRAY.rgba, font)
        } else {
            drawRows(mx, my, accent)
        }

        drawFooter(y + h - FOOTER_H)
    }

    private fun drawRows(mx: Float, my: Float, accent: Color) {
        if (moved(mx, my)) {
            rows.indices.firstOrNull { hovered(x + PAD, listY + it * ROW_H, WIDTH - PAD * 2, ROW_H, mx, my) }?.let { selected = it }
        }
        // Selection highlight slides between rows.
        val targetY = listY + selected * ROW_H
        animY = if (animY < 0f) targetY else animY + (targetY - animY) * 0.35f
        NVGRenderer.rect(x + PAD, animY, WIDTH - PAD * 2, ROW_H - 2f, accent.withAlpha(0.22f).rgba, 6f)
        NVGRenderer.rect(x + PAD, animY + 5f, 3f, ROW_H - 12f, accent.rgba, 1.5f)

        val q = text.trim().lowercase()
        rows.forEachIndexed { i, row ->
            val ry = listY + i * ROW_H
            NVGRenderer.rect(x + PAD + 10f, ry + 3f, ICON_BOX, ICON_BOX, 0xFF1F1F1F.toInt(), 5f)
            if (row.entry == null) NVGRenderer.circle(x + PAD + 10f + ICON_BOX / 2f, ry + 3f + ICON_BOX / 2f, 3f, accent.rgba)

            val name = row.text
            val tx = x + PAD + 10f + ICON_BOX + 10f
            val ty = ry + (ROW_H - 2f) / 2f - 7f
            val idx = if (q.isEmpty()) -1 else name.lowercase().indexOf(q)
            if (idx >= 0) {
                val hx = tx + NVGRenderer.textWidth(name.substring(0, idx), 14f, font)
                val hw = NVGRenderer.textWidth(name.substring(idx, idx + q.length), 14f, font)
                NVGRenderer.rect(hx - 1f, ty - 1f, hw + 2f, 16f, accent.withAlpha(0.35f).rgba, 3f)
            }
            NVGRenderer.text(name, tx, ty, 14f, if (row.entry == null) Colors.WHITE.rgba else nameColor(row.display), font)

            val tag = if (row.recent) "recent" else if (i == selected) "Enter" else ""
            if (tag.isNotEmpty()) {
                val tw = NVGRenderer.textWidth(tag, 10f, font)
                NVGRenderer.text(tag, x + WIDTH - PAD - 10f - tw, ry + (ROW_H - 2f) / 2f - 5f, 10f, Colors.MINECRAFT_DARK_GRAY.rgba, font)
            }
        }
    }

    private fun drawFooter(fy: Float) {
        NVGRenderer.line(x + PAD, fy, x + WIDTH - PAD, fy, 1f, Colors.gray38.rgba)
        var kx = x + PAD
        for ((key, action) in listOf("Up/Down" to "select", "Tab" to "complete", "Enter" to "search")) {
            val kw = NVGRenderer.textWidth(key, 10f, font) + 10f
            NVGRenderer.rect(kx, fy + 9f, kw, 15f, Colors.gray38.rgba, 4f)
            NVGRenderer.text(key, kx + 5f, fy + 12f, 10f, Colors.WHITE.rgba, font)
            kx += kw + 5f
            NVGRenderer.text(action, kx, fy + 12f, 10f, Colors.MINECRAFT_GRAY.rgba, font)
            kx += NVGRenderer.textWidth(action, 10f, font) + 14f
        }
    }

    /** Ring + handle, since the NVG font has no magnifier glyph. */
    private fun magnifier(cx: Float, cy: Float, color: Int) {
        NVGRenderer.hollowRect(cx - 6f, cy - 6f, 9f, 9f, 1.6f, color, 4.5f)
        NVGRenderer.line(cx + 2f, cy + 2f, cx + 6f, cy + 6f, 1.8f, color)
    }

    private fun drawIcons(g: GuiGraphicsExtractor) {
        val toGui = scale * g.guiWidth() / NVGRenderer.canvasWidth
        val pad = (ICON_BOX - ICON) / 2f
        rows.forEachIndexed { i, row ->
            val entry = row.entry ?: return@forEachIndexed
            icon(g, SearchOverlay.icon(entry), x + PAD + 10f + pad, listY + i * ROW_H + 3f + pad, toGui)
        }
    }

    private fun icon(g: GuiGraphicsExtractor, stack: ItemStack, px: Float, py: Float, toGui: Float) {
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(px * toGui, py * toGui)
        val s = ICON * toGui / 16f
        pose.scale(s, s)
        g.fakeItem(stack, 0, 0)
        pose.popMatrix()
    }

    /** Rarity colour from the item's leading colour code (§6 -> gold), white if none. */
    private fun nameColor(display: String): Int {
        val code = Regex("§([0-9a-f])").find(display)?.groupValues?.get(1)?.first() ?: return Colors.WHITE.rgba
        return FORMAT_COLORS[code.digitToInt(16)] or 0xFF000000.toInt()
    }

    // ---------------------------------------------------------------- input

    private var lastMx = Float.NaN
    private var lastMy = Float.NaN

    /** Hover only takes the selection once the mouse actually moves, so arrow keys aren't overridden. */
    private fun moved(mx: Float, my: Float): Boolean {
        val m = mx != lastMx || my != lastMy
        lastMx = mx; lastMy = my
        return m
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        when (keyEvent.key) {
            // TextInputHandler would only unfocus on Esc; close straight away instead.
            Keys.ESCAPE -> { onClose(); return true }
            Keys.DOWN -> { if (rows.isNotEmpty()) selected = (selected + 1) % rows.size; return true }
            Keys.UP -> { if (rows.isNotEmpty()) selected = (selected - 1 + rows.size) % rows.size; return true }
            Keys.TAB -> {
                rows.getOrNull(selected)?.let { text = it.text; refresh(); input.focus() }
                return true
            }
            Keys.ENTER, Keys.KP_ENTER -> { submit(rows.getOrNull(selected)?.text ?: text); return true }
        }
        if (input.keyPressed(keyEvent)) return true
        return super.keyPressed(keyEvent)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        if (input.keyTyped(characterEvent)) return true
        return super.charTyped(characterEvent)
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val mx = cedMouseX / scale
        val my = cedMouseY / scale
        rows.indices.firstOrNull { hovered(x + PAD, listY + it * ROW_H, WIDTH - PAD * 2, ROW_H, mx, my) }?.let {
            submit(rows[it].text)
            return true
        }
        input.mouseClicked(mx, my, mouseButtonEvent)
        input.focus()
        return true
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        input.mouseReleased()
        return true
    }

    private fun hovered(hx: Float, hy: Float, w: Float, h: Float, mx: Float, my: Float) =
        mx in hx..(hx + w) && my in hy..(hy + h)

    private fun submit(value: String) {
        query = value.trim().take(MAX_QUERY)
        if (query.isNotEmpty()) SearchOverlay.remember(query, location)
        onClose()
    }

    override fun removed() {
        val (first, second) = splitLines(query)
        val lines = sign.getText(front).getMessages(false)
        mc.connection?.send(ServerboundSignUpdatePacket(sign.blockPos, front, first, second, lines[2].string, lines[3].string))
    }

    override fun isPauseScreen(): Boolean = false

    /** No vanilla blur; the dim in extractRenderState is drawn instead (same as the ClickGUI). */
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {}

    private fun easeOutCubic(v: Float): Float {
        val inv = 1f - v.coerceIn(0f, 1f)
        return 1f - inv * inv * inv
    }

    companion object {
        private const val ZOOM = 1.45f
        private const val WIDTH = 360f
        private const val RADIUS = 8f
        private const val PAD = 14f
        private const val HEADER_H = 34f
        private const val FIELD_H = 32f
        private const val ROW_H = 30f
        private const val ICON_BOX = 24f
        private const val ICON = 18f
        private const val FOOTER_H = 32f
        private const val MAX_ROWS = 8

        // Vanilla §0-§f colours; ChatFormatting stopped exposing them in 26.2.
        private val FORMAT_COLORS = intArrayOf(
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
        )

        private const val LINE = 15
        private const val MAX_QUERY = LINE * 2

        /** Hypixel reads the query from the sign's first two lines; split on a space when it doesn't fit one. */
        fun splitLines(q: String): Pair<String, String> {
            if (q.length <= LINE) return q to ""
            val cut = q.lastIndexOf(' ', LINE).takeIf { it > 0 } ?: LINE
            return q.substring(0, cut).trim() to q.substring(cut).trim().take(LINE)
        }
    }
}
