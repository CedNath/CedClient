package ced.cedclient.utils

import ced.cedclient.features.impl.render.CustomNametag
import ced.cedclient.features.impl.render.HardcodedCosmetics
import ced.cedclient.sync.CosmeticsSync
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style

import java.util.Optional

/**
 * Shared logic for CustomNametag's 2D-text surfaces (chat, tab list) -- the
 * in-world floating tag is handled separately by PlayerRendererMixin
 * (overwrites the render state's nameTag Component directly, via
 * NametagOverride), this handles the rest via ChatComponentMixin /
 * PlayerTabOverlayMixin.
 *
 * Two replacement sources, synced taking priority over self (same order
 * NametagOverride/PlayerRendererMixin uses):
 *  - any IGN with a tag pushed via CosmeticsSync -> that tag, regardless of
 *    CustomNametag's own module toggle, but only while
 *    HardcodedCosmetics.isEnabled -- lets other people running this client
 *    turn the synced cosmetics off for themselves.
 *  - the local player's own username -> CustomNametag.tagText.value, only
 *    while the module is enabled and the field isn't blank.
 */
object CustomNametagText {

    /**
     * Tab list row for [profileName] -- a full swap, since there the whole
     * value IS the name. Returns null when nothing should override it (the
     * caller keeps whatever vanilla/the server supplied).
     */
    /**
     * [profileId] is used first to identify "is this my own row" -- some
     * servers (Hypixel lobbies included) build tab list layouts using fake
     * GameProfile *names* as sort/column keys (e.g. "!A-a"), with the
     * visible text actually coming from a scoreboard team prefix/suffix
     * wrapped around the entry. Matching by name silently fails in that
     * case since profileName is never really your username there. In the
     * lobby the UUID stays real (needed for the skin head to render), so
     * matching on UUID recovers it.
     *
     * Hypixel SkyBlock (as opposed to the lobby) goes a step further and
     * fakes the per-row UUID too -- confirmed via logging, profileId there
     * is neither your real UUID nor tied to it. So on SkyBlock islands
     * neither the name check nor the UUID check ever passes. [original] is
     * the fully-built vanilla component for the row (team prefix/suffix
     * included), which still literally contains your real username as
     * text even when the profile fields are faked -- so as a last resort
     * we look for your username as a whole word inside the rendered text.
     * This is a weaker signal (it could false-positive on someone else's
     * name containing yours as a substring token), so it's only used once
     * the UUID/name checks have both failed.
     *
     * [profileName] is still used for CosmeticsSync (other players' synced
     * tags), since that data is keyed by real IGN -- this will only work
     * on servers that don't do the fake-name trick for other players' rows.
     */
    fun transformTabListName(profileName: String, profileId: java.util.UUID?, original: Component): Component? {
        if (HardcodedCosmetics.isEnabled) {
            // Direct match: works wherever the server sends a real profile name.
            CosmeticsSync.getOverride(profileName)?.tag?.let { syncedTag ->
                val tag = NametagFormatting.parse(syncedTag)
                return spliceOverName(original, profileName, tag) ?: tag
            }

            // Fallback: SkyBlock fakes profileName (e.g. "!A-b") for tab-list
            // rows, so the direct lookup above always misses there. The real
            // IGN is still present as literal text in the rendered row though,
            // so scan known synced IGNs for a whole-word match against it.
            val originalText = original.string
            for ((ign, override) in CosmeticsSync.allTags()) {
                val regex = Regex("\\b${Regex.escape(ign)}\\b", RegexOption.IGNORE_CASE)
                if (regex.containsMatchIn(originalText)) {
                    val tag = NametagFormatting.parse(override)
                    return spliceOverName(original, ign, tag) ?: tag
                }
            }
        }

        val ownTag = CustomNametag.tagText.value
        val localPlayer = Minecraft.getInstance().player
        val localName = localPlayer?.gameProfile?.name

        val uuidMatch = profileId != null && profileId == localPlayer?.uuid
        val nameMatch = profileName.equals(localName, ignoreCase = true)
        val textMatch = localName != null && localName.isNotBlank()
                && Regex("\\b${Regex.escape(localName)}\\b").containsMatchIn(original.string)
        val isOwnEntry = uuidMatch || nameMatch || textMatch

        if (CustomNametag.isEnabled && ownTag.isNotBlank() && isOwnEntry && localName != null) {
            val tag = NametagFormatting.parse(ownTag)
            return spliceOverName(original, localName, tag) ?: tag
        }

        return null
    }
    /**
     * Replaces [needle] as a whole word inside [original]'s rendered text
     * with [replacement], preserving everything else about [original] --
     * rank prefix/suffix, surrounding colours, hover/click events. This is
     * what keeps e.g. Hypixel's "[MVP+]" team prefix intact in front of
     * the tab list name instead of a blind full-component swap wiping it
     * out along with the rest of the line.
     *
     * Returns null if [needle] isn't actually present as literal text
     * anywhere in [original] (e.g. a CosmeticsSync entry keyed by real IGN
     * on a server that fakes the profile name so it never shows up as
     * text) -- callers should fall back to a full swap in that case, since
     * there's nothing to splice onto.
     */
    private fun spliceOverName(original: Component, needle: String, replacement: Component): Component? {
        val fullText = original.string
        val regex = Regex("\\b${Regex.escape(needle)}\\b", RegexOption.IGNORE_CASE)
        val match = regex.find(fullText) ?: return null
        val matchStart = match.range.first
        val matchEnd = match.range.last + 1

        val result: MutableComponent = Component.literal("")
        var offset = 0
        var replaced = false

        original.visit(
            FormattedText.StyledContentConsumer<Unit> { style, text ->
                val segStart = offset
                val segEnd = offset + text.length
                offset = segEnd

                if (segEnd <= matchStart || segStart >= matchEnd) {
                    if (text.isNotEmpty()) result.append(Component.literal(text).withStyle(style))
                } else {
                    val localStart = (matchStart - segStart).coerceIn(0, text.length)
                    val localEnd = (matchEnd - segStart).coerceIn(0, text.length)
                    if (localStart > 0) {
                        result.append(Component.literal(text.substring(0, localStart)).withStyle(style))
                    }
                    if (!replaced) {
                        result.append(replacement)
                        replaced = true
                    }
                    if (localEnd < text.length) {
                        result.append(Component.literal(text.substring(localEnd)).withStyle(style))
                    }
                }

                Optional.empty()
            },
            Style.EMPTY
        )

        return if (replaced) result else null
    }

