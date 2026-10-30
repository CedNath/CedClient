package ced.cedclient.ui

import ced.cedclient.utils.Keys
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.state.UpdateChecker
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.clickGuiScale
import ced.cedclient.utils.ui.mouseX
import ced.cedclient.utils.ui.mouseY
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import kotlin.concurrent.thread

/**
 * "CedClient X is available" with the release notes and Update / Open GitHub page / Remind me later / Skip this
 * version, drawn with NanoVG in the ClickGUI popup style (same units and scale as the ClickGUI). Opened by
 * [UpdateChecker] on the title screen; every button except Update goes back to [parent].
 */
class UpdateScreen(private val parent: Screen?, private val release: UpdateChecker.Release) :
    Screen(Component.literal("CedClient Update")) {

    private class Line(val text: String, val color: Int, val heading: Boolean)
    private class Btn(val label: () -> String, val enabled: () -> Boolean, val action: () -> Unit) {
        var x = 0f; var y = 0f; var w = 0f
    }

    private var scroll = 0f
    private var status: Pair<String, Int>? = null
    private var progress = -2f // -2 idle, -1 downloading (unknown size), 0..1 downloading, 2 done

    private var lines: List<Line> = emptyList()
    private var wrappedFor = -1f

    private val buttons = listOf(
        Btn({ updateLabel() }, { progress == -2f }) { onUpdate() },
        Btn({ "Open GitHub page" }, { true }) { com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(release.pageUrl)) },
        Btn({ "Remind me later" }, { true }) { onClose() },
        Btn({ "Skip this version" }, { true }) { UpdateChecker.skip(release.version); onClose() },
    )

    // Layout, in ClickGUI units (window pixels / clickGuiScale), recomputed every frame.
    private var px = 0f; private var py = 0f; private var pw = 0f; private var ph = 0f
    private var notesTop = 0f; private var notesBottom = 0f

    private val font get() = NVGRenderer.defaultFont

    private fun updateLabel() = when {
        progress == 2f -> "Restart to finish"
        progress > -2f -> "Downloading..."
        UpdateChecker.canInstall -> "Update"
        else -> "Download"
    }

    private fun onUpdate() {
        // No jar to replace (dev environment) or no matching download: the page is the best we can do.
        if (!UpdateChecker.canInstall) {
            com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(release.pageUrl))
            return
        }
        UpdateChecker.handled = true
        progress = -1f
        thread(name = "CedClient update download", isDaemon = true) {
            val error = UpdateChecker.install { p -> minecraft.execute { progress = p } }
            minecraft.execute {
                if (error == null) {
                    progress = 2f
                    status = "Downloaded. Close Minecraft and start it again to finish updating." to Colors.MINECRAFT_GREEN.rgba
                } else {
                    progress = -2f
                    status = error to Colors.MINECRAFT_RED.rgba
                }
            }
        }
    }

    /** Release notes (GitHub markdown) wrapped to [width]: headings in the accent colour, bullets, no ** / `. */
    private fun wrapNotes(width: Float): List<Line> {
        val out = mutableListOf<Line>()
        for (raw in release.notes.replace("\r", "").lines()) {
            val line = raw.replace("**", "").replace("`", "").trimEnd()
            when {
                line.isBlank() -> out += Line("", 0, false)
                line.startsWith("#") -> wrap(line.trimStart('#', ' '), width, HEADING_SIZE).forEach { out += Line(it, ClickGUI.clickGUIColor.rgba, true) }
                line.trimStart().startsWith("- ") -> wrap(line.trimStart().removePrefix("- "), width - 12f, TEXT_SIZE)
                    .forEachIndexed { i, part -> out += Line((if (i == 0) "•  " else "    ") + part, TEXT_COLOR, false) }
                else -> wrap(line, width, TEXT_SIZE).forEach { out += Line(it, TEXT_COLOR, false) }
            }
        }
        return out
    }

    private fun wrap(text: String, width: Float, size: Float): List<String> {
        val result = mutableListOf<String>()
        var current = ""
        for (word in text.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && NVGRenderer.textWidth(candidate, size, font) > width) {
                result += current
                current = word
            } else current = candidate
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private fun layout() {
        val sw = NVGRenderer.canvasWidth / clickGuiScale
        val sh = NVGRenderer.canvasHeight / clickGuiScale
        pw = minOf(820f, sw - 60f)
        ph = minOf(900f, sh - 60f)
        px = (sw - pw) / 2f
        py = (sh - ph) / 2f
        notesTop = py + 84f
        notesBottom = py + ph - 2 * BUTTON_H - 34f
        val bw = (pw - 2 * PAD - BUTTON_GAP) / 2f
        buttons.forEachIndexed { i, b ->
            b.w = bw
            b.x = px + PAD + (i % 2) * (bw + BUTTON_GAP)
            b.y = notesBottom + 12f + (i / 2) * (BUTTON_H + BUTTON_GAP)
        }
        val wrapWidth = pw - 2 * PAD - 32f
        if (wrapWidth != wrappedFor) {
            wrappedFor = wrapWidth
            lines = wrapNotes(wrapWidth)
        }
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {
        super.extractRenderState(g, mx, my, delta)
        NVGSpecialRenderer.draw(g, 0, 0, g.guiWidth(), g.guiHeight(), renderContent = {
            NVGRenderer.scale(clickGuiScale, clickGuiScale)
            layout()
            val mouseXs = mouseX / clickGuiScale
            val mouseYs = mouseY / clickGuiScale

            NVGRenderer.dropShadow(px, py, pw, ph, 10f, 3f, 5f)
            NVGRenderer.rect(px, py, pw, ph, Colors.gray26.rgba, 5f)
            NVGRenderer.hollowRect(px, py, pw, ph, 1.5f, ClickGUI.clickGUIColor.rgba, 5f)

            NVGRenderer.text("CedClient ${release.version} is available", px + PAD, py + 14f, 24f, Colors.WHITE.rgba, font)
            NVGRenderer.text("You have ${UpdateChecker.currentVersion}", px + PAD, py + 44f, TEXT_SIZE, Colors.MINECRAFT_GRAY.rgba, font)
            val bar = status ?: when {
                progress in 0f..1f -> "Downloading... ${(progress * 100).toInt()}%" to Colors.WHITE.rgba
                progress == -1f -> "Downloading..." to Colors.WHITE.rgba
                else -> null
            }
            bar?.let { (text, color) ->
                val w = NVGRenderer.textWidth(text, TEXT_SIZE, font)
                NVGRenderer.text(text, px + pw - PAD - w, py + 44f, TEXT_SIZE, color, font)
            }
            if (progress in 0f..1f) {
                NVGRenderer.rect(px + PAD, py + 68f, pw - 2 * PAD, 3f, Colors.gray38.rgba, 1.5f)
                NVGRenderer.rect(px + PAD, py + 68f, (pw - 2 * PAD) * progress, 3f, ClickGUI.clickGUIColor.rgba, 1.5f)
            }

            // Notes: rounded inner box, clipped and scrollable.
            NVGRenderer.rect(px + PAD, notesTop, pw - 2 * PAD, notesBottom - notesTop, Colors.gray38.rgba, 4f)
            val contentH = lines.sumOf { lineHeight(it).toDouble() }.toFloat() + 16f
            val viewH = notesBottom - notesTop
            scroll = scroll.coerceIn((viewH - contentH).coerceAtMost(0f), 0f)
            NVGRenderer.pushScissor(px + PAD, notesTop, pw - 2 * PAD, viewH)
            var y = notesTop + 8f + scroll
            for (line in lines) {
                if (line.text.isNotEmpty() && y + lineHeight(line) > notesTop && y < notesBottom) {
                    NVGRenderer.text(line.text, px + PAD + 8f, y, if (line.heading) HEADING_SIZE else TEXT_SIZE, line.color, font)
                }
                y += lineHeight(line)
            }
            NVGRenderer.popScissor()
            if (contentH > viewH) {
                val thumbH = (viewH * viewH / contentH).coerceAtLeast(20f)
                val pct = (scroll / (viewH - contentH)).coerceIn(0f, 1f)
                NVGRenderer.rect(px + pw - PAD - 7f, notesTop + (viewH - thumbH) * pct, 4f, thumbH, ClickGUI.clickGUIColor.rgba, 2f)
            }

            for (b in buttons) {
                val enabled = b.enabled()
                val hovered = enabled && mouseXs in b.x..(b.x + b.w) && mouseYs in b.y..(b.y + BUTTON_H)
                NVGRenderer.rect(b.x, b.y, b.w, BUTTON_H, (if (hovered) ClickGUI.clickGUIColor else Colors.gray38).rgba, 4f)
                val label = b.label()
                val lw = NVGRenderer.textWidth(label, 16f, font)
                val color = if (enabled) Colors.WHITE.rgba else Colors.MINECRAFT_GRAY.rgba
                NVGRenderer.text(label, b.x + b.w / 2f - lw / 2f, b.y + BUTTON_H / 2f - 8f, 16f, color, font)
            }
        })
    }

    private fun lineHeight(line: Line) = when {
        line.text.isEmpty() -> 8f
        line.heading -> HEADING_SIZE + 6f
        else -> TEXT_SIZE + 4f
    }

    override fun mouseClicked(event: MouseButtonEvent, focused: Boolean): Boolean {
        if (Keys.mouse(event) != 0) return super.mouseClicked(event, focused)
        val x = mouseX / clickGuiScale
        val y = mouseY / clickGuiScale
        for (b in buttons) {
            if (b.enabled() && x in b.x..(b.x + b.w) && y in b.y..(b.y + BUTTON_H)) {
                b.action()
                return true
            }
        }
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        scroll += (scrollY * 30).toFloat()
        return true
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        // Buttons and Escape: done for this launch. (Another mod replacing this screen doesn't come through here.)
        UpdateChecker.handled = true
        minecraft.gui.setScreen(parent)
    }

    companion object {
        private const val PAD = 16f
        private const val TEXT_SIZE = 17f
        private const val HEADING_SIZE = 21f
        private const val BUTTON_H = 32f
        private const val BUTTON_GAP = 8f
        private val TEXT_COLOR = 0xFFD0D0D8.toInt()
    }
}
