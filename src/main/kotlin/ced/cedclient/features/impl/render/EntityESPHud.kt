//#if CHEAT
package ced.cedclient.features.impl.render

import ced.cedclient.config.CedPaths
import ced.cedclient.features.impl.misc.Theme
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.ui.Palette
import com.google.gson.Gson
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.io.File

private data class HudPosition(val x: Int, val y: Int, val scale: Float = 1.0f)

/**
 * Summary of EntityESP's current scan (counts per category, nearest entities). A compact HUD: see-through halo
 * text, or the shared card when the HUD editor turns it on (HudCard).
 */
object EntityESPHud : HudElement {

    override val label: String = "Entity ESP"

    private val hostileColor = 0xFFFF5555.toInt()
    private val passiveColor = 0xFF55FF55.toInt()
    private val playerColor = 0xFF5599FF.toInt()

    private const val MAX_LIST_ROWS = 20
    private const val MIN_SCALE = 0.5f
    private const val MAX_SCALE = 3.0f

    override var panelX: Int = 2
    override var panelY: Int = 111
    override var panelScale: Float = 0.6f
    override fun resetPosition() { panelX = 2; panelY = 111; panelScale = 0.6f }

    // logical (unscaled) size — updated every render() call, used for hit-testing
    // note: on-screen rendered size is this * panelScale
    override var lastWidth: Int = 190
        private set
    override var lastHeight: Int = 60
        private set

    private val gson = Gson()
    private val saveFile by lazy {
        CedPaths.hud("entity_esp")
    }

    // mirrors InventoryButtonManager's ensureLoaded() pattern: load once, lazily,
    // the first time we actually need the data, rather than relying on something
    // else remembering to call load() during mod init.
    private var loadedOnce = false

    fun ensureLoaded() {
        if (!loadedOnce) {
            load()
            loadedOnce = true
        }
    }

    fun load() {
        try {
            if (saveFile.exists()) {
                val pos = gson.fromJson(saveFile.readText(), HudPosition::class.java)
                if (pos != null) {
                    panelX = pos.x
                    panelY = pos.y
                    panelScale = pos.scale.coerceIn(MIN_SCALE, MAX_SCALE)
                }
            }
        } catch (e: Exception) {
            println("[EntityESPHud] Failed to load position: ${e.message}")
        }
    }

    override fun save() {
        try {
            saveFile.parentFile?.mkdirs()
            saveFile.writeText(gson.toJson(HudPosition(panelX, panelY, panelScale)))
        } catch (e: Exception) {
            println("[EntityESPHud] Failed to save position: ${e.message}")
        }
    }

