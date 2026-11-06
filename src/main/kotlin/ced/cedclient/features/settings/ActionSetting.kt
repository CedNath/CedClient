package ced.cedclient.features.settings

import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Color.Companion.brighter
import ced.cedclient.utils.Color.Companion.withAlpha
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

        // A bordered button, so it reads as clickable and not as a plain label.
        val bx = x + 6f
        val by = y + 5f
        val bw = width - 12f
        val bh = height - 10f
        NVGRenderer.rect(bx, by, bw, bh, (if (isHovered) Colors.gray38.brighter(1.4f) else Colors.gray38).rgba, 6f)
        NVGRenderer.hollowRect(bx, by, bw, bh, 2f, Colors.WHITE.withAlpha(if (isHovered) 1f else 0.7f).rgba, 6f)
        val tw = NVGRenderer.textWidth(name, 18f, NVGRenderer.defaultFont)
        NVGRenderer.text(name, bx + bw / 2f - tw / 2f, by + bh / 2f - 9f, 18f, Colors.WHITE.rgba, NVGRenderer.defaultFont)

        return height
    }

    /** Runs the action directly (e.g. from a popup's button bar). */
    fun press() = action()

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (!isHovered) return false
        action()
        return true
    }
}