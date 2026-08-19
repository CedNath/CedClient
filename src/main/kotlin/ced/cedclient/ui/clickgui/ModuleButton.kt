package ced.cedclient.ui.clickgui

import ced.cedclient.features.Module
import ced.cedclient.features.settings.RenderableSetting
import ced.cedclient.ui.animations.ColorAnimation
import ced.cedclient.ui.animations.EaseInOutAnimation
import ced.cedclient.ui.nvg.NVGRenderer
import ced.cedclient.utils.Color
import ced.cedclient.utils.Color.Companion.brighter
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.clickGuiScale
import ced.cedclient.utils.ui.mouseX
import ced.cedclient.utils.ui.mouseY
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import kotlin.math.floor

/**
 * Renders one module's row inside a Panel, and (when right-clicked expanded)
 * its settings stack below it. Ported from Odin's clickgui/settings/ModuleButton.kt.
 *
 * @see RenderableSetting
 */
class ModuleButton(val module: Module, val panel: Panel) {

    val representableSettings = module.settings.values.filterIsInstance<RenderableSetting<*>>()
    private val colorAnim = ColorAnimation(150)

    private val color: Color
        get() =
            colorAnim.get(ClickGUI.clickGUIColor, Colors.gray26, module.enabled).brighter(1 + hover.percent() / 500f)

    private val nameWidth = NVGRenderer.textWidth(module.name, 21f, NVGRenderer.defaultFont)
    private val hoverHandler = HoverHandler(750)
    private val extendAnim = EaseInOutAnimation(250)
    private val hover = HoverHandler(250)
    var extended = false

    fun draw(x: Float, y: Float, lastModule: Boolean = false): Float {
        hoverHandler.handle(x, y, Panel.WIDTH, Panel.HEIGHT - 1, true)
        hover.handle(x, y, Panel.WIDTH, Panel.HEIGHT - 1, true)

        if (hoverHandler.percent() >= 100 && y >= panel.panelSetting.y + Panel.HEIGHT)
            ClickGUI.setDescription(module.description, x + Panel.WIDTH + 10f, y, hoverHandler)

        if (!ClickGUI.roundedPanelBottom && lastModule) {
            NVGRenderer.rect(x, y, Panel.WIDTH, Panel.HEIGHT - 10f, color.rgba)
            NVGRenderer.drawHalfRoundedRect(x, y + Panel.HEIGHT - 10f, Panel.WIDTH, 10f, color.rgba, 5f, false)
        } else {
            NVGRenderer.rect(x, y, Panel.WIDTH, Panel.HEIGHT, color.rgba)
        }
        NVGRenderer.text(
            module.name,
            x + Panel.WIDTH / 2 - nameWidth / 2,
            y + Panel.HEIGHT / 2 - 10.5f,
            21f,
            Colors.WHITE.rgba,
            NVGRenderer.defaultFont
        )

        if (representableSettings.isEmpty()) return Panel.HEIGHT

        val totalHeight = Panel.HEIGHT + floor(extendAnim.get(0f, getSettingHeight(), !extended))
        var drawY = Panel.HEIGHT

        if (extendAnim.isAnimating()) NVGRenderer.pushScissor(x, y, Panel.WIDTH, totalHeight)

        if (extendAnim.isAnimating() || extended) {
            for (setting in representableSettings) {
                if (setting.isVisible) drawY += setting.render(x, y + drawY, mouseX / clickGuiScale, mouseY / clickGuiScale)
            }
        }

        if (extendAnim.isAnimating()) NVGRenderer.popScissor()
        return totalHeight
    }

    fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (hover.isHovered) {
            if (click.button() == 0) {
                colorAnim.start()
                module.toggle()
                return true
            } else if (click.button() == 1) {
                if (module.settings.isNotEmpty()) {
                    extendAnim.start()
                    extended = !extended
                }
                return true
            }
        } else if (extended) {
            for (setting in representableSettings) {
                if (setting.isVisible && setting.mouseClicked(mouseX, mouseY, click)) return true
            }
        }
        return false
    }

    fun mouseReleased(click: MouseButtonEvent) {
        if (!extended) return
        for (setting in representableSettings) {
            if (setting.isVisible) setting.mouseReleased(click)
        }
    }

    fun keyTyped(input: CharacterEvent): Boolean {
        if (!extended) return false
        for (setting in representableSettings) {
            if (setting.isVisible && setting.keyTyped(input)) return true
        }
        return false
    }

    fun keyPressed(input: KeyEvent): Boolean {
        if (!extended) return false
        for (setting in representableSettings) {
            if (setting.isVisible && setting.keyPressed(input)) return true
        }
        return false
    }

    private fun getSettingHeight(): Float =
        representableSettings.filter { it.isVisible }.sumOf { it.getHeight().toDouble() }.toFloat()
}