    override fun adjustScale(delta: Float) {
        panelScale = (panelScale + delta).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    fun render(g: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
        if (!EntityESP.isEnabled) return
        if (!EntityESP.hudPanelEnabled) return
        renderInternal(g)
    }

    private val ACCENT_COLOR get() = Theme.hudAccentRgba

    /** Also used by MasterHudEditScreen / HudEditScreen to draw a live preview while dragging/scaling. */
    override fun renderInternal(g: GuiGraphicsExtractor) {
        ensureLoaded()
        renderNVG(g)
    }

    private fun categoryTag(category: MobCategoryType) = when (category) {
        MobCategoryType.HOSTILE -> "Ho"
        MobCategoryType.PASSIVE -> "Pa"
        MobCategoryType.PLAYER -> "Pl"
    }

    private fun categoryColor(category: MobCategoryType) = when (category) {
        MobCategoryType.HOSTILE -> hostileColor
        MobCategoryType.PASSIVE -> passiveColor
        MobCategoryType.PLAYER -> playerColor
    }

    private fun renderNVG(g: GuiGraphicsExtractor) {
        val fontSize = 12f * panelScale

        val entities = EntityESP.scannedEntities
        val hostileCount = entities.count { it.category == MobCategoryType.HOSTILE }
        val passiveCount = entities.count { it.category == MobCategoryType.PASSIVE }
        val playerCount = entities.count { it.category == MobCategoryType.PLAYER }
        val chunkRadius = (EntityESP.maxDistanceBlocks / 16.0).toInt().coerceAtLeast(1)

        val header = "Entity ESP"
        val badgeText = if (entities.isEmpty()) "NONE" else "${entities.size} NEARBY"
        val scanLineText = "Scan: ${chunkRadius}x$chunkRadius [ON]"
        val truncated = entities.size > MAX_LIST_ROWS
        val truncatedText = "+${entities.size - MAX_LIST_ROWS} more"

        val paddingX = 10f * panelScale
        val paddingY = 8f * panelScale
        val headerGap = 5f * panelScale
        val lineHeight = fontSize + 7f * panelScale
        val dotIndent = 10f * panelScale

        // Measured with HudCard.width: outside the NanoVG frame NVGRenderer.textWidth uses a stale transform.
        fun w(s: String, bold: Boolean = false) = HudCard.width(s, fontSize, bold)
        val badgeWidth = w(badgeText)
        val headerRowHeight = fontSize
        val headerRowWidth = w(header, bold = true) + 8f * panelScale + badgeWidth

        var contentWidth = maxOf(
            headerRowWidth,
            w("Hostile $hostileCount") + dotIndent,
            w("Passive $passiveCount") + dotIndent,
            w("Player $playerCount") + dotIndent,
            w(scanLineText)
        )
        for (e in entities.take(MAX_LIST_ROWS)) {
            val tag = "[${categoryTag(e.category)}]"
            val distanceText = "%.1fm".format(e.distance)
            val rowWidth = w(tag) + 4f * panelScale + w(e.name) + 8f * panelScale + w(distanceText)
            if (rowWidth > contentWidth) contentWidth = rowWidth
        }
        if (truncated) contentWidth = maxOf(contentWidth, w(truncatedText))

        val width = contentWidth + paddingX * 2
        val listRows = entities.size.coerceAtMost(MAX_LIST_ROWS)
        val summaryRows = 4
        val height = paddingY * 2 + headerRowHeight + headerGap + 2f * panelScale + lineHeight * summaryRows +
                (if (listRows > 0 || truncated) 2f * panelScale else 0f) + lineHeight * listRows +
                (if (truncated) lineHeight else 0f)

        lastWidth = (width / panelScale).toInt()
        lastHeight = (height / panelScale).toInt()

        // HudCard.draw: shared canvas setup, the card only when the HUD editor turns it on, halo text otherwise.
        HudCard.draw(g, panelX, panelY) {
            HudCard.card(width, height, panelScale, label = label)

            HudCard.text(header, paddingX, paddingY, fontSize, Palette.TEXT, bold = true)
            // Count in the accent, "none" muted, at the header's right edge (was a filled pill).
            HudCard.text(badgeText, width - paddingX - badgeWidth, paddingY, fontSize, if (entities.isEmpty()) Palette.MUTED_SEE else ACCENT_COLOR, remap = false)

            val dividerY = paddingY + headerRowHeight + headerGap
            HudCard.divider(paddingX, dividerY, width - paddingX * 2, panelScale)

            var y = dividerY + 2f * panelScale + headerGap / 2f

            drawCountLineNVG(paddingX, y, dotIndent, fontSize, hostileColor, "Hostile $hostileCount")
            y += lineHeight
            drawCountLineNVG(paddingX, y, dotIndent, fontSize, passiveColor, "Passive $passiveCount")
            y += lineHeight
            drawCountLineNVG(paddingX, y, dotIndent, fontSize, playerColor, "Player $playerCount")
            y += lineHeight

            HudCard.text(scanLineText, paddingX, y, fontSize, Palette.FAINT)
            y += lineHeight

            if (listRows > 0 || truncated) y += 2f * panelScale

            for (e in entities.take(MAX_LIST_ROWS)) {
                val tag = "[${categoryTag(e.category)}]"
                val distanceText = "%.1fm".format(e.distance)
                HudCard.text(tag, paddingX, y, fontSize, categoryColor(e.category), remap = false)
                HudCard.text(e.name, paddingX + w(tag) + 4f * panelScale, y, fontSize, Palette.TEXT)
                HudCard.text(distanceText, width - paddingX - w(distanceText), y, fontSize, Palette.MUTED_SEE)
                y += lineHeight
            }

            if (truncated) HudCard.text(truncatedText, paddingX, y, fontSize, Palette.FAINT)
        }
    }

    private fun drawCountLineNVG(
        x: Float,
        y: Float,
        dotIndent: Float,
        fontSize: Float,
        color: Int,
        label: String
    ) {
        val d = 6f * panelScale
        NVGRenderer.rect(x, y + (fontSize - d) / 2f, d, d, color, d / 2f)
        HudCard.text(label, x + dotIndent, y, fontSize, Palette.TEXT)
    }
}
//#endif
