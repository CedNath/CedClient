package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
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
 * Text uses the mod's custom font (assets/cedclient/font/font.json, a
 * standard vanilla TTF resource-pack font provider) via
 * Style.withFont(Identifier) on a Component -- entirely vanilla
 * GuiGraphicsExtractor rendering, no NanoVG involved. An earlier version of
 * this routed the whole panel through NVGSpecialRenderer (the pipeline
 * ClickGUI/popups use) to also get NanoVG's custom font + rounded corners,
 * but that broke badly: resizing made the panel disappear, and disabling
 * the module broke ALL NVG-drawn UI including ClickGUI itself. Every other
 * NVG call in this mod happens inside a Screen's extractRenderState, where
 * there's exactly one top-level NVGSpecialRenderer.draw() per screen and
 * everything else nests inside it (see ClickGUI.kt) -- a HUD element
 * opening its own independent top-level NVG frame every single frame,
 * regardless of whether a Screen is also doing the same thing that frame,
 * is genuinely new territory for this codebase and isn't safe to attempt
 * again without a known-working reference to port from. Background stays
 * plain GuiGraphicsExtractor.fill/outline (square corners) for that reason
 * -- text alone gets your custom font here, which is most of the visual
 * upgrade with none of the risk.
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

    /** Also used by MasterHudEditScreen to draw a live preview while dragging/scaling. */
    override fun renderInternal(g: GuiGraphicsExtractor) {
        ensureLoaded()

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
            g.fill(0, 0, width, height, 0xCC10101A.toInt())
            g.outline(0, 0, width, height, 0xFF5A4FCF.toInt())
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

    init {
        load()
        addSettings(use24Hour, showSeconds, showBackground, textColor)
    }
}