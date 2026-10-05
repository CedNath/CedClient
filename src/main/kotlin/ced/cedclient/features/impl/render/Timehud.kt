package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.utils.Colors
import com.google.gson.Gson
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private data class TimeHudPosition(val x: Int, val y: Int, val scale: Float = 1.0f)

/**
 * Small HUD panel showing your real-world (system clock) time -- not
 * in-game/SkyBlock time. Implements HudElement so it shows up alongside
 * EntityESPHud in the shared MasterHudEditScreen (opened via /cc hud or
 * /cedclient hud -- deliberately no keybind).
 *
 * Styled to match DailyReset's rounded card (drawCard/fillRounded) instead
 * of the old square-corner panel, with the same dual-backend structure:
 * renderVanilla() is the long-proven GuiGraphicsExtractor path (still using
 * the mod's custom font via Style.withFont(Identifier), same as before),
 * and renderNVG() is an experimental NanoVG path gated behind
 * useNanoVGRendering, with renderInternal() catching any NVG failure and
 * silently falling back to renderVanilla() for that frame. This mirrors
 * DailyReset's own useNanoVGRendering/renderNVG() -- see those doc
 * comments for the fuller history of why a HUD element doing this needs
 * that safety net (an earlier, simpler attempt at routing this exact panel
 * through NVGSpecialRenderer broke badly: resizing made the panel
 * disappear, and disabling the module broke ALL NVG-drawn UI including
 * ClickGUI, because nothing guaranteed NVGRenderer.endFrame() ran if
 * renderContent() itself threw. NVGSpecialRenderer.kt now wraps that block
 * in try/finally so that specific failure mode can't recur, and the
 * automatic per-frame fallback here means a NanoVG bug now shows up as a
 * wrong-looking panel instead of taking down ClickGUI). The NVG path uses
 * NVGRenderer.defaultFont, which is loaded from the same
 * assets/cedclient/font/font.ttf as the vanilla path's custom font, so the
 * typeface stays the same either way.
 */
