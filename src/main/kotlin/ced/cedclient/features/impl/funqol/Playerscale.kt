package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.NumberSetting

/**
 * Purely a render-time transform (see PlayerRendererMixin) -- scales only
 * the model drawn, never the entity's actual dimensions/hitbox (those come
 * from Entity.getDimensions(), untouched here), so it doesn't affect
 * collision, reach, or hit detection.
 *
 * Two independent behaviors, both client-side only (no packets, so this
 * never changes what anyone else sees unless they're also running the mod):
 *   - Self: whoever has this module enabled sees their OWN player model
 *     scaled per-axis by the Scale X/Y/Z sliders below.
 *   - Hardcoded target: CedNath specifically always renders at
 *     HARDCODED_SCALE_X/Y/Z, ignoring both the sliders and the module
 *     toggle entirely. Edit those constants directly to change them --
 *     they're intentionally not Settings.
 */
object PlayerScale : Module(
    "Player Scale",
    Category.Funqol,
    "Scales your own player model per-axis. CedNath always renders at a fixed size, independent of these sliders and this toggle."
) {
    const val HARDCODED_USERNAME = "CedNath"
    const val HARDCODED_SCALE_X = 2.50f // edit directly -- not exposed as a Setting
    const val HARDCODED_SCALE_Y = 0.20f
    const val HARDCODED_SCALE_Z = 2.50f

    private val scaleX = NumberSetting("Scale X", 1.0, 0.1, 5.0, 0.05)
    private val scaleY = NumberSetting("Scale Y", 1.0, 0.1, 5.0, 0.05)
    private val scaleZ = NumberSetting("Scale Z", 1.0, 0.1, 5.0, 0.05)

    val scaleFactorX: Float get() = scaleX.value.toFloat()
    val scaleFactorY: Float get() = scaleY.value.toFloat()
    val scaleFactorZ: Float get() = scaleZ.value.toFloat()

    init {
        addSettings(scaleX, scaleY, scaleZ)
    }
}