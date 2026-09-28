package ced.cedclient.utils

import ced.cedclient.render.font.Font
import ced.cedclient.render.nvg.NVGRenderer

/**
 * Minimal legacy (section-sign) color-code support. Just color, no
 * bold/italic/underline/strikethrough/obfuscated tracking -- NVGRenderer's
 * text() calls don't support switching font weight mid-string anyway, so
 * those codes are stripped rather than rendered.
 *
 * Built for rendering raw Hypixel API fields that arrive as a single
 * legacy-formatted string, e.g. `player.prefix` ("&b[VIP+]&r Steve" with
 * literal section signs instead of &).
 */
object MinecraftFormatting {

    private const val FORMAT_CHAR = '\u00A7'

    private val colorCodes: Map<Char, Color> = mapOf(
        '0' to Colors.BLACK, '1' to Colors.MINECRAFT_DARK_BLUE, '2' to Colors.MINECRAFT_DARK_GREEN,
        '3' to Colors.MINECRAFT_DARK_AQUA, '4' to Colors.MINECRAFT_DARK_RED, '5' to Colors.MINECRAFT_DARK_PURPLE,
        '6' to Colors.MINECRAFT_GOLD, '7' to Colors.MINECRAFT_GRAY, '8' to Colors.MINECRAFT_DARK_GRAY,
        '9' to Colors.MINECRAFT_BLUE, 'a' to Colors.MINECRAFT_GREEN, 'b' to Colors.MINECRAFT_AQUA,
        'c' to Colors.MINECRAFT_RED, 'd' to Colors.MINECRAFT_LIGHT_PURPLE, 'e' to Colors.MINECRAFT_YELLOW,
        'f' to Colors.WHITE
    )

    data class Run(val text: String, val color: Int)

    /** Splits a legacy section-sign-coded string into colored runs. Non-color codes (l/o/n/m/k/r) are stripped; `r` also resets to [defaultColor]. */
    fun parse(raw: String, defaultColor: Int): List<Run> {
        val runs = mutableListOf<Run>()
        var color = defaultColor
        val builder = StringBuilder()

        fun flush() {
            if (builder.isNotEmpty()) {
                runs.add(Run(builder.toString(), color))
                builder.clear()
            }
        }

        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == FORMAT_CHAR && i + 1 < raw.length) {
                val code = raw[i + 1].lowercaseChar()
                when {
                    colorCodes.containsKey(code) -> {
                        flush()
                        color = colorCodes.getValue(code).rgba
                    }
                    code == 'r' -> {
                        flush()
                        color = defaultColor
                    }
                    // l/o/n/m/k and anything unrecognized: just drop the two chars, no color change.
                }
                i += 2
                continue
            }
            builder.append(c)
            i++
        }
        flush()
        return runs
    }

    /** Draws [raw]'s colored runs left-to-right starting at (x, y). Returns the total width drawn, so callers can position what comes next. */
    fun drawFormatted(raw: String, x: Float, y: Float, size: Float, defaultColor: Int, font: Font): Float {
        var cursor = x
        for (run in parse(raw, defaultColor)) {
            if (run.text.isEmpty()) continue
            NVGRenderer.text(run.text, cursor, y, size, run.color, font)
            cursor += NVGRenderer.textWidth(run.text, size, font)
        }
        return cursor - x
    }
}