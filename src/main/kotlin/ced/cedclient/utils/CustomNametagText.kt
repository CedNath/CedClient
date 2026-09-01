package ced.cedclient.utils

import ced.cedclient.features.impl.render.CustomNametag
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * Shared logic for CustomNametag's 2D-text surfaces (chat, tab list) -- the
 * in-world floating tag lives in CustomNametagRenderer, this handles the
 * rest via ChatComponentMixin / PlayerInfoMixin.
 *
 * Two replacement targets, matched by literal username, hardcoded taking
 * priority over self (same order CustomNametagRenderer uses):
 *  - CustomNametag.HARDCODED_USERNAME ("CedNath") -> always
 *    CustomNametag.HARDCODED_TAG, regardless of the module toggle.
 *  - the local player's own username -> CustomNametag.tagText.value, only
 *    while the module is enabled and the field isn't blank.
 */
object CustomNametagText {

    /**
     * Tab list row for [profileName] -- a full swap, since there the whole
     * value IS the name. Returns null when nothing should override it (the
     * caller keeps whatever vanilla/the server supplied).
     */
    fun transformTabListName(profileName: String): Component? {
        if (profileName.equals(CustomNametag.HARDCODED_USERNAME, ignoreCase = true)) {
            return Component.literal(CustomNametag.HARDCODED_TAG)
        }

        val ownTag = CustomNametag.tagText.value
        val localName = Minecraft.getInstance().player?.gameProfile?.name
        if (CustomNametag.isEnabled && ownTag.isNotBlank() && profileName.equals(localName, ignoreCase = true)) {
            return Component.literal(ownTag)
        }

        return null
    }

    /**
     * Chat line -- substring swap of any matched username (whole-word,
     * case-insensitive), everything else passed through as-is. Only the
     * matched span(s) get rebuilt as plain literal text, so unrelated
     * formatting on the rest of the line survives; the matched span itself
     * takes on the replacement tag's own styling instead of the original's.
     */
    fun transformChat(original: Component): Component {
        val replacements = buildReplacements()
        if (replacements.isEmpty()) return original

        val text = original.string
        val pattern = replacements.keys
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        val regex = Regex("\\b(?:$pattern)\\b", RegexOption.IGNORE_CASE)
        val matches = regex.findAll(text).toList()
        if (matches.isEmpty()) return original

        var result: MutableComponent = Component.literal("")
        var offset = 0
        for (match in matches) {
            if (match.range.first > offset) {
                result = result.append(Component.literal(text.substring(offset, match.range.first)))
            }
            val tag = replacements.entries.first { it.key.equals(match.value, ignoreCase = true) }.value
            result = result.append(tag)
            offset = match.range.last + 1
        }
        if (offset < text.length) {
            result = result.append(Component.literal(text.substring(offset)))
        }
        return result
    }

    private fun buildReplacements(): Map<String, Component> {
        val map = linkedMapOf<String, Component>()
        map[CustomNametag.HARDCODED_USERNAME] = Component.literal(CustomNametag.HARDCODED_TAG)

        val ownTag = CustomNametag.tagText.value
        val localName = Minecraft.getInstance().player?.gameProfile?.name
        if (CustomNametag.isEnabled && ownTag.isNotBlank() && localName != null &&
            !localName.equals(CustomNametag.HARDCODED_USERNAME, ignoreCase = true)
        ) {
            map[localName] = Component.literal(ownTag)
        }
        return map
    }
}