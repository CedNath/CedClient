package ced.cedclient.ui.clickgui

class HoverHandler(private val delay: Long) {
    private var lastHoverTime = 0L
    var isHovered = false
        private set

    fun handle(x: Int, y: Int, width: Int, height: Int, mouseX: Int, mouseY: Int) {
        val hovered = mouseX in x..(x + width) && mouseY in y..(y + height)
        if (hovered) {
            if (!isHovered) lastHoverTime = System.currentTimeMillis()
            isHovered = true
        } else {
            isHovered = false
            lastHoverTime = 0L
        }
    }

    fun percent(): Int {
        if (!isHovered) return 0
        val elapsed = System.currentTimeMillis() - lastHoverTime
        return (elapsed * 100 / delay).coerceAtMost(100).toInt()
    }
}

