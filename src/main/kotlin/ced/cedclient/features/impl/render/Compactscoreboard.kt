package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.nametag.CustomNametagText
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
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
 * Compact sidebar scoreboard in the same look as CompactTab: a dark translucent rounded
 * panel, a divider under the title, tight line spacing, and empty spacer lines squashed to
 * half height.
 *
 * Replaces vanilla's scoreboard HUD element (Fabric HudElementRegistry.replaceElement), so
 * no mixin or Minecraft class names are involved: with the module off, or when there's no
 * sidebar to draw, the original vanilla element runs exactly as before. Lines go through
 * CustomNametagText, so custom nametags / synced cosmetics apply here too.
 */
object CompactScoreboard : Module(
    "Compact Scoreboard",
    Category.Render,
    "Restyles the sidebar scoreboard into a compact panel matching Compact Tab",
    defaultEnabled = true
) {
    private val opacity = registerSetting(NumberSetting("Panel Opacity", 65.0, 0.0, 100.0, 1.0))
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

    private const val BG_RGB = 0x0B0D13
    private const val DIVIDER = 0x44454a58
    private const val NAME = 0xFFE8ECF2.toInt()
    private const val SCORE = 0xFFFF5555.toInt()

    private const val LINE_H = 10
    private const val BLANK_H = 5
    private const val PAD_X = 6
    private const val PAD_Y = 4
    private const val MAX_LINES = 15

    private val BLANK_COLOR: Pattern = Pattern.compile("\u00A7.")
    private val BLANK_INVISIBLE: Pattern = Pattern.compile("[\\p{Cf}\\p{Z}\\s]")

    private class Line(val text: Component, val score: String, val blank: Boolean, val width: Int)

    init {
        // Wrap vanilla's scoreboard element: ours when it applies, otherwise the original.
        HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD) { vanilla ->
            HudElement { graphics, tickCounter ->
                if (!render(graphics)) vanilla.extractRenderState(graphics, tickCounter)
            }
        }
    }

    private fun bgPanel(): Int {
        val a = (opacity.value.coerceIn(0.0, 100.0) * 2.55).roundToInt()
        return (a shl 24) or BG_RGB
    }

    private fun isBlank(c: Component): Boolean =
        BLANK_INVISIBLE.matcher(BLANK_COLOR.matcher(c.string).replaceAll("")).replaceAll("").isEmpty()

    /** @return true if the compact panel was drawn, false to let vanilla draw the scoreboard. */
    private fun render(g: GuiGraphicsExtractor): Boolean {
        if (!isEnabled) return false
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return false
        val tr = mc.font

        val sb = level.scoreboard
        val objective: Objective = sb.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return false

        // Same ordering vanilla uses: highest score first, ties by name; "#"-prefixed owners are hidden.
        val entries: List<PlayerScoreEntry> = sb.listPlayerScores(objective)
            .filter { !it.owner().startsWith("#") }
            .sortedWith(
                compareByDescending<PlayerScoreEntry> { it.value() }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.owner() }
            )
            .take(MAX_LINES)
        if (entries.isEmpty()) return false

        val lines = entries.map { en ->
            val team = sb.getPlayersTeam(en.owner())
            val raw: Component = if (team != null) {
                Component.empty().append(team.playerPrefix).append(en.ownerName()).append(team.playerSuffix)
            } else {
                en.ownerName()
            }
            val text = CustomNametagText.transformChat(raw)
            val score = if (showScores.value) en.value().toString() else ""
            Line(text, score, isBlank(text), tr.width(text))
        }

        val title: Component? = if (showTitle.value) CustomNametagText.transformChat(objective.displayName) else null
        val titleW = title?.let { tr.width(it) } ?: 0

        var contentW = titleW
        for (l in lines) {
            val w = l.width + if (l.score.isNotEmpty()) 8 + tr.width(l.score) else 0
            contentW = max(contentW, w)
        }

        val titleH = if (title != null) LINE_H + 3 else 0
        var bodyH = 0
        for (l in lines) bodyH += if (l.blank) BLANK_H else LINE_H
        val panelW = contentW + PAD_X * 2
        val panelH = PAD_Y * 2 + titleH + bodyH

        val s = scale.value.toFloat()
        val screenW = mc.window.guiScaledWidth
        val screenH = mc.window.guiScaledHeight
        val scaledW = panelW * s
        val scaledH = panelH * s
        val x0 = if (position.value.equals("LEFT", ignoreCase = true)) 4f else screenW - scaledW - 4f
        val y0 = (screenH - scaledH) / 2f + verticalOffset.value.toFloat()

        g.pose().pushMatrix()
        g.pose().translate(x0, y0)
        g.pose().scale(s, s)

        roundRect(g, 0, 0, panelW, panelH, bgPanel())

        var y = PAD_Y
        if (title != null) {
            g.text(tr, title, (panelW - titleW) / 2, y, NAME)
            y += LINE_H
            g.fill(PAD_X, y + 1, panelW - PAD_X, y + 2, DIVIDER)
            y += 3
        }
        for (l in lines) {
            if (l.blank) {
                y += BLANK_H
                continue
            }
            g.text(tr, l.text, PAD_X, y, NAME)
            if (l.score.isNotEmpty()) {
                g.text(tr, l.score, panelW - PAD_X - tr.width(l.score), y, SCORE)
            }
            y += LINE_H
        }

        g.pose().popMatrix()
        return true
    }

    // Same rounded-corner trick CompactTab uses.
    private fun roundRect(ctx: GuiGraphicsExtractor, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        ctx.fill(x1 + 2, y1, x2 - 2, y1 + 1, color)
        ctx.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 2, color)
        ctx.fill(x1, y1 + 2, x2, y2 - 2, color)
        ctx.fill(x1 + 1, y2 - 2, x2 - 1, y2 - 1, color)
        ctx.fill(x1 + 2, y2 - 1, x2 - 2, y2, color)
    }
}