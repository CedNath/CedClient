package ced.cedclient.ui.clickgui

class ColorAnimation(private val duration: Long) {
    private var startTime = 0L
    private var active = false

    fun start() {
        startTime = System.currentTimeMillis()
        active = true
    }

    fun get(enabledColor: Int, disabledColor: Int, enabled: Boolean): Int {
        val elapsed = System.currentTimeMillis() - startTime
        val percent = (elapsed * 1f / duration).coerceIn(0f, 1f)

        val from = if (enabled) disabledColor else enabledColor
        val to = if (enabled) enabledColor else disabledColor

        return interpolateColor(from, to, percent)
    }

    private fun interpolateColor(from: Int, to: Int, percent: Float): Int {
        val fr = (from shr 16) and 0xFF
        val fg = (from shr 8) and 0xFF
        val fb = from and 0xFF

        val tr = (to shr 16) and 0xFF
        val tg = (to shr 8) and 0xFF
        val tb = to and 0xFF

        val r = (fr + ((tr - fr) * percent)).toInt()
        val g = (fg + ((tg - fg) * percent)).toInt()
        val b = (fb + ((tb - fb) * percent)).toInt()

        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
