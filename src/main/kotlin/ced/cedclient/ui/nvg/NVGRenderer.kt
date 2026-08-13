    package ced.cedclient.ui.nvg

    import ced.cedclient.utils.alpha
    import ced.cedclient.utils.blue
    import ced.cedclient.utils.green
    import ced.cedclient.utils.red
    import net.minecraft.resources.Identifier
    import org.lwjgl.BufferUtils
    import org.lwjgl.nanovg.NVGColor
    import org.lwjgl.nanovg.NVGPaint
    import org.lwjgl.nanovg.NanoSVG.*
    import org.lwjgl.nanovg.NanoVG.*
    import org.lwjgl.nanovg.NanoVGGL3.*
    import org.lwjgl.stb.STBImage.stbi_load_from_memory
    import org.lwjgl.system.MemoryUtil.memAlloc
    import org.lwjgl.system.MemoryUtil.memFree
    import java.nio.ByteBuffer
    import kotlin.math.max
    import kotlin.math.min
    import kotlin.math.round
    import net.minecraft.client.gui.GuiGraphicsExtractor
    import net.minecraft.client.Minecraft
    import net.minecraft.network.chat.Style


    object NVGRenderer {

        private val nvgPaint = NVGPaint.malloc()
        private val nvgColor = NVGColor.malloc()
        private val nvgColor2 = NVGColor.malloc()

        // Your ClickGUI expects this to exist
        val defaultFont = Font("default")

        // ---- custom font (NanoVG-native) ----
        // NOT currently used for text rendering — see text()/textShadow()/textWidth()
        // below. NanoVG's glyph rendering (texture-sampled, unlike its solid-color
        // rect/circle fills) was coming out fully invisible in this renderer setup —
        // bounds/metrics measured correctly, color/alpha was correct, solid fills
        // rendered fine, but glyph pixels never appeared. That's consistent with a
        // GL-state or texture-format mismatch between NanoVG's GL3 backend and
        // Minecraft's newer GpuDevice/RenderPipeline renderer (most likely NanoVG's
        // font atlas texture format vs core-profile GL, or texture-unit state — see
        // conversation history), which needs a GL debug capture (RenderDoc) to
        // pin down properly rather than more blind guessing.
        //
        // Text rendering below always uses Minecraft's own font (drawVanillaText),
        // which is proven to work. loadCustomFont()/customFontLoaded are left in
        // place (harmless — just unused by text rendering) in case the NanoVG-glyph
        // path gets debugged and reinstated later.
        private const val CUSTOM_FONT_NAME = "custom"
        private const val CUSTOM_FONT_RESOURCE = "cedclient:font/font.ttf"
        private var customFontLoaded = false
        private var fontDataBuffer: ByteBuffer? = null

        private val images = HashMap<Image, NVGImage>()
        private var scissor: Scissor? = null
        private var drawing = false
        private var vg = -1L

        var debugTextMeasurement = false
        private val lastDebugLogAt = HashMap<String, Long>()
        private fun debugThrottled(key: String, minIntervalMs: Long = 1000L, block: () -> Unit) {
            if (!debugTextMeasurement) return
            val now = System.currentTimeMillis()
            val last = lastDebugLogAt[key] ?: 0L
            if (now - last >= minIntervalMs) {
                lastDebugLogAt[key] = now
                block()
            }
        }

        fun beginFrame(width: Float, height: Float) {
            if (drawing) throw IllegalStateException("[NVGRenderer] Already drawing")

            if (vg == -1L) {
                vg = nvgCreate(NVG_ANTIALIAS or NVG_STENCIL_STROKES)
                loadCustomFont()
            }

            nvgBeginFrame(vg, width, height, 1f)
            nvgTextAlign(vg, NVG_ALIGN_LEFT or NVG_ALIGN_TOP)
            drawing = true
        }

        fun endFrame() {
            if (!drawing) throw IllegalStateException("[NVGRenderer] Not drawing")
            nvgEndFrame(vg)
            drawing = false
        }

        private fun loadCustomFont() {
            try {
                val mc = Minecraft.getInstance()
                val id = Identifier.parse(CUSTOM_FONT_RESOURCE)
                val resource = mc.resourceManager.getResource(id).orElse(null)
                if (resource == null) {
                    println("[NVGRenderer] Custom font not found: $id — text rendering uses Minecraft's font regardless")
                    return
                }

                val bytes = resource.open().readBytes()
                val buffer = BufferUtils.createByteBuffer(bytes.size).apply {
                    put(bytes)
                    flip()
                }
                fontDataBuffer = buffer

                val handle = nvgCreateFontMem(vg, CUSTOM_FONT_NAME, buffer, false)
                customFontLoaded = handle != -1
                if (!customFontLoaded) {
                    println("[NVGRenderer] nvgCreateFontMem failed for $CUSTOM_FONT_RESOURCE")
                }
            } catch (e: Exception) {
                println("[NVGRenderer] Failed to load custom font: ${e.message}")
            }
        }

        fun push() = nvgSave(vg)
        fun pop() = nvgRestore(vg)
        fun scale(x: Float, y: Float) = nvgScale(vg, x, y)
        fun translate(x: Float, y: Float) = nvgTranslate(vg, x, y)
        fun rotate(amount: Float) = nvgRotate(vg, amount)
        fun globalAlpha(amount: Float) = nvgGlobalAlpha(vg, amount.coerceIn(0f, 1f))

        fun pushScissor(x: Float, y: Float, w: Float, h: Float) {
            scissor = Scissor(scissor, x, y, x + w, y + h)
            scissor?.applyScissor()
        }

        fun popScissor() {
            nvgResetScissor(vg)
            scissor = scissor?.previous
            scissor?.applyScissor()
        }

        fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Int) {
            nvgBeginPath(vg)
            nvgMoveTo(vg, x1, y1)
            nvgLineTo(vg, x2, y2)
            nvgStrokeWidth(vg, thickness)
            setColor(color)
            nvgStrokeColor(vg, nvgColor)
            nvgStroke(vg)
        }

        fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
            nvgBeginPath(vg)
            nvgRect(vg, x, y, w, h)
            setColor(color)
            nvgFillColor(vg, nvgColor)
            nvgFill(vg)
        }

        fun rect(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float) {
            nvgBeginPath(vg)
            nvgRoundedRect(vg, x, y, w, h, radius)
            setColor(color)
            nvgFillColor(vg, nvgColor)
            nvgFill(vg)
        }

        fun circle(x: Float, y: Float, radius: Float, color: Int) {
            nvgBeginPath(vg)
            nvgCircle(vg, x, y, radius)
            setColor(color)
            nvgFillColor(vg, nvgColor)
            nvgFill(vg)
        }

        // ---------------- TEXT ----------------
        // Always renders via Minecraft's own font (drawVanillaText). textWidth()
        // below measures with that exact same font/path, so truncate() always cuts
        // strings to a width that matches what's actually drawn — that's what keeps
        // labels inside their panel's right edge.

        fun text(g: GuiGraphicsExtractor, text: String, x: Float, y: Float, size: Float, color: Int, font: Font) {
            drawVanillaText(g, text, x, y, color, shadow = false)
        }

        fun textShadow(g: GuiGraphicsExtractor, text: String, x: Float, y: Float, size: Float, color: Int, font: Font) {
            drawVanillaText(g, text, x, y, color, shadow = true)
        }

        private fun drawVanillaText(
            g: GuiGraphicsExtractor,
            text: String,
            x: Float,
            y: Float,
            color: Int,
            shadow: Boolean
        ) {
            val mc = Minecraft.getInstance()
            val scale = mc.window.guiScale.toFloat()

            val guiX = x / scale
            val guiY = y / scale

            g.text(
                mc.font,
                text,
                guiX.toInt(),
                guiY.toInt(),
                color,
                shadow
            )
        }

        /**
         * Measures `text` in FRAMEBUFFER-space pixels — the same space x/y coordinates
         * passed into text()/rect() use, and the same space truncate()'s maxWidth is
         * given in. mc.font.width(text) returns a LOGICAL/GUI-space width, so it's
         * scaled up by guiScale here. Without this scaling, truncate() would compare
         * a logical-space measurement against a framebuffer-space budget and let
         * text run past its panel at any guiScale > 1.
         */
        fun textWidth(text: String, size: Float, font: Font): Float {
            val scale = Minecraft.getInstance().window.guiScale.toFloat()
            val measured = Minecraft.getInstance().font.width(text).toFloat() * scale

            debugThrottled("width|$text") {
                println(
                    "[NVGRenderer:width] text=\"$text\" size=$size " +
                            "logicalWidth=${Minecraft.getInstance().font.width(text)} scale=$scale " +
                            "measured(fb-space)=$measured"
                )
            }

            return measured
        }

        /**
         * Shortens `text` with a trailing "..." so it fits within maxWidth, measured
         * with the same vanilla Minecraft font that actually draws it, so truncation
         * always matches what's rendered.
         */
        fun truncate(text: String, maxWidth: Float, size: Float, font: Font): String {
            if (maxWidth <= 0f) return ""
            if (textWidth(text, size, font) <= maxWidth) return text

            val ellipsis = "..."
            if (textWidth(ellipsis, size, font) > maxWidth) return ellipsis

            var result = text
            while (result.isNotEmpty() && textWidth(result + ellipsis, size, font) > maxWidth) {
                result = result.dropLast(1)
            }
            return if (result.isEmpty()) ellipsis else result + ellipsis
        }

        /**
         * Draws word-wrapped text starting at (x, y), wrapped to width w.
         *
         * x/y/w are all in FRAMEBUFFER-space — same convention as every other
         * function in this file (text(), rect(), textWidth()). Previously this
         * function passed framebuffer x/y straight into g.text(...) without the
         * fb→gui scale division that drawVanillaText()/text() do, so at any
         * guiScale > 1 the text was drawn roughly `scale`× further right/down
         * than the box wrappedTextBounds() sized for it — that's what caused the
         * tooltip text to land nowhere near its own background box. It also fed
         * `w` (framebuffer-space) straight into splitter.splitLines(), which
         * expects a logical/gui-space width, so the wrap points didn't
         * necessarily match what wrappedTextBounds() computed either. Both are
         * fixed below by converting to gui-space first, same as drawVanillaText.
         */
        fun drawWrappedString(
            g: GuiGraphicsExtractor,
            text: String,
            x: Float,
            y: Float,
            w: Float,
            size: Float,
            color: Int,
            font: Font,
            lineHeight: Float = 1f
        ) {
            val mc = Minecraft.getInstance()
            val scale = mc.window.guiScale.toFloat()
            val fontRenderer = mc.font
            val splitter = fontRenderer.splitter

            val guiW = max(1, (w / scale).toInt())

            val lines = splitter.splitLines(text, guiW, Style.EMPTY)

            var currentY = y

            for (line in lines) {
                drawVanillaText(
                    g,
                    line.string,
                    x,
                    currentY,
                    color,
                    false
                )

                currentY += fontRenderer.lineHeight * scale * lineHeight
            }
        }
        /**
         * Computes wrapped-text bounds in FRAMEBUFFER-space (matching w's input
         * space and every other measurement in this file), so callers can feed
         * the returned width/height straight into rect() to size a background
         * box. Line-wrap decisions use textWidth() (already fb-space + scaled),
         * compared directly against w — consistent units throughout.
         */
        fun wrappedTextBounds(
            text: String,
            w: Float,
            size: Float,
            font: Font,
            lineHeight: Float = 1f
        ): FloatArray {
            val lines = mutableListOf<String>()
            var current = StringBuilder()
            val words = text.split(" ")

            for (word in words) {
                val test = if (current.isEmpty()) word else current.toString() + " " + word
                if (textWidth(test, size, font) > w) {
                    lines += current.toString()
                    current = StringBuilder(word)
                } else {
                    if (current.isNotEmpty()) current.append(" ")
                    current.append(word)
                }
            }

            if (current.isNotEmpty()) lines += current.toString()

            val scale = Minecraft.getInstance().window.guiScale.toFloat()
            val fbLineHeight = Minecraft.getInstance().font.lineHeight.toFloat() * scale * lineHeight
            val height = lines.size * fbLineHeight
            return floatArrayOf(0f, 0f, w, height)
        }

        // ---------------- IMAGES ----------------

        private data class NVGImage(var count: Int, val nvg: Int)

        private fun setColor(color: Int) {
            nvgRGBA(
                color.red.toByte(),
                color.green.toByte(),
                color.blue.toByte(),
                color.alpha.toByte(),
                nvgColor
            )
        }

        private class Scissor(val previous: Scissor?, val x: Float, val y: Float, val maxX: Float, val maxY: Float) {
            fun applyScissor() {
                if (previous == null) {
                    nvgScissor(vg, x, y, maxX - x, maxY - y)
                } else {
                    val nx = max(x, previous.x)
                    val ny = max(y, previous.y)
                    val w = max(0f, min(maxX, previous.maxX) - nx)
                    val h = max(0f, min(maxY, previous.maxY) - ny)
                    nvgScissor(vg, nx, ny, w, h)
                }
            }
        }
    }