object TimeHud : Module(
    "Time HUD",
    Category.Render,
    "Shows your real-world clock time on screen"
), HudElement {

    override val label: String = "Time HUD"

    private const val MIN_SCALE = 0.5f
    private const val MAX_SCALE = 3.0f

    // Font resource id for assets/cedclient/font/font.json (path convention:
    // assets/<namespace>/font/<id>.json -> Identifier(namespace, id)).
    // If this id is wrong, text silently falls back to Minecraft's default
    // font rather than crashing -- if the custom font doesn't show up
    // in-game, check the actual id via IntelliJ (search usages of
    // font.json's provider) and swap it in here.
    private val fontId: Identifier = Identifier.fromNamespaceAndPath("cedclient", "font")
    private val fontStyle: Style = Style.EMPTY.withFont(FontDescription.Resource(fontId))

    override var panelX: Int = 6
    override var panelY: Int = 20
    override var panelScale: Float = 1.0f

    // logical (unscaled) size — updated every render() call, used for hit-testing
    // note: on-screen rendered size is this * panelScale
    override var lastWidth: Int = 60
        private set
    override var lastHeight: Int = 16
        private set

    private val use24Hour = BooleanSetting("24-Hour Format", false)
    private val showSeconds = BooleanSetting("Show Seconds", false)
    private val showBackground = BooleanSetting("Show Background", true)
    private val textColor = ColorSetting("Text Color", Colors.WHITE)

    // On by default now that it's been tested — see the class doc comment
    // above for why a HUD element doing this still keeps the automatic
    // per-frame fallback in renderInternal() below. Flagged .advanced so
    // the toggle to fall back to the plain renderer is only visible with
    // Advanced Mode on, out of the way for everyone else.
    private val useNanoVGRendering = BooleanSetting(
        "Use NanoVG Rendering",
        true,
        "True rounded corners + antialiasing via the same NanoVG pipeline ClickGUI/Daily Reset use, " +
                "instead of the scanline-approximated corners GuiGraphicsExtractor.fill() draws. Falls " +
                "back to the old rendering automatically if anything goes wrong."
    ).also { it.advanced = true }

    private val gson = Gson()
    private val saveFile: File by lazy {
        File(Minecraft.getInstance().gameDirectory, "cedclient/time_hud.json")
    }

    // mirrors EntityESPHud's ensureLoaded() pattern: load once, lazily, the
    // first time we actually need the data.
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
        val now = LocalTime.now()
        val pattern = when {
            use24Hour.value && showSeconds.value -> "HH:mm:ss"
            use24Hour.value -> "HH:mm"
            showSeconds.value -> "hh:mm:ss a"
            else -> "hh:mm a"
        }
        return now.format(DateTimeFormatter.ofPattern(pattern))
    }

    fun render(g: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
        if (!isEnabled) return
        renderInternal(g)
    }

    // -------------------------
    // Shared card styling -- same palette/metrics as DailyReset/EntityESPHud
    // so all three HUD panels read as one visual family.
    // -------------------------
    private const val CORNER_RADIUS = 8
    private const val BORDER_THICKNESS = 1

    private val BORDER_COLOR = 0xFF5A4FCF.toInt()
    private val BACKGROUND_COLOR = 0xE8161620.toInt()

    /**
     * Fills a rounded rectangle using only GuiGraphicsExtractor.fill() -- no
     * NanoVG. Identical approach to DailyReset.fillRounded().
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

        if (useNanoVGRendering.value) {
            try {
                renderNVG(g)
                return
            } catch (e: Exception) {
                println("[TimeHud] NanoVG render failed, falling back to vanilla rendering this frame: ${e.message}")
            }
        }
        renderVanilla(g)
    }

    private fun renderVanilla(g: GuiGraphicsExtractor) {
        val font = Minecraft.getInstance().font
        val styledText: Component = Component.literal(currentTimeText()).withStyle(fontStyle)

        // font.width() takes a FormattedText/Component overload too, so the
        // measured width matches the custom font's actual glyph widths
        // rather than the default font's.
        val textWidth = font.width(styledText)

        val paddingX = 8
        val paddingY = 6
        val width = textWidth + paddingX * 2
        val height = font.lineHeight + paddingY * 2

        lastWidth = width
        lastHeight = height

        // draw everything in local (0,0)-anchored space, then scale+translate
        // the whole panel as one unit, same approach as EntityESPHud
        g.pose().pushMatrix()
        g.pose().translate(panelX.toFloat(), panelY.toFloat())
        g.pose().scale(panelScale, panelScale)

        if (showBackground.value) {
            drawCard(g, width, height)
        }

        // center exactly, rather than assuming padding alone lines it up --
        // width already centers horizontally since it's textWidth + equal
        // padding on both sides, but this computes both explicitly so it
        // stays centered even if that assumption ever changes
        val textX = (width - textWidth) / 2
        val textY = (height - font.lineHeight) / 2
        g.text(font, styledText, textX, textY, textColor.value.rgba)

        g.pose().popMatrix()
    }

    /**
     * NanoVG rendering path -- EXPERIMENTAL, see the class doc comment and
     * useNanoVGRendering's doc comment above for the fuller rationale.
     * Layout mirrors renderVanilla() exactly; only the draw calls differ
     * (NVGRenderer.rect/text instead of GuiGraphicsExtractor.fill/text).
     * Follows DailyReset.renderNVG()'s own coordinate-space handling:
     * NVGSpecialRenderer.draw() gets the FULL canvas as bounds, and this
     * panel's actual position/scale is applied manually inside
     * renderContent via NVGRenderer.translate/scale, scaled from GUI-pose
     * space into NVG-canvas space first (see NVGRenderer.canvasWidth's doc
     * comment for why that conversion is needed whenever GUI Scale !=
     * device pixel ratio).
     */
    private fun renderNVG(g: GuiGraphicsExtractor) {
        val font = NVGRenderer.defaultFont
        val fontSize = 12f * panelScale

        val text = currentTimeText()
        val textWidth = NVGRenderer.textWidth(text, fontSize, font)

        val paddingX = 8f * panelScale
        val paddingY = 6f * panelScale
        val width = textWidth + paddingX * 2
        val height = fontSize + paddingY * 2
        val cornerRadius = CORNER_RADIUS * panelScale

        lastWidth = (width / panelScale).toInt()
        lastHeight = (height / panelScale).toInt()

        NVGSpecialRenderer.draw(
            g,
            0, 0, g.guiWidth(), g.guiHeight(),
            renderContent = {
                val spaceRatio = NVGRenderer.canvasWidth / g.guiWidth().toFloat()
                NVGRenderer.scale(spaceRatio, spaceRatio)
                NVGRenderer.translate(panelX.toFloat(), panelY.toFloat())

                if (showBackground.value) {
                    NVGRenderer.rect(0f, 0f, width, height, BACKGROUND_COLOR, cornerRadius)
                    NVGRenderer.hollowRect(0f, 0f, width, height, BORDER_THICKNESS * panelScale, BORDER_COLOR, cornerRadius)
                }

                val textX = (width - textWidth) / 2f
                val textY = (height - fontSize) / 2f
                NVGRenderer.text(text, textX, textY, fontSize, textColor.value.rgba, font)
            }
        )
    }

    init {
        load()
        addSettings(use24Hour, showSeconds, showBackground, textColor, useNanoVGRendering)
    }
}