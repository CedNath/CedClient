package ced.cedclient.features.impl.render

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import java.util.Optional

/**
 * Discord-style emotes in chat (toggled from the Cosmetics module): ":sob:" and the real emoji character
 * (😭, which Minecraft's font can't draw) are both shown as the Twemoji image. Each emote is a bitmap glyph in
 * the cedclient:emoji font (assets/cedclient/font/emoji.json), on private-use code points U+E000 onwards in the
 * order of assets/cedclient/emotes.txt ("<codepoint hex> <shortcode> [aliases...]").
 *
 * Also plays the vine boom when a chat line contains 🗿 / :moyai:.
 */
object ChatEmotes {

    private val FONT = FontDescription.Resource(Identifier.fromNamespaceAndPath("cedclient", "emoji"))
    private val VINE_BOOM = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("cedclient", "vine_boom"))
    private const val MOYAI = 0x1F5FF
    private const val VARIATION_SELECTOR = 0xFE0F

    /** Shortcode (without colons) -> glyph. */
    private val byName = HashMap<String, String>()
    /** Emoji code point -> glyph. */
    private val byCodepoint = HashMap<Int, String>()

    private val SHORTCODE = Regex(""":([a-z0-9_+\-]+):""")

    /** Emotes are drawn untinted: chat colours / bold / italics would recolour or smear the image. */
    private val GLYPH_STYLE = Style.EMPTY.withFont(FONT).withColor(TextColor.fromRgb(0xFFFFFF))

    private var lastBoom = 0L

    init {
        ChatEmotes::class.java.getResourceAsStream("/assets/cedclient/emotes.txt")?.bufferedReader()?.useLines { lines ->
            lines.filter { it.isNotBlank() }.forEachIndexed { i, line ->
                val parts = line.trim().split(Regex("\\s+"))
                val glyph = String(Character.toChars(0xE000 + i))
                byCodepoint[parts[0].toInt(16)] = glyph
                parts.drop(1).forEach { byName[it] = glyph }
            }
        }
        on<ChatMessageEvent> { onChat(it.unformattedText) }
    }

    /** Called from ChatComponentMixin for every chat line; returns the line with emotes swapped in (or unchanged). */
    @JvmStatic
    fun transform(original: Component): Component {
        if (!HardcodedCosmetics.chatEmotes.value || byName.isEmpty()) return original
        val text = original.string
        if (text.indexOf(':') < 0 && text.codePoints().noneMatch { byCodepoint.containsKey(it) }) return original

        val out = Component.empty()
        var changed = false
        original.visit({ style, segment ->
            if (appendSegment(out, style, segment)) changed = true
            Optional.empty<Unit>()
        }, Style.EMPTY)
        return if (changed) out else original
    }

    /** Appends [segment] in [style], with emotes replaced by glyphs. True if anything was replaced. */
    private fun appendSegment(out: MutableComponent, style: Style, segment: String): Boolean {
        val plain = StringBuilder()
        var replaced = false
        fun flush() {
            if (plain.isNotEmpty()) out.append(Component.literal(plain.toString()).withStyle(style))
            plain.setLength(0)
        }
        fun emote(glyph: String) {
            flush()
            // Keep hover / click (e.g. a player name's hover card), drop colours and formatting.
            out.append(Component.literal(glyph).withStyle(GLYPH_STYLE.withHoverEvent(style.hoverEvent).withClickEvent(style.clickEvent)))
            replaced = true
        }

        var i = 0
        while (i < segment.length) {
            val cp = segment.codePointAt(i)
            val len = Character.charCount(cp)
            if (cp == ':'.code) {
                val m = SHORTCODE.matchAt(segment, i)
                val glyph = m?.let { byName[it.groupValues[1].lowercase()] }
                if (m != null && glyph != null) {
                    emote(glyph)
                    i = m.range.last + 1
                    continue
                }
            }
            val glyph = byCodepoint[cp]
            if (glyph != null) {
                emote(glyph)
                i += len
                // ❤️ etc. are sent as base + U+FE0F; the selector has nothing to draw on its own.
                if (i < segment.length && segment.codePointAt(i) == VARIATION_SELECTOR) i++
                continue
            }
            plain.appendCodePoint(cp)
            i += len
        }
        flush()
        return replaced
    }

    private fun onChat(text: String) {
        if (!HardcodedCosmetics.vineBoom.value) return
        if (!text.codePoints().anyMatch { it == MOYAI } && !text.contains(":moyai:")) return
        val now = System.currentTimeMillis()
        if (now - lastBoom < 300) return
        lastBoom = now
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(VINE_BOOM, 1f, HardcodedCosmetics.vineBoomVolume.value.toFloat())
        )
    }
}
