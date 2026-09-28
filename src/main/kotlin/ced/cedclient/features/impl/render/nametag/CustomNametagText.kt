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
 * and item hover names/lore. The in-world floating tag is NOT handled here
 * anymore -- see [overrideBareName], which EntityMixin calls from inside
 * Entity#getDisplayName() to swap the bare name out before vanilla wraps
 * team prefix/suffix around it, instead of text-splicing the already-
 * assembled nametag the way the functions in this file still do for chat/
 * tab-list/item-lore (those surfaces don't go through getDisplayName(), so
 * splicing is still the right tool there).
 *
 * Two replacement sources, synced taking priority over self (same order
 * every transform-/override- function here uses):
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
        val localName = Minecraft.getInstance().user.name

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
     * Replacement *bare name* Component for [player]'s in-world nametag, or
     * null to leave vanilla's name untouched. Called by EntityMixin's
     * redirect of the `this.getName()` call inside Entity#getDisplayName(),
     * i.e. BEFORE PlayerTeam.formatNameForTeam()/getFormattedName() wraps
     * team prefix + name + suffix around it. Because we're swapping the
     * name out before that wrapping happens, vanilla's own team-prefix/
     * suffix logic runs completely untouched afterwards -- Hypixel's
     * SkyBlock level prefix, lobby rank tags, status-icon suffixes, all of
     * it -- with no splicing or text-matching needed here at all.
     *
     * This replaces the old transformNameTag, which ran AFTER vanilla had
     * already assembled prefix+name+suffix into one Component and had to
     * text-match the real IGN back out of it to know where to splice --
     * fragile, and the exact thing that was silently dropping the level
     * prefix/suffix whenever the match failed.
     *
     * CONFIRMED GAP (was a suspicion, now reproduced in-game): the
     * CosmeticsSync branch below identifies other players by
     * player.gameProfile.name, and SkyBlock fakes that for entities the
     * same way it's confirmed to fake it for tab-list rows -- so this
     * silently returns null for other players there, and the real IGN
     * team-wraps through untouched. Unlike transformTabListName, there's
     * no literal rendered text at *this* call site to fall back to
     * scanning, because we're swapping the bare name out before vanilla
     * assembles anything. See [transformNameTagFallback] below for the fix:
     * it runs later, off the already-assembled render-state nameTag, where
     * the real IGN text (if this function missed it) is still there to
     * find -- same whole-line-splice trick transformTabListName already
     * uses for its own fallback.
     */
    fun overrideBareName(player: Player): Component? {
        if (HardcodedCosmetics.isEnabled) {
            CosmeticsSync.getOverride(player.gameProfile.name)?.tag?.let {
                return NametagFormatting.parse(it)
            }
        }

        val ownTag = CustomNametag.tagText.value
        if (CustomNametag.isEnabled && ownTag.isNotBlank() && player === Minecraft.getInstance().player) {
            return NametagFormatting.parse(ownTag)
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

    /** Legacy formatting code letter/digit that follows a literal '§' (e.g. the 'b' in "§b"). */
    private fun isLegacyCode(c: Char): Boolean = c.lowercaseChar() in "0123456789abcdefklmnor"

    /** One whole-word match in the flattened line, [start, end), with any literal legacy codes directly before it swallowed. */
    private data class Hit(val start: Int, val end: Int, val key: String)

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
        //
        // Hypixel puts legacy formatting codes into the text as LITERAL
        // characters (e.g. "§8[§d336§8] §bkilleur27610 §6ௐ"), so the char
        // right before the name is the 'b' of "§b" -- a letter, which used
        // to fail the left-boundary check and made every match miss. We
        // walk the start back over any "§x" pairs directly before the name
        // first, then do the boundary check. That also swallows the old
        // colour code so it can't leak into the replacement's styling.
        val hits = regex.findAll(fullText).mapNotNull { m ->
            var start = m.range.first
            while (start >= 2 && fullText[start - 2] == '\u00A7' && isLegacyCode(fullText[start - 1])) start -= 2
            val end = m.range.last + 1
            val leftOk = start == 0 || !isNameChar(fullText[start - 1])
            val rightOk = end >= fullText.length || !isNameChar(fullText[end])
            if (leftOk && rightOk) Hit(start, end, m.value) else null
        }.toList()
        if (hits.isEmpty()) return null

        val result: MutableComponent = Component.literal("")
        var cursor = 0
        for (hit in hits) {
            appendSlice(result, runs, cursor, hit.start)
            val replacement = replacements.entries.first { it.key.equals(hit.key, ignoreCase = true) }.value
            result.append(replacement)
            cursor = hit.end
        }
        appendSlice(result, runs, cursor, fullText.length)
        return result
    }

    private fun spliceOverName(original: Component, needle: String, replacement: Component): Component? =
        spliceAll(original, mapOf(needle to replacement))

    /**
     * Fallback for the in-world floating nametag, called by
     * PlayerRendererMixin's TAIL injection against the render state's
     * already-fully-assembled `nameTag` Component (team prefix/suffix and
     * all). [overrideBareName] is the primary path and should be preferred
     * whenever it resolves -- this only exists to catch the case where it
     * missed because SkyBlock faked player.gameProfile.name (see the
     * CONFIRMED GAP note on [overrideBareName]).
     *
     * By the time PlayerRendererMixin sees [original], one of two things
     * is true: either overrideBareName already matched and [original]
     * contains our replacement text (not the real IGN) -- in which case
     * every key here misses and this is a harmless no-op -- or it missed,
     * the real IGN team-wrapped straight through untouched, and it's still
     * sitting there as literal text for this whole-line splice to find.
     * Reuses the exact same [spliceAll] + [buildReplacements] plumbing
     * transformChat/transformItemText/transformTabListName's fallback
     * already use, rather than a separate one-off matcher.
     */
    fun transformNameTagFallback(original: Component): Component? =
        spliceAll(original, buildReplacements())

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
    // TEMP DIAGNOSTIC -- delete once tooltip replacement is confirmed
    // working. Only reprints when the incoming text actually changes, so
    // it won't spam once-per-frame while a tooltip is held open.
    private var lastDebugText: String? = null

    fun transformItemText(original: Component): Component {
        val screen = Minecraft.getInstance().screen
        if (screen is net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>) {
            val title = screen.title.string.lowercase()
            if (FUNCTIONAL_MENU_TITLES.any { title.contains(it) }) return original
        }
        val result = transformChat(original)

        val text = original.string
        if (text != lastDebugText) {
            lastDebugText = text
            val keys = buildReplacements().keys
   //         println(
   //            "CedClient tooltip debug: text=\"$text\" replacementKeys=$keys " +
   //                   "matched=${result !== original}"
   //     )
        }

        return result
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
        // matching comment in transformTabListName above.
        val localName = Minecraft.getInstance().user.name
        if (CustomNametag.isEnabled && ownTag.isNotBlank() && localName != null &&
            !map.keys.any { it.equals(localName, ignoreCase = true) }
        ) {
            map[localName] = NametagFormatting.parse(ownTag)
        }
        return map
    }
}