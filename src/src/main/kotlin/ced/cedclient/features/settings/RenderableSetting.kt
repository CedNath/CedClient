package ced.cedclient.features.settings

import ced.cedclient.features.impl.misc.AdvancedMode
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.HoverHandler
import ced.cedclient.ui.clickgui.Panel
import ced.cedclient.utils.ui.isAreaHovered
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

/**
 * A Setting that knows how to draw and handle input for itself. Every
 * concrete setting type (BooleanSetting, NumberSetting, etc.) extends this
 * instead of ModuleButton having one big `when` over setting types.
 *
 * References ClickGUI.setDescription() (a companion-object member — ClickGUI
 * itself is a class now, not the singleton object Phase 2's comment assumed;
 * see ClickGUI.kt for why) and Panel.WIDTH/HEIGHT, both real as of Phase 4.
 */
abstract class RenderableSetting<T>(
    name: String,
    description: String
) : Setting<T>(name, description) {

    private val hoverHandler = HoverHandler(750)
    protected val width = Panel.WIDTH
    protected var lastX = 0f
    protected var lastY = 0f
    var listening = false

    /**
     * Set post-construction, e.g. `debugLog.advanced = true`. An advanced
     * setting is only shown while [AdvancedMode] is toggled on in the Misc
     * panel — see [isVisible] below.
     */
    var advanced: Boolean = false

    /**
     * On top of Setting's own hidden/visibilityDependency gate, an advanced
     * setting also requires AdvancedMode to be enabled. Non-advanced
     * settings are unaffected either way.
     */
    override val isVisible: Boolean
        get() = super.isVisible && (!advanced || AdvancedMode.isEnabled)

    open fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        lastX = x
        lastY = y
        val height = getHeight()
        hoverHandler.handle(x, y, width, height, true)
        if (hoverHandler.percent() > 0)
            ClickGUI.setDescription(description, x + width + 10f, y, hoverHandler)

        return height
    }

    open fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean = false
    open fun mouseReleased(click: MouseButtonEvent) {}
    open fun keyTyped(input: CharacterEvent): Boolean = false
    open fun keyPressed(input: KeyEvent): Boolean = false
    open fun getHeight(): Float = Panel.HEIGHT

    open val isHovered get() = isAreaHovered(lastX, lastY, width, getHeight(), true)
}