package ced.cedclient.ui.animations

import ced.cedclient.utils.Color

/**
 * Animates between two Colors by animating each RGBA channel independently
 * through a LinearAnimation<Int>. Pulled forward from Phase 6 ("reattach your
 * extras") because ModuleButton (Phase 4) needs it for the panel-row
 * enabled/disabled color blend — same justification the roadmap already gave
 * HoverHandler/SearchBar for being written early: no real dependencies.
 *
 * Ported 1:1 from Odin's utils/ui/animations/ColorAnimation.kt.
 */
class ColorAnimation(duration: Long) {

    private val anim = LinearAnimation<Int>(duration)

    fun start() = anim.start()

    fun isAnimating(): Boolean = anim.isAnimating()

    fun percent(): Float = anim.getPercent()

    fun get(start: Color, end: Color, reverse: Boolean): Color =
        Color(
            anim.get(start.red, end.red, reverse),
            anim.get(start.green, end.green, reverse),
            anim.get(start.blue, end.blue, reverse),
            anim.get(start.alpha, end.alpha, reverse) / 255f,
        )
}