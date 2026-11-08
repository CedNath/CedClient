package ced.cedclient.ui.clickgui

import ced.cedclient.config.ConfigManager
import ced.cedclient.features.impl.misc.Theme
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.ui.Palette
import ced.cedclient.utils.Color
import ced.cedclient.utils.Colors
import ced.cedclient.utils.Color.Companion.withAlpha
import ced.cedclient.utils.Debug
import ced.cedclient.utils.Keys
import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

/**
 * "Pick your accent": shown over the menu the first time it opens (Theme.accentChosen false) and from Mod Settings >
 * Pick Accent. Swatches only change the preview; Done saves the pick, Skip / Esc keep the accent as it is (none on a
 * first open). Both count as answered.
 * Closing it any other way (X, click outside, closing the menu) saves nothing, so it shows again next time.
 */
class AccentPickerPopup : Popup("Pick your accent", WIDTH, 470f) {
    /** Swatch being previewed; -1 = the player's own custom accent (no swatch matches it), kept by Done. */
    private var picked = Palette.SWATCHES.indexOfFirst { it.second == Theme.accent.value.rgba }

    init { Debug.line("Accent Picker", "shown (accent chosen = ${Theme.accentChosen.value})") }

    private val font get() = NVGRenderer.defaultFont
    private val swatchColor get() = if (picked >= 0) Palette.SWATCHES[picked].second else Theme.accent.value.rgba

    private fun swatchCenter(i: Int): Pair<Float, Float> {
        val total = Palette.SWATCHES.size * SWATCH + (Palette.SWATCHES.size - 1) * SWATCH_GAP
        return x + (width - total) / 2f + i * (SWATCH + SWATCH_GAP) + SWATCH / 2f to y + 112f + SWATCH / 2f
    }

    private val doneX get() = x + width - PAD - BUTTON_W
    private val skipX get() = doneX - 12f - BUTTON_W
    private val buttonY get() = y + height - PAD - BUTTON_H

    private fun over(bx: Float, by: Float, bw: Float, bh: Float, mx: Float, my: Float) = mx in bx..(bx + bw) && my in by..(by + bh)

    private fun centered(text: String, cx: Float, ty: Float, size: Float, color: Int, f: ced.cedclient.render.font.Font = font) =
        NVGRenderer.text(text, cx - NVGRenderer.textWidth(text, size, f) / 2f, ty, size, color, f)

    override fun drawContent(mouseX: Float, mouseY: Float) {
        centered("Used for headline numbers, the dot on enabled modules and the chat prefix.", x + width / 2f, y + 46f, 15f, Palette.MUTED)
        centered("You can change it any time in Mod Settings.", x + width / 2f, y + 66f, 15f, Palette.MUTED)

        Palette.SWATCHES.forEachIndexed { i, (name, argb) ->
            val (cx, cy) = swatchCenter(i)
            val r = SWATCH / 2f
            if (i == picked) NVGRenderer.hollowRect(cx - r - 5f, cy - r - 5f, SWATCH + 10f, SWATCH + 10f, 2.5f, Palette.TEXT, r + 5f)
            NVGRenderer.circle(cx, cy, r, argb)
            centered(name, cx, cy + r + 12f, 13f, if (i == picked) Palette.TEXT else Palette.MUTED)
        }

        // Live preview: three menu rows, a HUD headline number and the chat prefix, all in the swatch being looked at.
        val px = x + PAD
        val py = y + 222f
        val pw = width - 2 * PAD
        val ph = 150f
        NVGRenderer.rect(px, py, pw, ph, Colors.WHITE.withAlpha(0.03f).rgba, 10f)
        NVGRenderer.hollowRect(px, py, pw, ph, 1f, Palette.LINE, 10f)
        val half = pw / 2f
        listOf("Dungeon Map" to true, "Terminal ESP" to false, "Secret HUD" to true).forEachIndexed { i, (name, on) ->
            val ry = py + 22f + i * 38f
            centered(name, px + half / 2f, ry, 19f, if (on) Palette.TEXT else Palette.FAINT)
            if (on) NVGRenderer.rect(px + half - 30f, ry + 6f, 8f, 8f, swatchColor, 4f)
        }
        NVGRenderer.rect(px + half, py + 18f, 1f, ph - 36f, Palette.LINE)
        val rx = px + half + 30f
        NVGRenderer.text("31", rx, py + 20f, 46f, swatchColor, NVGRenderer.boldFont)
        NVGRenderer.text("bosses / hour", rx + NVGRenderer.textWidth("31", 46f, NVGRenderer.boldFont) + 12f, py + 44f, 15f, Palette.MUTED, font)
        NVGRenderer.text("[CC]", rx, py + 100f, 16f, swatchColor, font)
        NVGRenderer.text("Ready", rx + NVGRenderer.textWidth("[CC] ", 16f, font), py + 100f, 16f, Palette.TEXT, font)

        // Skip (ghost) and Done (filled white, dark label).
        val skipHover = over(skipX, buttonY, BUTTON_W, BUTTON_H, mouseX, mouseY)
        if (skipHover) NVGRenderer.rect(skipX, buttonY, BUTTON_W, BUTTON_H, Palette.BUTTON_HOVER, 8f)
        NVGRenderer.hollowRect(skipX, buttonY, BUTTON_W, BUTTON_H, 1f, Palette.EDGE, 8f)
        centered("Skip", skipX + BUTTON_W / 2f, buttonY + BUTTON_H / 2f - 8f, 16f, Palette.TEXT, NVGRenderer.boldFont)
        val doneHover = over(doneX, buttonY, BUTTON_W, BUTTON_H, mouseX, mouseY)
        NVGRenderer.rect(doneX, buttonY, BUTTON_W, BUTTON_H, if (doneHover) Colors.WHITE.rgba else Palette.TEXT, 8f)
        centered("Done", doneX + BUTTON_W / 2f, buttonY + BUTTON_H / 2f - 8f, 16f, Palette.ON_ACCENT_DARK, NVGRenderer.boldFont)
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (Keys.mouse(click) != 0) return false
        Palette.SWATCHES.indices.firstOrNull { i ->
            val (cx, cy) = swatchCenter(i)
            val dx = mouseX - cx
            val dy = mouseY - cy
            dx * dx + dy * dy <= (SWATCH / 2f + 6f) * (SWATCH / 2f + 6f)
        }?.let { picked = it; return true }
        when {
            over(doneX, buttonY, BUTTON_W, BUTTON_H, mouseX, mouseY) -> finish(skip = false)
            over(skipX, buttonY, BUTTON_W, BUTTON_H, mouseX, mouseY) -> finish(skip = true)
            else -> return false
        }
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (input.key != Keys.ESCAPE) return false
        finish(skip = true)
        return true
    }

    private fun finish(skip: Boolean) {
        if (!skip && picked >= 0) Theme.accent.value = Color(Palette.SWATCHES[picked].second)
        Theme.accentChosen.value = true
        ConfigManager.saveModulesOnly()
        Debug.line("Accent Picker", if (skip) "skipped" else "picked ${Palette.SWATCHES.getOrNull(picked)?.first ?: "custom (kept)"}")
        (Minecraft.getInstance().gui.screen() as? ClickGUI)?.closePopup()
    }

    private companion object {
        const val PAD = 24f
        const val SWATCH = 52f
        const val SWATCH_GAP = 22f
        const val BUTTON_W = 120f
        const val BUTTON_H = 40f
    }
}
