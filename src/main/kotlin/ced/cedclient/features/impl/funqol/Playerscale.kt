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
 *   - Synced target: while HardcodedCosmetics is enabled, any IGN with a
 *     scale pushed via CosmeticsSync renders at that scale instead,
 *     ignoring this module's own sliders/toggle entirely. Turning
 *     HardcodedCosmetics off disables this override too (see
 *     PlayerRendererMixin), same as it does for the synced nametag.
 */
object PlayerScale : Module(
    "Player Scale",
    Category.Cosmetic,
    "Scales your own player model per-axis. Names with a synced scale pushed via CosmeticsSync always render at that fixed size, independent of these sliders and this toggle."
) {
    private val scaleX = NumberSetting("Scale X", 1.0, -10.0, 10.0, 0.05, "Type a bigger number for sizes past the slider. Negative flips the model on this axis.", uncapped = true)
    private val scaleY = NumberSetting("Scale Y", 1.0, -10.0, 10.0, 0.05, "Type a bigger number for sizes past the slider. Negative flips the model on this axis.", uncapped = true)
    private val scaleZ = NumberSetting("Scale Z", 1.0, -10.0, 10.0, 0.05, "Type a bigger number for sizes past the slider. Negative flips the model on this axis.", uncapped = true)

    // A size of 0 would collapse the model, so it is nudged to the smallest size; the sign (the flip) is kept.
    private fun axis(v: Double): Float = (if (v < 0.0) v.coerceAtMost(-0.01) else v.coerceAtLeast(0.01)).toFloat()
    val scaleFactorX: Float get() = axis(scaleX.value)
    val scaleFactorY: Float get() = axis(scaleY.value)
    val scaleFactorZ: Float get() = axis(scaleZ.value)

    init {
        addSettings(scaleX, scaleY, scaleZ)
    }
}