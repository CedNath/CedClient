package ced.cedclient.utils

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

/**
 * Small formatter for nametag text (CustomNametag's own tag, and any tag
 * pushed via CosmeticsSync/cosmetics.json) that goes beyond vanilla's
 * 16-color legacy "&" codes:
 *
 *   &0-&9, &a-&f              vanilla 16 colors, same as always
 *   &l &o &n &m &k             bold / italic / underline / strikethrough / obfuscated
 *   &r                         reset color + all styles
 *   &#RRGGBB                   any RGB color, e.g. &#ff8800
 *   <gradient:#RRGGBB:#RRGGBB>text</gradient>
 *                               smoothly blends between two colors across the text
 *   <rainbow>text</rainbow>
 *                               cycles through hues; drifts over real time, so
 *                               in-world tags (re-parsed every frame) visibly
 *                               animate -- chat/tab-list only render once, so
 *                               they'll just show a static snapshot of the cycle
 *
 * Like vanilla: setting a color (legacy or hex) resets bold/italic/etc back
 * off first, so put style codes *after* the color if you want both, e.g.
 * "&#ff0000&lBold Red". Unclosed <gradient>/<rainbow> tags just run to the
 * end of the string rather than erroring.
 */
object NametagFormatting {

    private val LEGACY_COLORS: Map<Char, TextColor> = mapOf(
        '0' to ChatFormatting.BLACK, '1' to ChatFormatting.DARK_BLUE, '2' to ChatFormatting.DARK_GREEN,
        '3' to ChatFormatting.DARK_AQUA, '4' to ChatFormatting.DARK_RED, '5' to ChatFormatting.DARK_PURPLE,
        '6' to ChatFormatting.GOLD, '7' to ChatFormatting.GRAY, '8' to ChatFormatting.DARK_GRAY,
        '9' to ChatFormatting.BLUE, 'a' to ChatFormatting.GREEN, 'b' to ChatFormatting.AQUA,
        'c' to ChatFormatting.RED, 'd' to ChatFormatting.LIGHT_PURPLE, 'e' to ChatFormatting.YELLOW,
        'f' to ChatFormatting.WHITE
    ).mapValues { (_, formatting) -> TextColor.fromRgb(formatting.color ?: 0xFFFFFF) }

    private data class RunStyle(
        var color: TextColor? = null,
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var strikethrough: Boolean = false,
        var obfuscated: Boolean = false
    ) {
        fun toStyle(): Style = Style.EMPTY
            .withColor(color)
            .withBold(bold)
            .withItalic(italic)
            .withUnderlined(underline)
            .withStrikethrough(strikethrough)
            .withObfuscated(obfuscated)

        fun copy() = RunStyle(color, bold, italic, underline, strikethrough, obfuscated)

        fun resetToColor(newColor: TextColor?) {
            color = newColor
            bold = false; italic = false; underline = false
            strikethrough = false; obfuscated = false
        }
    }

    fun parse(raw: String): MutableComponent {
        val result: MutableComponent = Component.literal("")
        val style = RunStyle()
        var i = 0

        while (i < raw.length) {
            val c = raw[i]

            if (c == '&' && i + 1 < raw.length) {
                val code = raw[i + 1]
                val hexAt = i + 2
                if (code == '#' && hexAt + 6 <= raw.length && isHex(raw.substring(hexAt, hexAt + 6))) {
                    style.resetToColor(TextColor.fromRgb(raw.substring(hexAt, hexAt + 6).toInt(16)))
                    i = hexAt + 6
                    continue
                }
                val legacy = LEGACY_COLORS[code.lowercaseChar()]
                if (legacy != null) {
                    style.resetToColor(legacy)
                    i += 2
                    continue
                }
                var consumed = true
                when (code.lowercaseChar()) {
                    'l' -> style.bold = true
                    'o' -> style.italic = true
                    'n' -> style.underline = true
                    'm' -> style.strikethrough = true
                    'k' -> style.obfuscated = true
                    'r' -> style.resetToColor(null)
                    else -> consumed = false
                }
                if (consumed) {
                    i += 2
                    continue
                }
            }

            if (raw.startsWith("<gradient:", i)) {
                val headerEnd = raw.indexOf('>', i)
                if (headerEnd != -1) {
                    val header = raw.substring(i + "<gradient:".length, headerEnd)
                    val colors = header.split(":").mapNotNull { parseHexColor(it) }
                    if (colors.size == 2) {
                        val closeTag = "</gradient>"
                        val closeIdx = raw.indexOf(closeTag, headerEnd + 1)
                        val contentEnd = if (closeIdx == -1) raw.length else closeIdx
                        appendGradient(result, raw.substring(headerEnd + 1, contentEnd), colors[0], colors[1], style)
                        i = if (closeIdx == -1) raw.length else closeIdx + closeTag.length
                        continue
                    }
                }
            }

            if (raw.startsWith("<rainbow>", i)) {
                val contentStart = i + "<rainbow>".length
                val closeTag = "</rainbow>"
                val closeIdx = raw.indexOf(closeTag, contentStart)
                val contentEnd = if (closeIdx == -1) raw.length else closeIdx
                appendRainbow(result, raw.substring(contentStart, contentEnd), style)
                i = if (closeIdx == -1) raw.length else closeIdx + closeTag.length
                continue
            }

            result.append(Component.literal(c.toString()).withStyle(style.toStyle()))
            i++
        }

        return result
    }

    private fun appendGradient(target: MutableComponent, text: String, from: TextColor, to: TextColor, base: RunStyle) {
        if (text.isEmpty()) return
        val len = text.length
        for (index in text.indices) {
            val t = if (len == 1) 0f else index.toFloat() / (len - 1)
            val run = base.copy()
            run.color = lerpColor(from, to, t)
            target.append(Component.literal(text[index].toString()).withStyle(run.toStyle()))
        }
    }

    private fun appendRainbow(target: MutableComponent, text: String, base: RunStyle) {
        // Slow hue drift over real time so in-world tags (re-parsed every
        // frame) visibly cycle; chat/tab-list just capture one frame of it.
        val phase = (System.currentTimeMillis() % 3600L) / 3600f
        val len = text.length.coerceAtLeast(1)
        for ((index, ch) in text.withIndex()) {
            val hue = (phase + index.toFloat() / len) % 1f
            val run = base.copy()
            run.color = TextColor.fromRgb(java.awt.Color.HSBtoRGB(hue, 0.9f, 1.0f) and 0xFFFFFF)
            target.append(Component.literal(ch.toString()).withStyle(run.toStyle()))
        }
    }

    private fun lerpColor(from: TextColor, to: TextColor, t: Float): TextColor {
        val f = from.value
        val g = to.value
        val r = lerp((f shr 16) and 0xFF, (g shr 16) and 0xFF, t)
        val gr = lerp((f shr 8) and 0xFF, (g shr 8) and 0xFF, t)
        val b = lerp(f and 0xFF, g and 0xFF, t)
        return TextColor.fromRgb((r shl 16) or (gr shl 8) or b)
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt().coerceIn(0, 255)

    private fun isHex(s: String) = s.length == 6 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    private fun parseHexColor(s: String): TextColor? {
        val trimmed = s.trim().removePrefix("#")
        if (!isHex(trimmed)) return null
        return TextColor.fromRgb(trimmed.toInt(16))
    }
}