package ced.cedclient.features.impl.render

import com.google.gson.Gson
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.io.File

private data class HudPosition(val x: Int, val y: Int, val scale: Float = 1.0f)

object EntityESPHud {

    private val hostileColor = 0xFFFF5555.toInt()
    private val passiveColor = 0xFF55FF55.toInt()
    private val playerColor = 0xFF5599FF.toInt()

    private const val MAX_LIST_ROWS = 20
    private const val MIN_SCALE = 0.5f
    private const val MAX_SCALE = 3.0f

    var panelX: Int = 6
    var panelY: Int = 6
    var panelScale: Float = 1.0f

    // logical (unscaled) size — updated every render() call, used for hit-testing
    // note: on-screen rendered size is this * panelScale
    var lastWidth: Int = 190
        private set
    var lastHeight: Int = 60
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

    fun save() {
        try {
            saveFile.parentFile?.mkdirs()
            saveFile.writeText(gson.toJson(HudPosition(panelX, panelY, panelScale)))
        } catch (e: Exception) {
            println("[EntityESPHud] Failed to save position: ${e.message}")
        }
    }

    fun adjustScale(delta: Float) {
        panelScale = (panelScale + delta).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    fun render(g: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
        if (!EntityESP.isEnabled) return
        if (!EntityESP.hudPanelEnabled) return
        renderInternal(g)
    }

    /** Also used by HudEditScreen to draw a live preview while dragging/scaling. */
    fun renderInternal(g: GuiGraphicsExtractor) {
        ensureLoaded()

        val font = net.minecraft.client.Minecraft.getInstance().font
        val entities = EntityESP.scannedEntities

        val hostileCount = entities.count { it.category == MobCategoryType.HOSTILE }
        val passiveCount = entities.count { it.category == MobCategoryType.PASSIVE }
        val playerCount = entities.count { it.category == MobCategoryType.PLAYER }

        val chunkRadius = (EntityESP.maxDistanceBlocks / 16.0).toInt().coerceAtLeast(1)

        val width = 190
        val lineHeight = 10

        val listRows = entities.size.coerceAtMost(MAX_LIST_ROWS)
        val truncated = entities.size > MAX_LIST_ROWS
        val totalRows = 5 + listRows + (if (truncated) 1 else 0)
        val height = 6 + totalRows * lineHeight

        lastWidth = width
        lastHeight = height

        // draw everything in local (0,0)-anchored space, then scale+translate the
        // whole panel as one unit so background/text/borders all scale together
        g.pose().pushMatrix()
        g.pose().translate(panelX.toFloat(), panelY.toFloat())
        g.pose().scale(panelScale, panelScale)

        var y = 0

        g.fill(0, y, width, height, 0xCC10101A.toInt())
        g.outline(0, y, width, height, 0xFF5A4FCF.toInt())

        y += 4
        g.text(font, "EntityESP", 6, y, 0xFFFFFFFF.toInt())
        y += lineHeight

        drawCountLine(g, font, 6, y, hostileColor, "Hostile $hostileCount")
        y += lineHeight
        drawCountLine(g, font, 6, y, passiveColor, "Passive $passiveCount")
        y += lineHeight
        drawCountLine(g, font, 6, y, playerColor, "Player $playerCount")
        y += lineHeight

        g.text(
            font,
            "Scan: ${chunkRadius}x$chunkRadius [ON]",
            6,
            y,
            0xFF888888.toInt()
        )
        y += lineHeight + 2

        for (e in entities.take(MAX_LIST_ROWS)) {
            val tag = when (e.category) {
                MobCategoryType.HOSTILE -> "Ho"
                MobCategoryType.PASSIVE -> "Pa"
                MobCategoryType.PLAYER -> "Pl"
            }
            val color = when (e.category) {
                MobCategoryType.HOSTILE -> hostileColor
                MobCategoryType.PASSIVE -> passiveColor
                MobCategoryType.PLAYER -> playerColor
            }

            g.text(font, "[$tag]", 6, y, color)
            g.text(font, e.name, 26, y, 0xFFDDDDDD.toInt())
            g.text(font, "%.1fm".format(e.distance), width - 34, y, 0xFF999999.toInt())
            y += lineHeight
        }

        if (truncated) {
            g.text(
                font,
                "+${entities.size - MAX_LIST_ROWS} more",
                6,
                y,
                0xFF888888.toInt()
            )
        }

        g.pose().popMatrix()
    }

    private fun drawCountLine(
        g: GuiGraphicsExtractor,
        font: net.minecraft.client.gui.Font,
        x: Int,
        y: Int,
        color: Int,
        label: String
    ) {
        g.fill(x, y + 1, x + 6, y + 7, color)
        g.text(font, label, x + 10, y, 0xFFDDDDDD.toInt())
    }
}