    fun transformChat(original: Component): Component {
        val replacements = buildReplacements()
        if (replacements.isEmpty()) return original

        val pattern = replacements.keys
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        val regex = Regex("\\b(?:$pattern)\\b", RegexOption.IGNORE_CASE)

        var matchedAny = false
        val result: MutableComponent = Component.literal("")

        original.visit(
            FormattedText.StyledContentConsumer<Unit> { style, text ->
                val matches = regex.findAll(text).toList()
                if (matches.isEmpty()) {
                    result.append(Component.literal(text).withStyle(style))
                    return@StyledContentConsumer Optional.empty()
                }

                matchedAny = true
                var offset = 0
                for (match in matches) {
                    if (match.range.first > offset) {
                        result.append(
                            Component.literal(text.substring(offset, match.range.first)).withStyle(style)
                        )
                    }
                    val tag = replacements.entries.first { it.key.equals(match.value, ignoreCase = true) }.value
                    result.append(tag)
                    offset = match.range.last + 1
                }
                if (offset < text.length) {
                    result.append(Component.literal(text.substring(offset)).withStyle(style))
                }
                Optional.empty()
            },
            Style.EMPTY
        )

        return if (matchedAny) result else original
    }

    private fun buildReplacements(): Map<String, Component> {
        val map = linkedMapOf<String, Component>()
        if (HardcodedCosmetics.isEnabled) {
            for ((ign, tag) in CosmeticsSync.allTags()) {
                map[ign] = NametagFormatting.parse(tag)
            }
        }

        val ownTag = CustomNametag.tagText.value
        val localName = Minecraft.getInstance().player?.gameProfile?.name
        if (CustomNametag.isEnabled && ownTag.isNotBlank() && localName != null &&
            !map.keys.any { it.equals(localName, ignoreCase = true) }
        ) {
            map[localName] = NametagFormatting.parse(ownTag)
        }
        return map
    }
}