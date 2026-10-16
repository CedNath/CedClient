package ced.cedclient.ui.clickgui

import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.RenderableSetting
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

/**
 * A big popup that shows a module's settings in titled sections laid out in columns (each column is one panel
 * wide, so every setting draws exactly like it does in a panel), plus a row of [actions] as buttons at the bottom.
 * For modules with too many settings to comfortably list under the module in its panel -- mark those settings
 * [RenderableSetting.inPopup] so the panel skips them.
 *
 * The section area scrolls when it is taller than the popup (e.g. with a colour picker open).
 */
class SettingsPopup(
    title: String,
    private val columns: List<List<Section>>,
    private val actions: List<ActionSetting> = emptyList(),
    height: Float = 600f,
) : Popup(title, Popup.WIDTH, Popup.HEIGHT) {

    class Section(val name: String, val settings: List<RenderableSetting<*>>)

    private var scroll = 0f
    private var lastContentHeight = 0f

    private val allSettings get() = columns.asSequence().flatten().flatMap { it.settings }.filter { it.isVisible }

    private val viewTop get() = y + 40f
    private val actionsY get() = y + height - BUTTON_HEIGHT - 12f
    private val viewHeight get() = (if (actions.isEmpty()) y + height - 10f else actionsY - 10f) - viewTop

    override fun drawContent(mouseX: Float, mouseY: Float) {
        val font = NVGRenderer.defaultFont
        var contentHeight = 0f

        NVGRenderer.pushScissor(x, viewTop, width, viewHeight)
        columns.forEachIndexed { i, sections ->
            // Columns centred in the fixed-width popup (a single column sits in the middle).
            val cx = x + (width - columns.size * Panel.WIDTH - (columns.size - 1) * PAD) / 2f + i * (Panel.WIDTH + PAD)
            var cy = viewTop + scroll
            for (section in sections) {
                NVGRenderer.text(section.name, cx + 6f, cy + 4f, 16f, ClickGUI.clickGUIColor.rgba, font)
                NVGRenderer.rect(cx, cy + 24f, Panel.WIDTH, 1f, Colors.gray38.rgba)
                cy += HEADER_HEIGHT
                for (setting in section.settings) {
                    if (setting.isVisible) cy += setting.render(cx, cy, mouseX, mouseY)
                }
                cy += SECTION_GAP
            }
            contentHeight = maxOf(contentHeight, cy - (viewTop + scroll))
        }
        NVGRenderer.popScissor()
        lastContentHeight = contentHeight
        scroll = scroll.coerceIn((viewHeight - contentHeight).coerceAtMost(0f), 0f)

        // Scroll thumb
        if (contentHeight > viewHeight) {
            val sx = x + width - 7f
            val thumb = (viewHeight * viewHeight / contentHeight).coerceAtLeast(20f)
            val pct = (scroll / (viewHeight - contentHeight)).coerceIn(0f, 1f)
            NVGRenderer.rect(sx, viewTop + (viewHeight - thumb) * pct, 4f, thumb, Colors.gray38.rgba, 2f)
        }

        // Action buttons
        if (actions.isNotEmpty()) {
            NVGRenderer.rect(x + PAD, actionsY - 6f, width - 2 * PAD, 1f, Colors.gray38.rgba)
            val bw = buttonWidth()
            actions.forEachIndexed { i, action ->
                val bx = x + PAD + i * (bw + BUTTON_GAP)
                val hovered = isAreaHovered(bx, actionsY, bw, BUTTON_HEIGHT, mouseX, mouseY)
                NVGRenderer.rect(bx, actionsY, bw, BUTTON_HEIGHT, (if (hovered) ClickGUI.clickGUIColor else Colors.gray38).rgba, 4f)
                val label = NVGRenderer.truncate(action.name, bw - 8f, 14f, font)
                val tw = NVGRenderer.textWidth(label, 14f, font)
                NVGRenderer.text(label, bx + bw / 2f - tw / 2f, actionsY + BUTTON_HEIGHT / 2f - 7f, 14f, Colors.WHITE.rgba, font)
            }
        }
    }

    private fun buttonWidth() = (width - 2 * PAD - BUTTON_GAP * (actions.size - 1)) / actions.size

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (actions.isNotEmpty() && click.button() == 0) {
            val bw = buttonWidth()
            actions.forEachIndexed { i, action ->
                if (isAreaHovered(x + PAD + i * (bw + BUTTON_GAP), actionsY, bw, BUTTON_HEIGHT, mouseX, mouseY)) {
                    action.press()
                    return true
                }
            }
        }
        // Rows scrolled out of view are still laid out under the mouse -- only take clicks inside the view.
        if (mouseY !in viewTop..(viewTop + viewHeight)) return true
        for (setting in allSettings) if (setting.mouseClicked(mouseX, mouseY, click)) return true
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent) {
        for (setting in allSettings) setting.mouseReleased(click)
    }

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Int): Boolean {
        // Clamp here too: clamping only in draw let each scroll past the end show one frame too far (jitter).
        scroll = (scroll + amount * 2).coerceIn((viewHeight - lastContentHeight).coerceAtMost(0f), 0f)
        return true
    }

    override fun keyTyped(input: CharacterEvent): Boolean = allSettings.any { it.keyTyped(input) }
    override fun keyPressed(input: KeyEvent): Boolean = allSettings.any { it.keyPressed(input) }

    companion object {
        private const val PAD = 16f
        private const val HEADER_HEIGHT = 30f
        private const val SECTION_GAP = 12f
        private const val BUTTON_HEIGHT = 26f
        private const val BUTTON_GAP = 6f
    }
}
