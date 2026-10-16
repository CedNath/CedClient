package ced.cedclient.features.settings

import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Colors

/**
 * A section title with a divider line above it, to group a module's settings, e.g.
 * `HeaderSetting("Vampire", "Rift Vampire Slayer boss helpers")`. Not clickable, nothing to save.
 */
class HeaderSetting(
    name: String,
    description: String = ""
) : RenderableSetting<Unit>(name, description) {

    override val default: Unit = Unit
    override var value: Unit = Unit

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        val height = super.render(x, y, mouseX, mouseY)
        NVGRenderer.line(x + 6f, y + 3f, x + width - 6f, y + 3f, 1f, Colors.MINECRAFT_DARK_GRAY.rgba)
        NVGRenderer.text(name.uppercase(), x + 6f, y + height / 2f - 5f, 12f, Colors.MINECRAFT_GOLD.rgba, NVGRenderer.defaultFont)
        return height
    }
}
