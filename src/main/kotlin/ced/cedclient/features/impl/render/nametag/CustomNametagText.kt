package ced.cedclient.features.impl.render.nametag

import ced.cedclient.features.impl.render.nametag.CustomNametag
import ced.cedclient.features.impl.render.HardcodedCosmetics
import ced.cedclient.state.CosmeticsSync
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.world.entity.player.Player

import java.util.Optional

/**
 * Shared logic for all of CustomNametag's text surfaces -- chat, tab list,
 * and (via [transformNameTag]) the in-world floating tag, which
 * PlayerRendererMixin calls into and applies to the render state's nameTag
 * Component directly.
 *
 * Two replacement sources, synced taking priority over self (same order
 * every transform* function here uses):
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
        // Minecraft.getInstance().user.name (the login session's own username)
        // rather than localPlayer.gameProfile.name -- SkyBlock fakes the
        // latter for your own entity/row too (same trick documented above
        // for other players' CosmeticsSync lookups), so it's not reliable
        // either as a match key or as literal text to splice onto. The
        // session username is set once locally at login and never touched
        // by anything the server sends, so it's unaffected by that fakery.
        val localName = Minecraft.getInstance().user?.name ?: localPlayer?.gameProfile?.name

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
     * In-world floating nametag for [player] -- splices the resolved tag
     * text over just the name portion of [original] (vanilla's already-
     * computed nametag Component, team prefix/suffix and all), the same
     * way [transformTabListName] does for tab-list rows. This is what
     * keeps Hypixel's network-level prefix (e.g. "[383]") and status-icon
     * suffix (e.g. "[❤6]") intact instead of the old full-swap behavior,
     * which discarded them along with the vanilla name.
     *
     * Falls back to a full swap (just the parsed tag, nothing else) only
     * when the matched name can't actually be located as literal text in
     * [original] -- spliceOverName returns null in that case since there's
     * nothing to splice onto.
     *
     * Returns null when nothing should override this player's tag (the
     * caller keeps vanilla's own nameTag untouched).
     */
    fun transformNameTag(player: Player, original: Component): Component? {
        if (HardcodedCosmetics.isEnabled) {
            val profileName = player.gameProfile.name
            CosmeticsSync.getOverride(profileName)?.tag?.let { syncedTag ->
                val tag = NametagFormatting.parse(syncedTag)
                return spliceOverName(original, profileName, tag) ?: tag
            }

            // Fallback: SkyBlock fakes the entity's GameProfile name (same
            // trick confirmed for tab-list rows above), so the direct
            // lookup misses there -- the real IGN is still present as
            // literal text in vanilla's rendered nametag though.
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
        if (CustomNametag.isEnabled && ownTag.isNotBlank() && player === localPlayer) {
            // Session username, not localPlayer.gameProfile.name -- on
            // SkyBlock the latter is faked for your own entity the same way
            // it's faked for other players' (see the CosmeticsSync fallback
            // above and the class doc comment), so splicing against it as
            // the needle silently never matches anything in `original`'s
            // text. That's what was causing the fallback below to discard
            // Hypixel's own "[383]"-style level prefix and status-icon
            // suffix and fall back to a bare full-text swap instead of
            // preserving them. The session name is set locally at login and
            // never touched by server-sent data, so it's always the real,
            // literally-rendered name.
            val localName = Minecraft.getInstance().user?.name
                ?: localPlayer?.gameProfile?.name
                ?: return null
            val tag = NametagFormatting.parse(ownTag)
            return spliceOverName(original, localName, tag) ?: tag
        }

        return null
    }

    /**
     * One leaf text run from [Component.visit], with its absolute start
     * offset in the fully-flattened line. Recording this lets us splice a
     * replacement into the flattened string and then rebuild styled runs
     * from the *original* boundaries afterwards, instead of matching and
     * splicing run-by-run.
     *
     * Matching run-by-run (the old approach) is fragile: if the server
     * splits styling into a separate run right at a word boundary --
     * common for Hypixel's rank-prefixed chat, where the coloured IGN is
     * frequently its own sibling component split off from the "[RANK] "
     * prefix -- a per-run splice can end up rebuilding that boundary
     * incorrectly (e.g. dropping the space between the rank and the
     * name). Flattening first and matching once over the whole line
     * avoids that class of bug entirely, since every character outside
     * the match is carried over verbatim. This mirrors how NoammAddons
     * (see TextReplacer.kt) and Athena do their own chat name splicing.
     */
    private data class Run(val start: Int, val text: String, val style: Style)

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private fun flatten(component: Component): Pair<String, List<Run>> {
        val runs = mutableListOf<Run>()
        val sb = StringBuilder()
        component.visit(
            FormattedText.StyledContentConsumer<Unit> { style, text ->
                if (text.isNotEmpty()) {
                    runs.add(Run(sb.length, text, style))
                    sb.append(text)
                }
                Optional.empty()
            },
            Style.EMPTY
        )
        return sb.toString() to runs
    }

    /** Appends the `[from, to)` slice of the flattened text, split back along the original run boundaries so each piece keeps its own style. */
    private fun appendSlice(result: MutableComponent, runs: List<Run>, from: Int, to: Int) {
        if (from >= to) return
        for (run in runs) {
            val runEnd = run.start + run.text.length
            if (runEnd <= from) continue
            if (run.start >= to) break
            val localStart = (from - run.start).coerceIn(0, run.text.length)
            val localEnd = (to - run.start).coerceIn(0, run.text.length)
            if (localEnd > localStart) {
                result.append(Component.literal(run.text.substring(localStart, localEnd)).withStyle(run.style))
            }
        }
    }

    /**
     * Replaces every whole-word occurrence of any key in [replacements]
     * found in [original]'s flattened text with its mapped Component,
     * preserving everything else about [original] -- rank prefix/suffix,
     * surrounding colours, hover/click events, and exact spacing --
     * because matching happens once over the whole line rather than
     * per style-run (see [Run]/[flatten] above).
     *
     * Returns null if nothing in [replacements] is actually present as
     * literal text anywhere in [original] (e.g. a CosmeticsSync entry
     * keyed by real IGN on a server that fakes the profile name so it
     * never shows up as text) -- callers fall back to a full swap in
     * that case, since there's nothing to splice onto.
     */
    private fun spliceAll(original: Component, replacements: Map<String, Component>): Component? {
        if (replacements.isEmpty()) return null
        val (fullText, runs) = flatten(original)
        if (fullText.isEmpty()) return null

        val pattern = replacements.keys
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)

        // Manual left/right boundary check (rather than \b) so it stays
        // correct regardless of what's adjacent to the match once it's
        // matched against the *whole* line instead of a single run.
        val matches = regex.findAll(fullText).filter { m ->
            val leftOk = m.range.first == 0 || !isNameChar(fullText[m.range.first - 1])
            val rightOk = m.range.last + 1 >= fullText.length || !isNameChar(fullText[m.range.last + 1])
            leftOk && rightOk
        }.toList()
        if (matches.isEmpty()) return null

        val result: MutableComponent = Component.literal("")
        var cursor = 0
        for (match in matches) {
            appendSlice(result, runs, cursor, match.range.first)
            val replacement = replacements.entries.first { it.key.equals(match.value, ignoreCase = true) }.value
            result.append(replacement)
            cursor = match.range.last + 1
        }
        appendSlice(result, runs, cursor, fullText.length)
        return result
    }

    private fun spliceOverName(original: Component, needle: String, replacement: Component): Component? =
        spliceAll(original, mapOf(needle to replacement))

    fun transformChat(original: Component): Component {
        val replacements = buildReplacements()
        return spliceAll(original, replacements) ?: original
    }

    // Menus where a slot's item name/lore is read back out to figure out
    // which player it represents, rather than just being cosmetic text --
    // e.g. the dungeon "Leap" ability's teammate picker matches slots by
    // the real IGN in the item's hover name. Rewriting that text there
    // (via transformItemText below) broke that matching. Title-keyword
    // match, case-insensitive -- add more entries here if another menu
    // turns out to have the same problem.
    private val FUNCTIONAL_MENU_TITLES = listOf("leap")

    /**
     * Same idea as [transformChat] but for item hover names / lore
     * specifically -- those show up inside functional menus as well as
     * purely cosmetic ones (coop/party/guild lists), and a few of those
     * menus rely on the item's real name to know what it represents. This
     * leaves the text alone while one of [FUNCTIONAL_MENU_TITLES] is the
     * open screen, so the swap stays cosmetic-only where it's safe.
     */
    fun transformItemText(original: Component): Component {
        val screen = Minecraft.getInstance().screen
        if (screen is net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>) {
            val title = screen.title.string.lowercase()
            if (FUNCTIONAL_MENU_TITLES.any { title.contains(it) }) return original
        }
        return transformChat(original)
    }

    private fun buildReplacements(): Map<String, Component> {
        val map = linkedMapOf<String, Component>()
        if (HardcodedCosmetics.isEnabled) {
            for ((ign, tag) in CosmeticsSync.allTags()) {
                map[ign] = NametagFormatting.parse(tag)
            }
        }

        val ownTag = CustomNametag.tagText.value
        // Session username preferred over gameProfile.name -- see the
        // matching comment in transformNameTag above.
        val localName = Minecraft.getInstance().user?.name
            ?: Minecraft.getInstance().player?.gameProfile?.name
        if (CustomNametag.isEnabled && ownTag.isNotBlank() && localName != null &&
            !map.keys.any { it.equals(localName, ignoreCase = true) }
        ) {
            map[localName] = NametagFormatting.parse(ownTag)
        }
        return map
    }
}