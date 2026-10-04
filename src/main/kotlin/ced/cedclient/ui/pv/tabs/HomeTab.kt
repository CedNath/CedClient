package ced.cedclient.ui.pv.tabs

import ced.cedclient.features.impl.misc.ProfileViewer
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.ui.pv.HypixelRank
import ced.cedclient.ui.pv.PvCategory
import ced.cedclient.ui.pv.PvData
import ced.cedclient.ui.pv.PvTab
import ced.cedclient.ui.pv.SkinFetcher
import ced.cedclient.ui.pv.SkyblockLeveling
import ced.cedclient.utils.Colors
import ced.cedclient.utils.MinecraftFormatting
import com.google.gson.JsonObject

/**
 * Home tab -- Phase 2. Header (skin face, rank-colored name, last-seen,
 * coins) plus a base-stats grid and a skills/slayers grid with progress
 * bars, laid out as two side-by-side columns under the header -- the shape
 * cedclient's stats-overlay look is going for.
 *
 * Two things here are deliberately approximate, and labeled as such right
 * in the UI rather than presented as exact:
 *  - The stats grid is SkyBlock's fixed "naked" base values (no gear, no
 *    accessories, no pets, no Magical Power). Reproducing a player's real
 *    effective stats needs a full item/accessory/pet stat-calc engine that
 *    doesn't exist in this mod yet -- this is a placeholder for that, not
 *    a finished calculation.
 *  - "Coins" is purse + bank, not a true net worth -- valuing every item in
 *    every inventory/backpack against live bazaar/AH prices is a separate,
 *    much bigger feature.
 *
 * No live online/location status ("Dungeon Hub" etc.) -- Hypixel's
 * /status endpoint needs elevated API-key permissions a personal key
 * doesn't get, so this shows last-seen (from /player's lastLogin/
 * lastLogout) instead of pretending to know current online state.
 *
 * Reads ProfileViewer's cached fields fresh every draw call rather than
 * copying them into local state, same as before -- this tab just reflects
 * whatever's currently there, including mid-fetch (loading) and
 * post-failure (lastError) states.
 */
object HomeTab : PvTab {

    override val id: String = "home"
    override val displayName: String = "Home"
    override val categories: List<PvCategory> = emptyList()

    private const val FACE_SIZE = 56f
    private const val HEADER_HEIGHT = FACE_SIZE + 32f

