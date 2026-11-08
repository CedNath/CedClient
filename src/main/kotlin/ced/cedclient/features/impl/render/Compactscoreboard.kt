package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.nametag.CustomNametagText
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.impl.misc.Theme
import ced.cedclient.ui.Palette
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerScoreEntry
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Compact sidebar scoreboard on the shared HUD card (HudCard): Inter text in Hypixel's own colours, a bold centred
 * title, empty spacer lines squashed to half height, and the server address line swapped for a small CedClient footer.
 *
 * Replaces vanilla's scoreboard HUD element (Fabric HudElementRegistry.replaceElement), so
 * no mixin or Minecraft class names are involved: with the module off, or when there's no
 * sidebar to draw, the original vanilla element runs exactly as before. Lines go through
 * CustomNametagText, so custom nametags / synced cosmetics apply here too.
 */
object CompactScoreboard : Module(
    "Compact Scoreboard",
    Category.HUD,
    "Restyles the sidebar scoreboard into a compact panel matching Compact Tab",
    defaultEnabled = false
) {
    private val opacity = registerSetting(NumberSetting("Panel Opacity", 100.0, 0.0, 100.0, 1.0, "How solid the card is (percent)."))
    private val scale = registerSetting(NumberSetting("Scale", 1.0, 0.5, 2.0, 0.05))
    private val position = registerSetting(DropdownSetting("Position", listOf("RIGHT", "LEFT"), "RIGHT"))
    private val verticalOffset = registerSetting(NumberSetting("Vertical Offset", 0.0, -150.0, 150.0, 1.0))
    private val showTitle = registerSetting(BooleanSetting("Show Title", true))
    private val showScores = registerSetting(
        BooleanSetting(
            "Show Scores", false,
            "Show the red score numbers. Hypixel's are meaningless, so this is off by default."
        )
    )

    private const val MAX_LINES = 15
    /** Shown in place of the server address line ("www.hypixel.net"), in the accent. */
    private const val FOOTER = "CedClient"

    private val BLANK_COLOR: Pattern = Pattern.compile("\u00A7.")
    private val BLANK_INVISIBLE: Pattern = Pattern.compile("[\\p{Cf}\\p{Z}\\s]")
    private val SERVER_LINE: Pattern = Pattern.compile("(?i)\\b[\\w.-]*hypixel\\.net\\b")

    private class Line(val text: Component, val score: String, val blank: Boolean, val footer: Boolean)

    private var cachedTick = Long.MIN_VALUE
    private var cachedObjective: Objective? = null
    private var cachedLines: List<Line> = emptyList()
    private var cachedTitle: Component? = null

    init {
        // Wrap vanilla's scoreboard element: ours when it applies, otherwise the original.
        HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD) { vanilla ->
            HudElement { graphics, tickCounter ->
                if (!render(graphics)) vanilla.extractRenderState(graphics, tickCounter)
            }
        }
    }

    private fun isBlank(c: Component): Boolean =
        BLANK_INVISIBLE.matcher(BLANK_COLOR.matcher(c.string).replaceAll("")).replaceAll("").isEmpty()

    /** @return true if the compact panel was drawn, false to let vanilla draw the scoreboard. */
    private fun render(g: GuiGraphicsExtractor): Boolean {
        if (!isEnabled) return false
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return false

        val sb = level.scoreboard
        val objective: Objective = sb.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return false

        // The sidebar only changes on a tick: rebuild the lines once per game tick, not every frame (profiled hot).
        val tick = level.gameTime
        if (tick != cachedTick || objective !== cachedObjective) {
            cachedTick = tick
            cachedObjective = objective
            // Same ordering vanilla uses: highest score first, ties by name; "#"-prefixed owners are hidden.
            val entries: List<PlayerScoreEntry> = sb.listPlayerScores(objective)
                .filter { !it.owner().startsWith("#") }
                .sortedWith(
                    compareByDescending<PlayerScoreEntry> { it.value() }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.owner() }
                )
                .take(MAX_LINES)
            cachedLines = entries.map { en ->
                val team = sb.getPlayersTeam(en.owner())
                val raw: Component = if (team != null) {
                    Component.empty().append(team.playerPrefix).append(en.ownerName()).append(team.playerSuffix)
                } else {
                    en.ownerName()
                }
                val text = CustomNametagText.transformChat(raw)
                val score = if (showScores.value) en.value().toString() else ""
                val plain = BLANK_COLOR.matcher(text.string).replaceAll("")
                Line(text, score, isBlank(text), SERVER_LINE.matcher(plain).find())
            }
            cachedTitle = if (showTitle.value) CustomNametagText.transformChat(objective.displayName) else null
        }
        val lines = cachedLines
        if (lines.isEmpty()) return false

        // The shared HUD card (HudCard): Inter text in Hypixel's own colours, bold centred title, half-height gaps,
        // and the server address line swapped for a small CedClient footer.
        val s = scale.value.toFloat()
        val size = 9f * s
        val titleSize = 10.5f * s
        val lineH = 11f * s
        val blankH = 5f * s
        val padX = 10f * s
        val padY = 8f * s
        val titleGap = 5f * s

        val title: Component? = cachedTitle
        val titleW = title?.let { HudCard.width(it, titleSize) } ?: 0f
        var contentW = titleW
        for (l in lines) {
            val w = if (l.footer) HudCard.width(FOOTER, size, bold = true)
            else HudCard.width(l.text, size) + if (l.score.isNotEmpty()) 8f * s + HudCard.width(l.score, size) else 0f
            contentW = max(contentW, w)
        }
        var bodyH = 0f
        for (l in lines) bodyH += if (l.blank) blankH else lineH
        val panelW = HudCard.steady("Compact Scoreboard", contentW + padX * 2, s)
        val panelH = padY * 2 + (if (title != null) titleSize + titleGap else 0f) + bodyH - (lineH - size)

        val screenW = mc.window.guiScaledWidth
        val screenH = mc.window.guiScaledHeight
        val x0 = if (position.value.equals("LEFT", ignoreCase = true)) 4 else (screenW - panelW - 4f).toInt()
        val y0 = ((screenH - panelH) / 2f + verticalOffset.value.toFloat()).toInt()

        HudCard.draw(g, x0, y0) {
            HudCard.card(panelW, panelH, s, (opacity.value / 100.0).toFloat().coerceIn(0f, 1f), label = "Compact Scoreboard")
            var y = padY
            if (title != null) {
                HudCard.componentText(title, (panelW - titleW) / 2f, y, titleSize)
                y += titleSize + titleGap
            }
            for (l in lines) {
                when {
                    l.blank -> { y += blankH; continue }
                    l.footer -> HudCard.text(FOOTER, (panelW - HudCard.width(FOOTER, size, bold = true)) / 2f, y, size, Theme.hudAccentRgba, bold = true, remap = false)
                    else -> {
                        HudCard.componentText(l.text, padX, y, size)
                        if (l.score.isNotEmpty()) HudCard.text(l.score, panelW - padX - HudCard.width(l.score, size), y, size, Palette.BAD_SEE, remap = false)
                    }
                }
                y += lineH
            }
        }
        return true
    }
}
