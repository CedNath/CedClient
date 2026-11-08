package ced.cedclient.features.impl.render

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.utils.Colors
import com.google.gson.Gson
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private data class TimeHudPosition(val x: Int, val y: Int, val scale: Float = 1.0f)

/**
 * Your real-world (system clock) time on screen -- not in-game/SkyBlock time. A compact HUD: see-through halo text,
 * or the shared card with Show Background (HudCard). Placed in the HUD editor (/cc hud).
 */
object TimeHud : Module(
    "Time HUD",
    Category.HUD,
    "Shows your real-world clock time on screen"
), HudElement {

    override val label: String = "Time HUD"

    private const val MIN_SCALE = 0.5f
    private const val MAX_SCALE = 3.0f

    override var panelX: Int = 1205
    override var panelY: Int = 652
    override var panelScale: Float = 1.0f
    override fun resetPosition() { panelX = 1205; panelY = 652; panelScale = 1.0f }

    // Logical (unscaled) size, updated every frame and used for hit-testing; on screen it is this * panelScale.
    override var lastWidth: Int = 60
        private set
    override var lastHeight: Int = 16
        private set

    private val use24Hour = BooleanSetting("24-Hour Format", false)
    private val showSeconds = BooleanSetting("Show Seconds", false)
    private val showBackground = BooleanSetting("Show Background", false)
    private val textColor = ColorSetting("Text Color", Colors.WHITE)

    private val gson = Gson()
    private val saveFile by lazy { CedPaths.hud("time") }

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
                val pos = gson.fromJson(saveFile.readText(), TimeHudPosition::class.java)
                if (pos != null) {
                    panelX = pos.x
                    panelY = pos.y
                    panelScale = pos.scale.coerceIn(MIN_SCALE, MAX_SCALE)
                }
            }
        } catch (e: Exception) {
            println("[TimeHud] Failed to load position: ${e.message}")
        }
    }

    override fun save() {
        try {
            saveFile.parentFile?.mkdirs()
            saveFile.writeText(gson.toJson(TimeHudPosition(panelX, panelY, panelScale)))
        } catch (e: Exception) {
            println("[TimeHud] Failed to save position: ${e.message}")
        }
    }

    override fun adjustScale(delta: Float) {
        panelScale = (panelScale + delta).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    private fun currentTimeText(): String {
        val pattern = when {
            use24Hour.value && showSeconds.value -> "HH:mm:ss"
            use24Hour.value -> "HH:mm"
            showSeconds.value -> "hh:mm:ss a"
            else -> "hh:mm a"
        }
        return LocalTime.now().format(DateTimeFormatter.ofPattern(pattern))
    }

    fun render(g: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
        if (!isEnabled) return
        renderInternal(g)
    }

    /** Also used by MasterHudEditScreen to draw a live preview while dragging/scaling. */
    override fun renderInternal(g: GuiGraphicsExtractor) {
        ensureLoaded()
        val fontSize = 12f * panelScale
        val text = currentTimeText()
        val textWidth = HudCard.width(text, fontSize)

        // See-through: almost no padding, the halo keeps it readable. With the card on: room around the text.
        val paddingX = (if (showBackground.value) 8f else 1f) * panelScale
        val paddingY = (if (showBackground.value) 6f else 1f) * panelScale
        val width = textWidth + paddingX * 2
        val height = fontSize + paddingY * 2

        lastWidth = (width / panelScale).toInt()
        lastHeight = (height / panelScale).toInt()

        HudCard.draw(g, panelX, panelY) {
            if (showBackground.value) HudCard.card(width, height, panelScale, label = label)
            HudCard.text(text, (width - textWidth) / 2f, (height - fontSize) / 2f, fontSize, textColor.value.rgba)
        }
    }

    init {
        load()
        addSettings(use24Hour, showSeconds, showBackground, textColor)
    }
}
