package ced.cedclient.ui

import ced.cedclient.render.nvg.NVGRenderer

/**
 * The window every CedClient screen and popup sits in, so the search overlay, price input, keybinds, cosmetic presets,
 * button editor, update screen and debug panel match the ClickGUI popups: soft shadow, [Palette.PANEL] fill, a 1 px
 * [Palette.EDGE] border, and a hairline under the title instead of a coloured strip. Call inside a NanoVG frame.
 */
object Frame {
    const val RADIUS = 8f

    fun window(x: Float, y: Float, w: Float, h: Float, radius: Float = RADIUS, border: Float = 1f) {
        NVGRenderer.dropShadow(x, y, w, h, 10f, 3f, radius)
        NVGRenderer.rect(x, y, w, h, Palette.PANEL, radius)
        NVGRenderer.hollowRect(x, y, w, h, border, Palette.EDGE, radius)
    }

    /** Hairline across a window, under its title row. */
    fun divider(x: Float, y: Float, w: Float, thickness: Float = 1f) = NVGRenderer.rect(x, y, w, thickness, Palette.LINE)
}