    override fun draw(x: Float, y: Float, w: Float, h: Float, mouseX: Float, mouseY: Float, category: PvCategory?) {
        when {
            ProfileViewer.loading -> {
                NVGRenderer.text("Loading...", x + 16f, y + 16f, 18f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
                return
            }
            ProfileViewer.lastError != null -> {
                NVGRenderer.text(
                    "Error: ${ProfileViewer.lastError}", x + 16f, y + 16f, 16f, Colors.MINECRAFT_RED.rgba, NVGRenderer.defaultFont
                )
                return
            }
            ProfileViewer.lastUsername == null -> {
                NVGRenderer.text(
                    "No profile loaded -- run /pv [username].", x + 16f, y + 16f, 16f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont
                )
                return
            }
        }

        val uuid = ProfileViewer.lastUuid!!
        val username = ProfileViewer.lastUsername!!
        val profile = PvData.currentProfile()
        val member = PvData.currentMember()
        val player = PvData.currentPlayer()

        drawHeader(x, y, w, uuid, username, profile, member, player)

        val gridY = y + HEADER_HEIGHT
        val gridH = h - HEADER_HEIGHT - 16f
        if (gridH <= 0f) return

        val statsW = w * 0.42f
        drawStats(x + 16f, gridY, statsW - 24f, gridH)
        drawSkills(x + statsW, gridY, w - statsW - 16f, gridH, member)
    }

    private fun drawHeader(
        x: Float, y: Float, w: Float,
        uuid: String, username: String,
        profile: JsonObject?, member: JsonObject?, player: JsonObject?
    ) {
        val faceX = x + 16f
        val faceY = y + 16f

        val face = SkinFetcher.faceFor(uuid)
        if (face != null) {
            NVGRenderer.image(face.handle, face.width, face.height, 8, 8, 8, 8, faceX, faceY, FACE_SIZE, FACE_SIZE, 4f)
        } else {
            NVGRenderer.rect(faceX, faceY, FACE_SIZE, FACE_SIZE, Colors.gray38.rgba, 4f)
        }

        val textX = faceX + FACE_SIZE + 16f
        var textY = faceY

        val rankTag = HypixelRank.tagFor(player)
        if (rankTag != null) {
            val tagWidth = MinecraftFormatting.drawFormatted(
                "$rankTag ", textX, textY, 20f, Colors.WHITE.rgba, NVGRenderer.defaultFont
            )
            NVGRenderer.text(username, textX + tagWidth, textY, 20f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
        } else {
            NVGRenderer.text(username, textX, textY, 20f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
        }
        textY += 24f

        NVGRenderer.text(lastSeenText(player), textX, textY, 13f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)
        textY += 18f

        val purse = member?.let { PvData.purse(it) } ?: 0.0
        val bank = profile?.let { PvData.bankBalance(it) } ?: 0.0
        NVGRenderer.text(
            "Coins: ${"%,.0f".format(purse + bank)}", textX, textY, 14f, Colors.MINECRAFT_GOLD.rgba, NVGRenderer.defaultFont
        )

        val cuteName = profile?.get("cute_name")?.asString
        if (cuteName != null) {
            val label = "Profile: $cuteName"
            val labelW = NVGRenderer.textWidth(label, 13f, NVGRenderer.defaultFont)
            NVGRenderer.text(label, x + w - 16f - labelW, faceY, 13f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)
        }
    }

    private fun lastSeenText(player: JsonObject?): String {
        if (player == null) return "Last seen: unknown"
        val lastLogin = player.get("lastLogin")?.asLong
        val lastLogout = player.get("lastLogout")?.asLong
        val mostRecent = when {
            lastLogin != null && lastLogout != null -> maxOf(lastLogin, lastLogout)
            else -> lastLogin ?: lastLogout
        } ?: return "Last seen: unknown"

        val possiblyOnline = lastLogin != null && (lastLogout == null || lastLogin > lastLogout)
        val ago = System.currentTimeMillis() - mostRecent
        return if (possiblyOnline) "Online as of last data refresh" else "Last seen ${formatDuration(ago)} ago"
    }

    private fun formatDuration(millis: Long): String {
        val minutes = millis / 60000
        return when {
            minutes < 60 -> "${minutes}m"
            minutes < 1440 -> "${minutes / 60}h"
            else -> "${minutes / 1440}d"
        }
    }

    private data class StatRow(val label: String, val value: String, val color: Int)

    /** SkyBlock's fixed base values with no gear/accessories/pets applied -- see the class doc comment. */
    private fun baseStats(): List<StatRow> = listOf(
        StatRow("Health", "100", Colors.MINECRAFT_RED.rgba),
        StatRow("Defence", "0", Colors.MINECRAFT_GREEN.rgba),
        StatRow("Strength", "0", Colors.MINECRAFT_RED.rgba),
        StatRow("Speed", "100", Colors.WHITE.rgba),
        StatRow("Crit Chance", "30%", Colors.MINECRAFT_BLUE.rgba),
        StatRow("Crit Damage", "50%", Colors.MINECRAFT_BLUE.rgba),
        StatRow("Attack Speed", "0%", Colors.MINECRAFT_YELLOW.rgba),
        StatRow("Intelligence", "0", Colors.MINECRAFT_AQUA.rgba),
        StatRow("SC Chance", "20%", Colors.MINECRAFT_AQUA.rgba),
        StatRow("Magic Find", "0", Colors.MINECRAFT_AQUA.rgba),
        StatRow("Pet Luck", "0", Colors.MINECRAFT_LIGHT_PURPLE.rgba),
        StatRow("Ferocity", "0", Colors.MINECRAFT_RED.rgba),
        StatRow("Ability Damage", "0", Colors.MINECRAFT_RED.rgba)
    )

    // Fixed distance from a row's label start to its value -- NOT w-relative.
    // w here is 42% of the whole panel, which on a wide/ultrawide monitor is
    // huge; right-justifying the value against that edge (the original
    // version of this) put it nearly a thousand pixels from its own label.
    // A fixed offset keeps label and value next to each other regardless of
    // how wide the panel actually is.
    private const val STAT_VALUE_OFFSET = 150f

    private fun drawStats(x: Float, y: Float, w: Float, h: Float) {
        NVGRenderer.text("Base Stats", x, y, 16f, Colors.WHITE.rgba, NVGRenderer.defaultFont)

        val valueX = x + STAT_VALUE_OFFSET
        var rowY = y + 24f
        for (stat in baseStats()) {
            if (rowY + 16f > y + h - 22f) break

            NVGRenderer.circle(x + 4f, rowY + 5f, 3f, stat.color)
            NVGRenderer.text(stat.label, x + 14f, rowY, 13f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
            NVGRenderer.text(stat.value, valueX, rowY, 13f, stat.color, NVGRenderer.defaultFont)

            rowY += 19f
        }

        NVGRenderer.drawWrappedString(
            "Base values only -- gear, accessories & pets aren't factored in yet.",
            x, y + h - 30f, w, 11f, Colors.MINECRAFT_DARK_GRAY.rgba, NVGRenderer.defaultFont, 1.2f
        )
    }

    private fun drawSkills(x: Float, y: Float, w: Float, h: Float, member: JsonObject?) {
        NVGRenderer.text("Skills", x, y, 16f, Colors.WHITE.rgba, NVGRenderer.defaultFont)

        if (member == null) {
            NVGRenderer.text("No profile loaded.", x, y + 24f, 13f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)
            return
        }

        val colWidth = (w - 16f) / 2f
        var leftY = y + 24f
        var rightY = y + 24f

        for ((index, skill) in SkyblockLeveling.skills.withIndex()) {
            val xp = SkyblockLeveling.skillXp(member, skill)
            val progress = SkyblockLeveling.levelFor(xp, skill.table, skill.maxLevel)
            val toRight = index % 2 == 1
            val entryX = if (toRight) x + colWidth + 16f else x

            drawSkillRow(entryX, if (toRight) rightY else leftY, colWidth, skill.displayName, progress.level, progress.progress, progress.maxed)

            if (toRight) rightY += 30f else leftY += 30f
        }

        for ((index, slayer) in SkyblockLeveling.slayers.withIndex()) {
            val xp = SkyblockLeveling.slayerXp(member, slayer)
            val progress = SkyblockLeveling.slayerLevelFor(xp, slayer.table)
            val toRight = index % 2 == 1
            val entryX = if (toRight) x + colWidth + 16f else x

            drawSkillRow(entryX, if (toRight) rightY else leftY, colWidth, slayer.displayName, progress.level, progress.progress, progress.maxed)

            if (toRight) rightY += 30f else leftY += 30f
        }
    }

    private fun drawSkillRow(x: Float, y: Float, w: Float, name: String, level: Int, progress: Float, maxed: Boolean) {
        NVGRenderer.text("$name $level", x, y, 13f, Colors.WHITE.rgba, NVGRenderer.defaultFont)

        val barY = y + 16f
        val barColor = if (maxed) Colors.MINECRAFT_GOLD.rgba else Colors.MINECRAFT_GREEN.rgba
        NVGRenderer.rect(x, barY, w, 6f, Colors.gray38.rgba, 3f)

        val fillW = w * progress.coerceIn(0f, 1f)
        if (fillW > 0f) {
            val visibleW = fillW.coerceAtLeast(6f).coerceAtMost(w)
            NVGRenderer.rect(x, barY, visibleW, 6f, barColor, 3f)
        }
    }
}