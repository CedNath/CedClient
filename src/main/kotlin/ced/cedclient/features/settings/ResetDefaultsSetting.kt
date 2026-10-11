package ced.cedclient.features.settings

import ced.cedclient.config.ConfigManager
import ced.cedclient.features.Module
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import net.minecraft.client.input.MouseButtonEvent

/**
 * "Reset to Defaults" row ModuleButton adds under every module's settings. Not registered on the module,
 * so it's never saved. Needs a second click within [CONFIRM_MS] so a misclick can't wipe a module's setup.
 * Resets every setting the module has, including the ones that only live in its popups.
 */
class ResetDefaultsSetting(private val module: Module) :
    RenderableSetting<Unit>("Reset to Defaults", "Puts every setting of this module back to its default (click twice).") {

    override val default: Unit = Unit
    override var value: Unit = Unit

    private var armedAt = 0L
    private val confirming get() = System.currentTimeMillis() - armedAt < CONFIRM_MS

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        val height = super.render(x, y, mouseX, mouseY)
        val (label, color) = when {
            confirming -> "Click again to reset" to Colors.MINECRAFT_RED.rgba
            isHovered -> name to Colors.WHITE.rgba
            else -> name to Colors.MINECRAFT_GRAY.rgba
        }
        NVGRenderer.text(label, x + 6f, y + height / 2f - 7f, 14f, color, NVGRenderer.defaultFont)
        return height
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (!isHovered || click.button() != 0) return false
        if (!confirming) {
            armedAt = System.currentTimeMillis()
            return true
        }
        armedAt = 0L
        module.settings.values.forEach { it.reset() }
        ConfigManager.saveModulesOnly()
        return true
    }

    companion object {
        private const val CONFIRM_MS = 3000L

        /** Only worth showing when the module has something to reset (action buttons don't count). */
        fun hasResettable(module: Module) = module.settings.values.any { it !is ActionSetting }
    }
}
