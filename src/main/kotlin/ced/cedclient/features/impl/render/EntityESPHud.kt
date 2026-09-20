package ced.cedclient.features.impl.render

import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.utils.Colors
import com.google.gson.Gson
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.io.File

private data class HudPosition(val x: Int, val y: Int, val scale: Float = 1.0f)

/**
 * Rounded-card HUD panel summarizing EntityESP's current scan, styled to
 * match DailyReset's card (drawCard/fillRounded, header + count badge,
 * divider, colored list rows) instead of the old square-corner panel.
 * Same dual-backend structure as DailyReset too: a long-proven vanilla
 * GuiGraphicsExtractor path (renderVanilla) plus an experimental NanoVG
 * path (renderNVG) gated behind EntityESP's "Use NanoVG Rendering"
 * setting, with renderInternal() catching any NVG failure and silently
 * falling back to the vanilla path for that frame -- see DailyReset's
 * renderInternal()/renderNVG() doc comments for the fuller history of why
 * a HUD element doing this needs that safety net.
 */
object EntityESPHud : HudElement {

    override val label: String = "Entity ESP"

    private val hostileColor = 0xFFFF5555.toInt()
    private val passiveColor = 0xFF55FF55.toInt()
    private val playerColor = 0xFF5599FF.toInt()

    private const val MAX_LIST_ROWS = 20
    private const val MIN_SCALE = 0.5f
    private const val MAX_SCALE = 3.0f

    override var panelX: Int = 6
    override var panelY: Int = 6
    override var panelScale: Float = 1.0f

    // logical (unscaled) size — updated every render() call, used for hit-testing
    // note: on-screen rendered size is this * panelScale
    override var lastWidth: Int = 190
        private set
    override var lastHeight: Int = 60
        private set

