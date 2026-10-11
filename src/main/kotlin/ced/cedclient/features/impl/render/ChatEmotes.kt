package ced.cedclient.features.impl.render

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource
import java.util.Optional

/**
 * Discord-style emotes in chat (toggled from the Cosmetics module): ":sob:" and the real emoji character
 * (😭, which Minecraft's font can't draw) are both shown as the Twemoji image. Each emote is a bitmap glyph in
 * the cedclient:emoji font (assets/cedclient/font/emoji.json), on private-use code points U+E000 onwards in the
 * order of assets/cedclient/emotes.txt ("<codepoint hex> <shortcode> [aliases...]").
 *
 * Also plays the vine boom when any chat line contains 🗿 / :moyai: (system chat, player chat, client messages).
 */
object ChatEmotes {

    private val FONT = FontDescription.Resource(Identifier.fromNamespaceAndPath("cedclient", "emoji"))
    private val VINE_BOOM = Identifier.fromNamespaceAndPath("cedclient", "vine_boom")
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

    /**
     * True only while a chat packet from the server is being handled (set by ClientPacketListenerMixin). Chat Patches
     * replays saved history by calling addMessage directly on join, outside any packet, so those lines don't boom.
     */
    @JvmField
    var liveChat = false

    init {
        ChatEmotes::class.java.getResourceAsStream("/assets/cedclient/emotes.txt")?.bufferedReader()?.useLines { lines ->
            lines.filter { it.isNotBlank() }.forEachIndexed { i, line ->
                val parts = line.trim().split(Regex("\\s+"))
                val glyph = String(Character.toChars(0xE000 + i))
                byCodepoint[parts[0].toInt(16)] = glyph
                parts.drop(1).forEach { byName[it] = glyph }
            }
        }
    }

    /** Every shortcode as typed in chat (":sob:"), sorted, for autocomplete. */
    private val completions: List<String> by lazy { byName.keys.map { ":$it:" }.sorted() }

    /**
     * Chat autocomplete (CommandSuggestionsMixin): the emotes to offer for the word being typed, or null when
     * it isn't an emote (not starting with ":") so vanilla's player-name completion is left alone.
     */
    @JvmStatic
    fun completionsFor(word: String): List<String>? =
        if (HardcodedCosmetics.chatEmotes.value && word.startsWith(":") && word.length >= 2) completions else null

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

    /** Called from ChatComponentMixin for every line the chat shows (any kind of chat). */
    @JvmStatic
    fun onLineShown(line: Component) {
        // Chat Emotes off turns the sound off too (vine boom has no visible toggle of its own).
        if (!HardcodedCosmetics.chatEmotes.value || !HardcodedCosmetics.vineBoom.value || !liveChat) return
        val text = line.string
        // The line may already have its emotes swapped in, so also look for the 🗿 glyph.
        val moyaiGlyph = byCodepoint[MOYAI]
        if (!text.codePoints().anyMatch { it == MOYAI } && !text.contains(":moyai:") &&
            (moyaiGlyph == null || !text.contains(moyaiGlyph))) return
        val now = System.currentTimeMillis()
        if (now - lastBoom < 300) return
        lastBoom = now
        // On the MASTER channel, so only the master slider (and Vine Boom Volume) apply -- not UI / Players.
        // Non-positional and camera-relative, like a UI sound.
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance(
                VINE_BOOM, SoundSource.MASTER, HardcodedCosmetics.vineBoomVolume.value.toFloat(), 1f,
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true,
            )
        )
    }
}
