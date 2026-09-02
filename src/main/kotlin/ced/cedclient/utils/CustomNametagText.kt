package ced.cedclient.utils

import ced.cedclient.features.impl.render.CustomNametag
import ced.cedclient.features.impl.render.HardcodedCosmetics
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style

import java.util.Optional

/**
 * Shared logic for CustomNametag's 2D-text surfaces (chat, tab list) -- the
 * in-world floating tag lives in CustomNametagRenderer, this handles the
 * rest via ChatComponentMixin / PlayerInfoMixin.
 *
 * Two replacement targets, matched by literal username, hardcoded taking
 * priority over self (same order CustomNametagRenderer uses):
 *  - CustomNametag.HARDCODED_USERNAME ("CedNath") -> CustomNametag.HARDCODED_TAG,
 *    regardless of CustomNametag's own module toggle, but only while
 *    HardcodedCosmetics.isEnabled -- lets other people running this client
 *    turn the cosmetic off for themselves.
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
        if (HardcodedCosmetics.isEnabled &&
            profileName.equals(CustomNametag.HARDCODED_USERNAME, ignoreCase = true)
        ) {
            return Component.literal(CustomNametag.HARDCODED_TAG)
        }

        val ownTag = CustomNametag.tagText.value
        val localName = Minecraft.getInstance().player?.gameProfile?.name
        if (CustomNametag.isEnabled && ownTag.isNotBlank() && profileName.equals(localName, ignoreCase = true)) {
            return Component.literal(ownTag)
        }

        return null
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
            map[CustomNametag.HARDCODED_USERNAME] = Component.literal(CustomNametag.HARDCODED_TAG)
        }

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