package ced.cedclient.ui.clickgui

import ced.cedclient.ui.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

/**
 * Base for a modal popup drawn on top of the ClickGUI, e.g. EntityESP's
 * "Select Mobs" / "Select Players" / "Select Custom" name-filter editors
 * (see FilterPopup). ClickGUI centers whichever popup is open and gives it
 * exclusive input -- panels underneath don't receive clicks/keys/scroll
 * until the popup closes (see ClickGUI.openPopup/closePopup).
 *
 * Chrome (dropShadow + gray26 body + clickGUIColor border + centered title
 * + close button) matches Panel/SearchBar's existing visual language, so a
 * popup looks like it belongs to the same GUI rather than a bolted-on
 * dialog. Subclasses implement drawContent() for everything below the
 * title bar, and override the input callbacks if they need more than the
 * close button (FilterPopup does).
 *
 * Not a port of anything in Odin -- EntityESP's popups are CedClient-only,
 * so this is new UI built to match the established look.
 */
abstract class Popup(val title: String, val width: Float, val height: Float) {

    var x: Float = 0f
    var y: Float = 0f

    protected val closeButtonSize = 18f
    protected val closeButtonX get() = x + width - closeButtonSize - 8f
    protected val closeButtonY get() = y + 8f

    fun draw(mouseX: Float, mouseY: Float) {
        NVGRenderer.dropShadow(x, y, width, height, 10f, 3f, 5f)
        NVGRenderer.rect(x, y, width, height, Colors.gray26.rgba, 5f)
        NVGRenderer.hollowRect(x, y, width, height, 1.5f, ClickGUI.clickGUIColor.rgba, 5f)

        val titleWidth = NVGRenderer.textWidth(title, 18f, NVGRenderer.defaultFont)
        NVGRenderer.text(title, x + width / 2f - titleWidth / 2f, y + 10f, 18f, Colors.WHITE.rgba, NVGRenderer.defaultFont)

        val closeHovered = isAreaHovered(closeButtonX, closeButtonY, closeButtonSize, closeButtonSize, mouseX, mouseY)
        NVGRenderer.rect(
            closeButtonX,
            closeButtonY,
            closeButtonSize,
            closeButtonSize,
            (if (closeHovered) Colors.MINECRAFT_RED else Colors.gray38).rgba,
            4f
        )
        val closeWidth = NVGRenderer.textWidth("x", 14f, NVGRenderer.defaultFont)
        NVGRenderer.text(
            "x",
            closeButtonX + closeButtonSize / 2f - closeWidth / 2f,
            closeButtonY + 2f,
            14f,
            Colors.WHITE.rgba,
            NVGRenderer.defaultFont
        )

        drawContent(mouseX, mouseY)
    }

    protected abstract fun drawContent(mouseX: Float, mouseY: Float)

    /** ClickGUI checks this on click to close the popup, before offering the click to [mouseClicked]. */
    fun isCloseButtonHovered(mouseX: Float, mouseY: Float): Boolean =
        isAreaHovered(closeButtonX, closeButtonY, closeButtonSize, closeButtonSize, mouseX, mouseY)

    /** ClickGUI checks this on click to dismiss the popup when the click lands outside it entirely. */
    fun isInside(mouseX: Float, mouseY: Float): Boolean =
        mouseX in x..(x + width) && mouseY in y..(y + height)

    open fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean = false
    open fun mouseReleased(click: MouseButtonEvent) {}
    open fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Int): Boolean = false
    open fun keyTyped(input: CharacterEvent): Boolean = false
    open fun keyPressed(input: KeyEvent): Boolean = false

    protected fun isAreaHovered(ax: Float, ay: Float, aw: Float, ah: Float, mouseX: Float, mouseY: Float): Boolean =
        mouseX in ax..(ax + aw) && mouseY in ay..(ay + ah)
}