package ced.cedclient.features.impl.render

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import com.google.gson.Gson
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import java.io.File

/**
 * CedClient's own notices ("Copied message", "Fish catching ON", ...) in a small line just above the action bar,
 * where Hypixel's action bar can't overwrite them, plus a movable held item name. Both are in the HUD editor and
 * anchored to the bottom middle of the screen, so they stay put when the window size changes.
 * Use [notify] instead of Gui.setOverlayMessage. Item name hook: GuiItemNameMixin.
 */
object ActionBarHud : Module(
    "Action Bar HUD",
    Category.HUD,
    "Client notices in a small line above the action bar (Hypixel can't overwrite them), and a movable held item name.",
    defaultEnabled = false
) {
    private val mc get() = Minecraft.getInstance()

    private val alwaysItemName = BooleanSetting("Always Show Item Name", false, "The held item's name stays up instead of fading after you switch slots.")

    init {
        addSettings(alwaysItemName)
    }

    @JvmStatic fun alwaysShowItemName(): Boolean = isEnabled && alwaysItemName.value

    /** Shows [text] for [ms]; falls back to the vanilla action bar while this module is off. */
    @JvmStatic
    fun notify(text: Component, ms: Long = 2000) {
        if (!isEnabled) return mc.gui.hud.setOverlayMessage(text, false)
        Notices.text = text
        Notices.until = System.currentTimeMillis() + ms
    }

    fun render(g: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
        if (isEnabled) Notices.draw(g)
    }

    /** Bottom-middle anchored panel: [dx] from the screen centre to the panel centre, [dy] from the bottom to its top. */
    abstract class Anchored(override val label: String, private val defaultDx: Int, private val defaultDy: Int, private val defaultScale: Float) : HudElement {
        var dx = defaultDx
        var dy = defaultDy
        override var panelScale = defaultScale
        override val lastWidth = WIDTH
        override val lastHeight = 12

        private val screenW get() = Minecraft.getInstance().window.guiScaledWidth
        private val screenH get() = Minecraft.getInstance().window.guiScaledHeight
        override var panelX: Int
            get() = screenW / 2 + dx - (WIDTH * panelScale / 2).toInt()
            set(v) { dx = v + (WIDTH * panelScale / 2).toInt() - screenW / 2 }
        override var panelY: Int
            get() = screenH - dy
            set(v) { dy = screenH - v }

        override fun resetPosition() { dx = defaultDx; dy = defaultDy; panelScale = defaultScale }
        override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.5f, 3f) }
        override fun save() = savePositions()

        /** Draws [text] centred in the panel: on Compact Scoreboard's rounded dark panel when [compact], else chat-style backdrop. */
        protected fun drawText(g: GuiGraphicsExtractor, text: Component, color: Int, compact: Boolean = false) {
            val font = Minecraft.getInstance().font
            val w = font.width(text)
            val x = (WIDTH - w) / 2
            g.pose().pushMatrix()
            g.pose().translate(panelX.toFloat(), panelY.toFloat())
            g.pose().scale(panelScale, panelScale)
            if (compact) {
                // Panel opacity follows Compact Scoreboard's setting, faded along with the text.
                val bg = CompactScoreboard.bgPanel()
                val alpha = (bg ushr 24) * (color ushr 24) / 255
                CompactScoreboard.roundRect(g, x - 5, -1, x + w + 5, 12, (alpha shl 24) or (bg and 0xFFFFFF))
                g.text(font, text, x, 1, color)
            } else {
                g.textWithBackdrop(font, text, x, 1, w, color)
            }
            g.pose().popMatrix()
        }
    }

    /** Vanilla's action bar sits ~68 up from the bottom; this sits a line above it, a bit smaller. */
    object Notices : Anchored("Client Notices", 9, 113, 1.1f) {
        var text: Component? = null
        var until = 0L

        fun draw(g: GuiGraphicsExtractor) {
            val line = text ?: return
            val left = until - System.currentTimeMillis()
            if (left <= 0) { text = null; return }
            drawText(g, line, ARGB.white(((left / 300f).coerceIn(0.05f, 1f) * 255).toInt()), compact = true)
        }

        override fun renderInternal(g: GuiGraphicsExtractor) =
            drawText(g, text ?: Component.literal("§aClient notice"), -1, compact = true)
    }

    /** Defaults to vanilla's spot (59 up from the bottom, scale 1), so it only moves once you drag it. */
    object ItemName : Anchored("Held Item Name", 0, 59, 1f) {
        override fun renderInternal(g: GuiGraphicsExtractor) = drawText(g, Component.literal("Held Item Name"), -1)

        /**
         * GuiItemNameMixin: moves vanilla's item name (drawn centred at y = height - 59, +14 in creative) from its
         * own spot into this panel. False while the module is off.
         */
        @JvmStatic
        fun transform(g: GuiGraphicsExtractor): Boolean {
            if (!isEnabled) return false
            val w = g.guiWidth()
            val h = g.guiHeight()
            g.pose().pushMatrix()
            g.pose().translate(w / 2f + dx, (h - dy).toFloat())
            g.pose().scale(panelScale, panelScale)
            g.pose().translate(-w / 2f, -(h - 59f))
            return true
        }
    }

    private const val WIDTH = 200

    // ---- positions ----

    private data class Saved(val dx: Int, val dy: Int, val scale: Float)
    private val gson = Gson()
    private val file by lazy { CedPaths.hud("action_bar") }
    private val panels get() = mapOf("notices" to Notices, "itemName" to ItemName)

    private fun savePositions() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(panels.mapValues { (_, p) -> Saved(p.dx, p.dy, p.panelScale) }))
        }.onFailure { println("[ActionBarHud] Failed to save positions: ${it.message}") }
    }

    init {
        runCatching {
            if (!file.exists()) return@runCatching
            val saved = gson.fromJson(file.readText(), com.google.gson.JsonObject::class.java)
            panels.forEach { (key, panel) ->
                val s = saved.get(key)?.let { gson.fromJson(it, Saved::class.java) } ?: return@forEach
                panel.dx = s.dx; panel.dy = s.dy; panel.panelScale = s.scale.coerceIn(0.5f, 3f)
            }
        }.onFailure { println("[ActionBarHud] Failed to load positions: ${it.message}") }
    }
}
