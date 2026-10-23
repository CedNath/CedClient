package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.NumberSetting
import net.minecraft.resources.Identifier

/**
 * Color saturation of the 3D world (not the GUI): a post-process pass after the level is drawn, run from
 * GameRendererSaturationMixin. The slider value reaches the shader through PostPassSaturationMixin.
 */
object Saturation : Module(
    "Saturation",
    Category.Render,
    "Makes the world more or less colorful. 100% is vanilla, 0% is black and white."
) {
    private val amount = NumberSetting("Saturation %", 100.0, 0.0, 300.0, 5.0, "100 = vanilla, below = washed out, above = vivid.")

    val CHAIN: Identifier = Identifier.fromNamespaceAndPath("cedclient", "saturation")

    init { addSettings(amount) }

    /** Shader value (1.0 = vanilla). */
    fun value() = (amount.value / 100.0).toFloat()

    /** Whether the pass needs to run at all. */
    fun active() = isEnabled && amount.value != 100.0
}
