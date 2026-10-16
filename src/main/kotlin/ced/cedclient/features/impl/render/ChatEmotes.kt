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
import java.util.Collections
import java.util.Optional
import java.util.WeakHashMap

/**
 * Discord-style emotes in chat (toggled from the Cosmetics module): ":sob:" and the real emoji character
 * (😭, which Minecraft's font can't draw) are both shown as the Twemoji image. Each emote is a bitmap glyph in
 * the cedclient:emoji font (assets/cedclient/font/emoji.json), on private-use code points U+E000 onwards in the
 * order of assets/cedclient/emotes.txt ("<codepoint hex> <shortcode> [aliases...]").
 *
 * Also plays a meme sound when a chat line contains certain emotes (see [SOUNDS]): 🗿 vine boom, ✅ / ❌, 🕷, 😱,
 * and the custom :fah: / :sus: / :dictator: emotes (images in textures/emoji, code points f0001+ in emotes.txt).
 */
object ChatEmotes {

    private val FONT = FontDescription.Resource(Identifier.fromNamespaceAndPath("cedclient", "emoji"))
    /**
     * Emote shortcode -> sound (assets/cedclient/sounds.json) played when a chat line contains that emote, as the
     * emoji, its :shortcode: or the already-swapped glyph. Earlier entries win when a line has several.
     */
    private val SOUNDS = linkedMapOf(
        "moyai" to "vine_boom",
        "white_check_mark" to "correct",
        "x" to "wrong",
        "spider" to "spiderman",
        "fah" to "fah",
        "sus" to "sus",
        "dictator" to "dictator",
        "scream" to "anime_wow",
        "private" to "private_property",
        "rizz" to "rizz",
        "spongebob" to "spongebob_fail",
        "person_shrugging" to "it_is_what_it_is",
        "camera" to "camera",
        "door" to "door",
        "man_dancing" to "hee_hee",
    ).mapValues { Identifier.fromNamespaceAndPath("cedclient", it.value) }
    private const val VARIATION_SELECTOR = 0xFE0F

    /** Shortcode (without colons) -> glyph. */
    private val byName = HashMap<String, String>()
    /** Emoji code point -> glyph. */
    private val byCodepoint = HashMap<Int, String>()
    /** Shortcode -> emoji code point (for the sound triggers). */
    private val codepointOf = HashMap<String, Int>()

    private val SHORTCODE = Regex(""":([a-z0-9_+\-]+):""")

    /** Emotes are drawn untinted: chat colours / bold / italics would recolour or smear the image. */
    private val GLYPH_STYLE = Style.EMPTY.withFont(FONT).withColor(TextColor.fromRgb(0xFFFFFF))

    private var lastBoom = 0L

    private val boosted: MutableSet<SoundInstance> = Collections.newSetFromMap(WeakHashMap())

    /** Emote shortcodes that play a sound, for /emotevolume. */
    val soundEmotes: Set<String> get() = SOUNDS.keys

    /** SoundEngineMixin: our meme sounds play at the /emotevolume level, whatever the Master slider says (even 0). */
    @JvmStatic
    fun boostVolume(instance: SoundInstance, volume: Float): Float {
        if (instance !in boosted) return volume
        return HardcodedCosmetics.emoteVolume.value.toFloat() * instance.volume.coerceIn(0f, 1f)
    }

    /**
     * True only while a chat packet from the server is being handled (set by ClientPacketListenerMixin). Chat Patches
     * replays saved history by calling addMessage directly on join, outside any packet, so those lines don't boom.
     */
    @JvmField
    var liveChat = false

    /** An animated emote (assets/cedclient/animated_emotes.txt): its frames sit on consecutive code points. */
    private class Anim(val firstFrame: Int, val frames: Int, val frameMs: Int)
    /** Emote glyph code point -> its animation. */
    private val animByGlyph = HashMap<Int, Anim>()

    init {
        ChatEmotes::class.java.getResourceAsStream("/assets/cedclient/emotes.txt")?.bufferedReader()?.useLines { lines ->
            lines.filter { it.isNotBlank() }.forEachIndexed { i, line ->
                val parts = line.trim().split(Regex("\\s+"))
                val glyph = String(Character.toChars(0xE000 + i))
                val cp = parts[0].toInt(16)
                byCodepoint[cp] = glyph
                parts.drop(1).forEach {
                    byName[it] = glyph
                    codepointOf[it] = cp
                }
            }
        }
        ChatEmotes::class.java.getResourceAsStream("/assets/cedclient/animated_emotes.txt")?.bufferedReader()?.useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val p = line.split(Regex("\\s+"))
                val glyph = byName[p[0]] ?: return@forEach
                animByGlyph[glyph.codePointAt(0)] = Anim(p[1].toInt(16), p[2].toInt(), p[3].toInt())
            }
        }
    }

    /**
     * FontSetMixin, for every glyph the game looks up: an animated emote's glyph becomes the frame for the current
     * time (chat is redrawn every frame, so it plays). Anything outside the private-use block returns straight away.
     */
    @JvmStatic
    fun animatedGlyph(cp: Int): Int {
        if (cp < 0xE000 || cp > 0xF8FF) return cp
        val anim = animByGlyph[cp] ?: return cp
        return anim.firstFrame + ((System.currentTimeMillis() / anim.frameMs) % anim.frames).toInt()
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
        // Glyphs of every :shortcode: in the line, aliases included (":heehee:" = ":man_dancing:").
        val typed = SHORTCODE.findAll(text).mapNotNull { byName[it.groupValues[1].lowercase()] }.toSet()
        // The line may already have its emotes swapped in, so also look for the glyph.
        val sound = SOUNDS.entries.firstOrNull { (name, _) ->
            val cp = codepointOf[name]
            val glyph = byName[name]
            (glyph != null && (glyph in typed || text.contains(glyph))) ||
                (cp != null && text.codePoints().anyMatch { it == cp })
        }?.value ?: return
        val now = System.currentTimeMillis()
        if (now - lastBoom < 300) return
        lastBoom = now
        // On the MASTER channel, not UI / Players; boostVolume then swaps the Master slider for /emotevolume.
        // Non-positional and camera-relative, like a UI sound.
        val instance = SimpleSoundInstance(
            sound, SoundSource.MASTER, HardcodedCosmetics.vineBoomVolume.value.toFloat(), 1f,
            SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true,
        )
        boosted.add(instance)
        Minecraft.getInstance().soundManager.play(instance)
    }
}