    private val gson = Gson()
    private val saveFile: File by lazy {
        File(Minecraft.getInstance().gameDirectory, "cedclient/entity_esp_hud.json")
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

    // -------------------------
    // Shared card styling -- same palette/metrics as DailyReset so both HUD
    // panels read as one visual family.
    // -------------------------
    private const val CORNER_RADIUS = 8
    private const val BORDER_THICKNESS = 1

    private val BORDER_COLOR = 0xFF5A4FCF.toInt()
    private val BACKGROUND_COLOR = 0xE8161620.toInt()
    private val ACCENT_COLOR = 0xFF8A7FFF.toInt()
    private val IDLE_BADGE_COLOR = 0xFF7BD88F.toInt() // soft green -- "nothing nearby"
    private val BADGE_TEXT_COLOR = 0xFF1A1A24.toInt()
    private val DIVIDER_HIGHLIGHT = 0x30FFFFFF
    private val MUTED_TEXT_COLOR = 0xFF888888.toInt()
    private val NAME_TEXT_COLOR = 0xFFDDDDDD.toInt()
    private val DISTANCE_TEXT_COLOR = 0xFF999999.toInt()

    /**
     * Fills a rounded rectangle using only GuiGraphicsExtractor.fill() -- no
     * NanoVG. Identical approach to DailyReset.fillRounded(): a plus/cross
     * of two overlapping plain rects covers everything except the four
     * corner squares, and each corner square gets its quarter-circle carved
     * out one scanline at a time.
     */
    private fun fillRounded(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, radius: Int, color: Int) {
        val r = radius.coerceIn(0, minOf(width, height) / 2)
        if (r <= 0) {
            g.fill(x, y, x + width, y + height, color)
            return
        }

        g.fill(x + r, y, x + width - r, y + height, color)
        g.fill(x, y + r, x + width, y + height - r, color)

        for (row in 0 until r) {
            val dy = r - row
            val dx = kotlin.math.sqrt((r * r - dy * dy).toDouble()).toInt()
            val inset = r - dx

            g.fill(x + inset, y + row, x + r, y + row + 1, color) // top-left
            g.fill(x + width - r, y + row, x + width - inset, y + row + 1, color) // top-right
            g.fill(x + inset, y + height - 1 - row, x + r, y + height - row, color) // bottom-left
            g.fill(x + width - r, y + height - 1 - row, x + width - inset, y + height - row, color) // bottom-right
        }
    }

    /** Draws a rounded card border by layering a background-colored rounded rect inside a border-colored one. */
    private fun drawCard(g: GuiGraphicsExtractor, width: Int, height: Int) {
        fillRounded(g, 0, 0, width, height, CORNER_RADIUS, BORDER_COLOR)
        fillRounded(
            g,
            BORDER_THICKNESS, BORDER_THICKNESS,
            width - BORDER_THICKNESS * 2, height - BORDER_THICKNESS * 2,
            (CORNER_RADIUS - BORDER_THICKNESS).coerceAtLeast(0),
            BACKGROUND_COLOR
        )
    }

    /** Also used by MasterHudEditScreen to draw a live preview while dragging/scaling. */
    override fun renderInternal(g: GuiGraphicsExtractor) {
        ensureLoaded()

        if (EntityESP.nvgHudRenderingEnabled) {
            try {
                renderNVG(g)
                return
            } catch (e: Exception) {
                println("[EntityESPHud] NanoVG render failed, falling back to vanilla rendering this frame: ${e.message}")
            }
        }
        renderVanilla(g)
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

    private fun renderVanilla(g: GuiGraphicsExtractor) {
        val font = Minecraft.getInstance().font
        val entities = EntityESP.scannedEntities

        val hostileCount = entities.count { it.category == MobCategoryType.HOSTILE }
        val passiveCount = entities.count { it.category == MobCategoryType.PASSIVE }
        val playerCount = entities.count { it.category == MobCategoryType.PLAYER }
        val chunkRadius = (EntityESP.maxDistanceBlocks / 16.0).toInt().coerceAtLeast(1)

        val header = "Entity ESP"
        val badgeText = if (entities.isEmpty()) "NONE" else "${entities.size} NEARBY"

        val paddingX = 10
        val paddingY = 8
        val headerGap = 5
        val lineHeight = font.lineHeight + 4
        val dotIndent = 10
        val badgeHPad = 6
        val badgeVPad = 3

        val listRows = entities.size.coerceAtMost(MAX_LIST_ROWS)
        val truncated = entities.size > MAX_LIST_ROWS

        val badgeWidth = font.width(badgeText) + badgeHPad * 2
        val badgeHeight = font.lineHeight + badgeVPad * 2
        val headerRowHeight = maxOf(font.lineHeight, badgeHeight)
        val headerRowWidth = font.width(header) + 8 + badgeWidth

        val scanLineText = "Scan: ${chunkRadius}x$chunkRadius [ON]"
        val truncatedText = "+${entities.size - MAX_LIST_ROWS} more"

        var contentWidth = maxOf(
            headerRowWidth,
            font.width("Hostile $hostileCount") + dotIndent,
            font.width("Passive $passiveCount") + dotIndent,
            font.width("Player $playerCount") + dotIndent,
            font.width(scanLineText)
        )
        for (e in entities.take(MAX_LIST_ROWS)) {
            val rowWidth = font.width("[${categoryTag(e.category)}] ") + font.width(e.name) + 8 + font.width("%.1fm".format(e.distance))
            if (rowWidth > contentWidth) contentWidth = rowWidth
        }
        if (truncated) contentWidth = maxOf(contentWidth, font.width(truncatedText))

        val width = contentWidth + paddingX * 2
        val summaryRows = 4 // 3 category rows + scan line
        val height = paddingY * 2 + headerRowHeight + headerGap + 2 + lineHeight * summaryRows +
                (if (listRows > 0 || truncated) 2 else 0) + lineHeight * listRows + (if (truncated) lineHeight else 0)

        lastWidth = width
        lastHeight = height

        g.pose().pushMatrix()
        g.pose().translate(panelX.toFloat(), panelY.toFloat())
        g.pose().scale(panelScale, panelScale)

        drawCard(g, width, height)

        val headerTextY = paddingY + (headerRowHeight - font.lineHeight) / 2
        g.text(font, header, paddingX, headerTextY, Colors.WHITE.rgba)

        val badgeLeft = width - paddingX - badgeWidth
        val badgeTop = paddingY
        fillRounded(
            g, badgeLeft, badgeTop, badgeWidth, badgeHeight, badgeHeight / 2,
            if (entities.isEmpty()) IDLE_BADGE_COLOR else ACCENT_COLOR
        )
        g.text(font, badgeText, badgeLeft + badgeHPad, badgeTop + badgeVPad, BADGE_TEXT_COLOR)

        val dividerY = paddingY + headerRowHeight + headerGap
        g.fill(paddingX, dividerY, width - paddingX, dividerY + 1, ACCENT_COLOR)
        g.fill(paddingX, dividerY + 1, width - paddingX, dividerY + 2, DIVIDER_HIGHLIGHT)

        var y = dividerY + 2 + (headerGap / 2)

        drawCountLine(g, font, paddingX, y, dotIndent, hostileColor, "Hostile $hostileCount")
        y += lineHeight
        drawCountLine(g, font, paddingX, y, dotIndent, passiveColor, "Passive $passiveCount")
        y += lineHeight
        drawCountLine(g, font, paddingX, y, dotIndent, playerColor, "Player $playerCount")
        y += lineHeight

        g.text(font, scanLineText, paddingX, y, MUTED_TEXT_COLOR)
        y += lineHeight

        if (listRows > 0 || truncated) y += 2

        for (e in entities.take(MAX_LIST_ROWS)) {
            val tag = "[${categoryTag(e.category)}]"
            val color = categoryColor(e.category)
            val distanceText = "%.1fm".format(e.distance)

            g.text(font, tag, paddingX, y, color)
            g.text(font, e.name, paddingX + font.width(tag) + 4, y, NAME_TEXT_COLOR)
            g.text(font, distanceText, width - paddingX - font.width(distanceText), y, DISTANCE_TEXT_COLOR)
            y += lineHeight
        }

        if (truncated) {
            g.text(font, truncatedText, paddingX, y, MUTED_TEXT_COLOR)
        }

        g.pose().popMatrix()
    }

    /**
     * NanoVG rendering path -- EXPERIMENTAL, see EntityESP's
     * "Use NanoVG Rendering" setting and DailyReset.renderNVG()'s doc
     * comment for the fuller rationale/history behind a HUD element using
     * this pipeline. Layout mirrors renderVanilla() exactly, right down to
     * the same rows in the same order; only the draw calls differ
     * (NVGRenderer.rect/hollowRect/text instead of
     * GuiGraphicsExtractor.fill/text). Follows DailyReset.renderNVG()'s own
     * coordinate-space handling: NVGSpecialRenderer.draw() gets the FULL
     * canvas as bounds, and this panel's actual position/scale is applied
     * manually inside renderContent via NVGRenderer.translate/scale, scaled
     * from GUI-pose space into NVG-canvas space first (see
     * NVGRenderer.canvasWidth's doc comment for why that conversion is
     * needed whenever GUI Scale != device pixel ratio).
     */
    private fun renderNVG(g: GuiGraphicsExtractor) {
        val font = NVGRenderer.defaultFont
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
        val badgeHPad = 6f * panelScale
        val badgeVPad = 3f * panelScale
        val cornerRadius = CORNER_RADIUS * panelScale
        val borderThickness = BORDER_THICKNESS * panelScale

        val badgeWidth = NVGRenderer.textWidth(badgeText, fontSize, font) + badgeHPad * 2
        val badgeHeight = fontSize + badgeVPad * 2
        val headerRowHeight = maxOf(fontSize, badgeHeight)
        val headerRowWidth = NVGRenderer.textWidth(header, fontSize, font) + 8f * panelScale + badgeWidth

        var contentWidth = maxOf(
            headerRowWidth,
            NVGRenderer.textWidth("Hostile $hostileCount", fontSize, font) + dotIndent,
            NVGRenderer.textWidth("Passive $passiveCount", fontSize, font) + dotIndent,
            NVGRenderer.textWidth("Player $playerCount", fontSize, font) + dotIndent,
            NVGRenderer.textWidth(scanLineText, fontSize, font)
        )
        for (e in entities.take(MAX_LIST_ROWS)) {
            val tag = "[${categoryTag(e.category)}]"
            val distanceText = "%.1fm".format(e.distance)
            val rowWidth = NVGRenderer.textWidth(tag, fontSize, font) + 4f * panelScale +
                    NVGRenderer.textWidth(e.name, fontSize, font) + 8f * panelScale +
                    NVGRenderer.textWidth(distanceText, fontSize, font)
            if (rowWidth > contentWidth) contentWidth = rowWidth
        }
        if (truncated) contentWidth = maxOf(contentWidth, NVGRenderer.textWidth(truncatedText, fontSize, font))

        val width = contentWidth + paddingX * 2
        val listRows = entities.size.coerceAtMost(MAX_LIST_ROWS)
        val summaryRows = 4
        val height = paddingY * 2 + headerRowHeight + headerGap + 2f * panelScale + lineHeight * summaryRows +
                (if (listRows > 0 || truncated) 2f * panelScale else 0f) + lineHeight * listRows +
                (if (truncated) lineHeight else 0f)

        lastWidth = (width / panelScale).toInt()
        lastHeight = (height / panelScale).toInt()

        NVGSpecialRenderer.draw(
            g,
            0, 0, g.guiWidth(), g.guiHeight(),
            renderContent = {
                // See DailyReset.renderNVG()'s doc comment -- panelX/panelY
                // are GUI-pose values (shared with MasterHudEditScreen's
                // vanilla outline overlay), so they need converting into
                // NVG-canvas space before being used as a translate offset.
                val spaceRatio = NVGRenderer.canvasWidth / g.guiWidth().toFloat()
                NVGRenderer.scale(spaceRatio, spaceRatio)
                NVGRenderer.translate(panelX.toFloat(), panelY.toFloat())

                NVGRenderer.rect(0f, 0f, width, height, BACKGROUND_COLOR, cornerRadius)
                NVGRenderer.hollowRect(0f, 0f, width, height, borderThickness, BORDER_COLOR, cornerRadius)

                val headerTextY = paddingY + (headerRowHeight - fontSize) / 2f
                NVGRenderer.text(header, paddingX, headerTextY, fontSize, Colors.WHITE.rgba, font)

                val badgeLeft = width - paddingX - badgeWidth
                val badgeTop = paddingY
                NVGRenderer.rect(
                    badgeLeft, badgeTop, badgeWidth, badgeHeight,
                    if (entities.isEmpty()) IDLE_BADGE_COLOR else ACCENT_COLOR, badgeHeight / 2f
                )
                NVGRenderer.text(badgeText, badgeLeft + badgeHPad, badgeTop + badgeVPad, fontSize, BADGE_TEXT_COLOR, font)

                val dividerY = paddingY + headerRowHeight + headerGap
                NVGRenderer.rect(paddingX, dividerY, width - paddingX * 2, 1f * panelScale, ACCENT_COLOR)
                NVGRenderer.rect(paddingX, dividerY + 1f * panelScale, width - paddingX * 2, 1f * panelScale, DIVIDER_HIGHLIGHT)

                var y = dividerY + 2f * panelScale + headerGap / 2f

                drawCountLineNVG(paddingX, y, dotIndent, fontSize, font, hostileColor, "Hostile $hostileCount")
                y += lineHeight
                drawCountLineNVG(paddingX, y, dotIndent, fontSize, font, passiveColor, "Passive $passiveCount")
                y += lineHeight
                drawCountLineNVG(paddingX, y, dotIndent, fontSize, font, playerColor, "Player $playerCount")
                y += lineHeight

                NVGRenderer.text(scanLineText, paddingX, y, fontSize, MUTED_TEXT_COLOR, font)
                y += lineHeight

                if (listRows > 0 || truncated) y += 2f * panelScale

                for (e in entities.take(MAX_LIST_ROWS)) {
                    val tag = "[${categoryTag(e.category)}]"
                    val color = categoryColor(e.category)
                    val distanceText = "%.1fm".format(e.distance)
                    val tagWidth = NVGRenderer.textWidth(tag, fontSize, font)
                    val distanceWidth = NVGRenderer.textWidth(distanceText, fontSize, font)

                    NVGRenderer.text(tag, paddingX, y, fontSize, color, font)
                    NVGRenderer.text(e.name, paddingX + tagWidth + 4f * panelScale, y, fontSize, NAME_TEXT_COLOR, font)
                    NVGRenderer.text(distanceText, width - paddingX - distanceWidth, y, fontSize, DISTANCE_TEXT_COLOR, font)
                    y += lineHeight
                }

                if (truncated) {
                    NVGRenderer.text(truncatedText, paddingX, y, fontSize, MUTED_TEXT_COLOR, font)
                }
            }
        )
    }

    private fun drawCountLine(
        g: GuiGraphicsExtractor,
        font: net.minecraft.client.gui.Font,
        x: Int,
        y: Int,
        dotIndent: Int,
        color: Int,
        label: String
    ) {
        g.fill(x, y + 1, x + 6, y + 7, color)
        g.text(font, label, x + dotIndent, y, NAME_TEXT_COLOR)
    }

    private fun drawCountLineNVG(
        x: Float,
        y: Float,
        dotIndent: Float,
        fontSize: Float,
        font: ced.cedclient.render.font.Font,
        color: Int,
        label: String
    ) {
        NVGRenderer.rect(x, y + 1f * panelScale, 6f * panelScale, 6f * panelScale, color, 1.5f * panelScale)
        NVGRenderer.text(label, x + dotIndent, y, fontSize, NAME_TEXT_COLOR, font)
    }
}