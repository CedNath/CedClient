package ced.cedclient.features.impl.misc

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.ModuleManager
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.Color.Companion.withAlpha
import ced.cedclient.utils.Colors
import ced.cedclient.utils.Debug
import ced.cedclient.utils.ui.clickGuiScale
import ced.cedclient.utils.ui.TextInputHandler
import ced.cedclient.utils.ui.mouseX
import ced.cedclient.utils.ui.mouseY
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.sign

/** Button: opens the in-game debug log (also /cc debug panel). */
object DebugPanel : Module("Debug Panel", Category.Misc, "In-game log of what each feature detected and decided, with export.") {
    override val isAction = true

    override fun onAction() {
        val mc = Minecraft.getInstance()
        mc.execute { mc.gui.setScreen(DebugScreen()) }
    }
}

/**
 * Every [Debug.line] the features wrote, newest first, filterable by module tag (click a chip), with Copy (what is
 * shown), Export (a diagnostics file: versions, enabled modules and the log) and Clear.
 */
class DebugScreen : Screen(Component.literal("Debug Panel")) {
    private class Hit(val x: Float, val y: Float, val w: Float, val h: Float, val click: () -> Unit)

    private val hits = ArrayList<Hit>()
    private var scroll = 0f
    private var contentH = 0f
    private var viewH = 0f
    private var tag: String? = null
    private var status = ""
    private var query = ""
    private val input = TextInputHandler(textProvider = { query }, textSetter = { query = it.take(40); scroll = 0f })
    private val font get() = NVGRenderer.defaultFont
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        NVGSpecialRenderer.draw(context, 0, 0, context.guiWidth(), context.guiHeight(), renderContent = {
            NVGRenderer.scale(clickGuiScale, clickGuiScale)
            val cw = NVGRenderer.canvasWidth / clickGuiScale
            val ch = NVGRenderer.canvasHeight / clickGuiScale
            NVGRenderer.rect(0f, 0f, cw, ch, Colors.BLACK.withAlpha(0.35f).rgba)
            draw(cw, ch)
        })
        super.extractRenderState(context, mouseX, mouseY, deltaTicks)
    }

    private fun shown() = Debug.lines().asReversed().filter {
        (tag == null || it.tag == tag) &&
            (query.isBlank() || it.tag.contains(query, true) || it.msg.contains(query, true))
    }

    private fun fit(text: String, size: Float, maxW: Float): String {
        if (NVGRenderer.textWidth(text, size, font) <= maxW) return text
        var n = text.length
        while (n > 1 && NVGRenderer.textWidth(text.take(n) + "…", size, font) > maxW) n--
        return text.take(n) + "…"
    }

    private fun tagColor(t: String): Int {
        val palette = intArrayOf(0xFF55FFFF.toInt(), 0xFFFFAA00.toInt(), 0xFF55FF55.toInt(), 0xFFFF77FF.toInt(), 0xFF77AAFF.toInt(), 0xFFFFFF55.toInt())
        return palette[Math.floorMod(t.hashCode(), palette.size)]
    }

    private fun button(label: String, rightEdge: Float, y: Float, click: () -> Unit): Float {
        val w = NVGRenderer.textWidth(label, 11f, font) + 16f
        val x = rightEdge - w
        NVGRenderer.rect(x, y, w, 20f, Colors.gray38.rgba, 4f)
        NVGRenderer.text(label, x + 8f, y + 5f, 11f, Colors.WHITE.rgba, font)
        hits += Hit(x, y, w, 20f, click)
        return x - 6f
    }

    private fun draw(cw: Float, ch: Float) {
        hits.clear()
        val w = minOf(900f, cw - 20f)
        val h = minOf(760f, ch - 20f)
        val x = (cw - w) / 2f
        val y = (ch - h) / 2f
        NVGRenderer.dropShadow(x, y, w, h, 10f, 3f, 5f)
        NVGRenderer.rect(x, y, w, h, Colors.gray26.rgba, 5f)
        NVGRenderer.hollowRect(x, y, w, h, 1.5f, ClickGUI.clickGUIColor.rgba, 5f)
        NVGRenderer.text("Debug Panel", x + 16f, y + 12f, 16f, Colors.WHITE.rgba, font)

        var bx = x + w - 14f
        bx = button("Clear", bx, y + 9f) { Debug.clear(); scroll = 0f; status = "Cleared." }
        bx = button("Export", bx, y + 9f) { status = export() }
        bx = button("Copy", bx, y + 9f) { status = copyShown() }

        // Search: narrows the module chips and the lines (module name or message).
        val sx = x + 130f
        val sw = bx - sx - 6f
        NVGRenderer.rect(sx, y + 10f, sw, 18f, Colors.INPUT_BG.rgba, 3f)
        NVGRenderer.hollowRect(sx, y + 10f, sw, 18f, 1f, Colors.INPUT_BORDER.rgba, 3f)
        input.x = sx; input.y = y + 10f; input.width = sw; input.height = 18f
        input.draw(mouseX / clickGuiScale, mouseY / clickGuiScale)
        if (query.isEmpty() && !input.isActive) NVGRenderer.text("Search modules...", sx + 5f, y + 13f, 11f, 0xFF777777.toInt(), font)

        // Tag chips: "All" then one per module that has written a line.
        var cx = x + 16f
        var cy = y + 42f
        val right = x + w - 16f
        for (t in listOf<String?>(null) + Debug.tags().filter { query.isBlank() || it.contains(query, true) }) {
            val label = t ?: "All"
            val cwid = NVGRenderer.textWidth(label, 10f, font) + 14f
            if (cx + cwid > right) { cx = x + 16f; cy += 22f }
            val on = tag == t
            NVGRenderer.rect(cx, cy, cwid, 18f, if (on) ClickGUI.clickGUIColor.rgba else Colors.gray38.rgba, 9f)
            NVGRenderer.text(label, cx + 7f, cy + 4f, 10f, if (on) Colors.WHITE.rgba else (t?.let { tagColor(it) } ?: Colors.WHITE.rgba), font)
            val picked = t
            hits += Hit(cx, cy, cwid, 18f) { tag = picked; scroll = 0f }
            cx += cwid + 6f
        }

        val top = cy + 28f
        viewH = y + h - top - 24f
        val rows = shown()
        val rowH = 18f
        contentH = rows.size * rowH
        scroll = scroll.coerceIn(-maxOf(0f, contentH - viewH), 0f)
        if (rows.isEmpty()) {
            val msg = "No debug lines yet - features write here as they detect and decide things."
            NVGRenderer.text(msg, x + 16f, top + 8f, 11f, 0xFF999999.toInt(), font)
        }
        NVGRenderer.pushScissor(x, top, w, viewH)
        val first = (-scroll / rowH).toInt().coerceAtLeast(0)
        val last = minOf(rows.size, first + (viewH / rowH).toInt() + 2)
        for (i in first until last) {
            val line = rows[i]
            val ry = top + scroll + i * rowH
            NVGRenderer.text(clock.format(Instant.ofEpochMilli(line.time)), x + 16f, ry, 12f, 0xFF9A9A9A.toInt(), font)
            val tagText = "[${line.tag}]"
            NVGRenderer.text(tagText, x + 92f, ry, 12f, tagColor(line.tag), font)
            val mx = x + 92f + NVGRenderer.textWidth(tagText, 12f, font) + 8f
            NVGRenderer.text(fit(line.msg, 12f, x + w - 16f - mx), mx, ry, 12f, 0xFFFFFFFF.toInt(), font)
        }
        NVGRenderer.popScissor()
        val footer = if (status.isNotEmpty()) status else "${rows.size} lines" + (tag?.let { " in $it" } ?: "") + " - newest first, scroll for more"
        NVGRenderer.text(footer, x + 16f, y + h - 18f, 10f, 0xFF999999.toInt(), font)
    }

    private fun report(lines: List<Debug.Line>): String {
        val sb = StringBuilder()
        val mod = FabricLoader.getInstance().getModContainer("cedclient").map { it.metadata.version.friendlyString }.orElse("?")
        val mc = FabricLoader.getInstance().getModContainer("minecraft").map { it.metadata.version.friendlyString }.orElse("?")
        sb.appendLine("CedClient $mod, Minecraft $mc")
        sb.appendLine("Enabled: " + ModuleManager.modules.filter { it.isEnabled && !it.alwaysEnabled }.joinToString(", ") { it.name })
        sb.appendLine()
        lines.forEach { sb.appendLine("${clock.format(Instant.ofEpochMilli(it.time))} [${it.tag}] ${it.msg}") }
        return sb.toString()
    }

    private fun export(): String {
        val file = CedPaths.file("debug-export.txt")
        val ok = runCatching { file.writeText(report(Debug.lines())) }.isSuccess
        val msg = if (ok) "Saved config/cedclient/${file.name}" else "Could not write ${file.name}."
        Minecraft.getInstance().player?.sendSystemMessage(ChatPrefix.msg("§7Debug export: §f$msg"))
        return msg
    }

    private fun copyShown(): String {
        val lines = shown().take(300).asReversed()
        Minecraft.getInstance().keyboardHandler.clipboard = report(lines)
        return "Copied ${lines.size} lines."
    }

    override fun isInputCaptured(): Boolean = ced.cedclient.utils.ui.TextInputHandler.anyActive

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val mx = mouseX / clickGuiScale
        val my = mouseY / clickGuiScale
        if (input.mouseClicked(mx, my, mouseButtonEvent)) return true
        hits.lastOrNull { mx >= it.x && mx <= it.x + it.w && my >= it.y && my <= it.y + it.h }?.let { it.click(); return true }
        return super.mouseClicked(mouseButtonEvent, bl)
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        input.mouseReleased()
        return super.mouseReleased(mouseButtonEvent)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean =
        input.keyTyped(characterEvent) || super.charTyped(characterEvent)

    override fun keyPressed(keyEvent: KeyEvent): Boolean =
        input.keyPressed(keyEvent) || super.keyPressed(keyEvent)

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        scroll += (verticalAmount.sign * 28).toFloat()
        return true
    }

    override fun isPauseScreen(): Boolean = false

    /** No vanilla blur behind this screen; the dim above is drawn instead (same as the other NanoVG screens). */
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {}
}
