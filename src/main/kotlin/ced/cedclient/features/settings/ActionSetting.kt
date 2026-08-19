package ced.cedclient.features.settings

import ced.cedclient.ui.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import net.minecraft.client.input.MouseButtonEvent

/**
 * A clickable button row that fires a callback — e.g.
 * `ActionSetting("Open Editor") { InventoryButtonEditorOverlay.open() }`.
 * Nothing to persist, so this doesn't implement Saving.
 */
class ActionSetting(
    name: String,
    description: String = "",
    private val action: () -> Unit
) : RenderableSetting<Unit>(name, description) {

    constructor(name: String, action: () -> Unit) : this(name, "", action)

    override val default: Unit = Unit
    override var value: Unit = Unit

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        val height = super.render(x, y, mouseX, mouseY)

        val textColor = if (isHovered) Colors.WHITE.rgba else Colors.MINECRAFT_GRAY.rgba
        NVGRenderer.text(name, x + 6f, y + height / 2f - 7f, 14f, textColor, NVGRenderer.defaultFont)

        return height
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (!isHovered) return false
        action()
        return true
    }
}