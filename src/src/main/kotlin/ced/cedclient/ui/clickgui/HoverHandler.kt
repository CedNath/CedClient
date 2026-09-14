package ced.cedclient.ui.clickgui

import ced.cedclient.ui.animations.LinearAnimation
import ced.cedclient.utils.ui.isAreaHovered

class HoverHandler(delay: Long) {

    val anim = LinearAnimation<Float>(delay)
    var isHovered = false

    fun percent(): Float {
        if (!anim.isAnimating()) return if (isHovered) 100f else 0f
        return if (isHovered) anim.getPercent() else 100f - anim.getPercent()
    }

    fun handle(x: Float, y: Float, w: Float, h: Float, scaled: Boolean = false) {
        val currentlyHovered = isAreaHovered(x, y, w, h, scaled)

        if (currentlyHovered != isHovered) {
            anim.start()
            isHovered = currentlyHovered
        }
    }
}