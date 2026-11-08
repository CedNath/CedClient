package ced.cedclient.ui

/**
 * CedClient's colours, shared by the menu and every HUD (docs/superpowers/specs/2026-11-08-premium-look-design.md).
 * Black, white and grey; colour only means something (good / bad / gold). The accent is the player's pick
 * (Mod Settings > Accent, default none = [TEXT]). All values are ARGB.
 */
object Palette {
    const val TEXT = 0xFFF2F3F5.toInt()
    /** Labels and subtitles on a card. */
    const val MUTED = 0xFF9A9CA5.toInt()
    /** Labels in see-through HUDs: lighter, they sit straight on the game. */
    const val MUTED_SEE = 0xFFB4B6BE.toInt()
    /** Disabled modules, side notes. */
    const val FAINT = 0xFF6B6E78.toInt()
    /** Hairline dividers: white at 7 %. */
    const val LINE = 0x12FFFFFF
    /** 1 px border of cards, panels and popups: white at 9 %. */
    const val EDGE = 0x17FFFFFF
    /** Full HUD card fill: #0F1015 at 80 %. */
    const val SURFACE = 0xCC0F1015.toInt()
    /** Menu panels and popups: #0D0E12 at 86 %. */
    const val PANEL = 0xDB0D0E12.toInt()
    const val GOOD = 0xFF4CC38A.toInt()
    const val GOOD_SEE = 0xFF5EDB9C.toInt()
    const val BAD = 0xFFE5484D.toInt()
    const val BAD_SEE = 0xFFFF6B6F.toInt()
    const val GOLD = 0xFFE9B949.toInt()
    /** Text and knobs drawn on a light accent fill. */
    const val ON_ACCENT_DARK = 0xFF111216.toInt()

    /** The accent picker's swatches, in order. "None" is plain white. */
    val SWATCHES: List<Pair<String, Int>> = listOf(
        "None" to TEXT,
        "Red" to 0xFFE5484D.toInt(),
        "Amber" to 0xFFF5A524.toInt(),
        "Green" to 0xFF4CC38A.toInt(),
        "Cyan" to 0xFF4CC9E0.toInt(),
        "Blue" to 0xFF6E8BFF.toInt(),
        "Violet" to 0xFF9B8CFF.toInt(),
        "Pink" to 0xFFF06FB0.toInt(),
    )

    private fun linear(channel: Int): Double {
        val v = channel / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    /** Relative luminance (WCAG) of [rgb]; alpha is ignored. */
    fun luminance(rgb: Int): Double =
        0.2126 * linear((rgb shr 16) and 255) + 0.7152 * linear((rgb shr 8) and 255) + 0.0722 * linear(rgb and 255)

    /**
     * Colour for text or a knob on top of an [accentArgb] fill: whichever of [ON_ACCENT_DARK] / [TEXT] contrasts more.
     * The two tie at a luminance of about 0.18, so every bright swatch gets dark text and deep colours get white.
     */
    fun onAccent(accentArgb: Int): Int = if (luminance(accentArgb and 0xFFFFFF) > 0.18) ON_ACCENT_DARK else TEXT

    init {
        check(onAccent(TEXT) == ON_ACCENT_DARK) { "Palette: white accent needs dark text" }
        check(onAccent(0xFFF5A524.toInt()) == ON_ACCENT_DARK) { "Palette: amber accent needs dark text" }
        check(onAccent(0xFF690000.toInt()) == TEXT) { "Palette: deep red accent needs white text" }
    }
}
