package ced.cedclient.ui.clickgui

import ced.cedclient.features.Category
import ced.cedclient.features.ModuleManager
import ced.cedclient.ui.nvg.NVGRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max
import kotlin.math.min

class Panel(
    val category: Category,
    var x: Float,
    var y: Float
) {

    companion object {
        const val WIDTH = 260f
        const val GAP = 30f
    }

    private var dragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    // Vertical scroll offset in pixels.
    private var scrollOffset = 0f

    val width = WIDTH
    val headerHeight = 32f
    val buttonSpacing = 10f

    // Captured by updateHover() each frame, reused by drawNVG()/drawText()
    // so all three passes agree on the same layout for this frame — same
    // idea as ModuleButton's lastX/lastY.
    private var lastVisibleTop = 0f
    private var lastVisibleBottom = 0f

    /**
     * Maximum amount of body space available before scrolling is required.
     */
    private val maxBodyHeight: Float
        get() =
            Minecraft.getInstance().window.height.toFloat() -
                    y -
                    headerHeight -
                    20f

    val moduleButtons = ModuleManager.modules
        .filter { it.category == category }
        .map { ModuleButton(it, this) }

    fun setPosition(
        newX: Float,
        newY: Float
    ) {
        x = newX
        y = newY
    }

    fun toPosEntry(): Pair<String, PanelPos> {
        return category.name to PanelPos(
            x.toDouble(),
            y.toDouble()
        )
    }

    /**
     * Total height required by all modules.
     */
    private fun contentHeight(): Float {
        if (moduleButtons.isEmpty()) {
            return 0f
        }

        return moduleButtons
            .sumOf { it.height.toDouble() }
            .toFloat() +
                (moduleButtons.size - 1) * buttonSpacing
    }

    /**
     * Maximum possible scroll amount.
     */
    private fun maxScroll(): Float {
        return max(
            0f,
            contentHeight() -
                    max(0f, maxBodyHeight)
        )
    }

    // ============================================================
    // PASS 0 — HOVER + TOOLTIP REQUEST (synchronous, NOT inside NVG)
    // ============================================================
    //
    // Mirrors ModuleButton's Pass 0 (see the long comment on
    // ModuleButton.updateHover() for the full explanation). This was
    // the missing piece: nothing was calling ModuleButton.updateHover()
    // at all, so TooltipManager.request() never ran, which is why
    // TooltipManager.drawText() always saw text == null.
    //
    // Must be called by ClickGUI.extractRenderState() BEFORE
    // NVGSpecialRenderer.draw() is invoked for this frame — same
    // ordering requirement as ModuleButton.updateHover().
    //
    // Also owns recomputing visibleTop/visibleBottom and clamping
    // scrollOffset for the frame; drawNVG()/drawText() just reuse
    // lastVisibleTop/Bottom instead of recomputing (and instead of
    // drawNVG() being the only place that ever computed them, which
    // meant hover math — when it existed — and the text pass could
    // run against stale bounds).

    fun updateHover(
        mouseX: Int,
        mouseY: Int
    ) {

        scrollOffset =
            scrollOffset.coerceIn(
                0f,
                maxScroll()
            )

        val bodyHeight =
            min(
                contentHeight(),
                max(0f, maxBodyHeight)
            )

        val visibleTop =
            y + headerHeight

        val visibleBottom =
            visibleTop + bodyHeight

        lastVisibleTop = visibleTop
        lastVisibleBottom = visibleBottom

        var offset =
            buttonSpacing - scrollOffset

        for (button in moduleButtons) {

            val drawY =
                visibleTop + offset

            button.updateHover(
                mouseX,
                mouseY,
                x,
                drawY,
                visibleTop,
                visibleBottom
            )

            offset +=
                button.height +
                        buttonSpacing
        }
    }

    // ============================================================
    // PASS 1 — NANOVG SHAPES ONLY
    // ============================================================
    //
    // Called from inside NVGSpecialRenderer's renderContent lambda,
    // AFTER updateHover() has already run for this frame. No hit-testing
    // and no TooltipManager.request() calls belong here — see the long
    // comment on ModuleButton.drawNVG() for why. Reuses lastVisibleTop/
    // Bottom from updateHover() rather than mouseX/mouseY, since layout
    // no longer depends on the mouse position at this point.

    fun drawNVG(
        g: GuiGraphicsExtractor
    ) {

        val visibleTop = lastVisibleTop
        val visibleBottom = lastVisibleBottom

        // ========================================================
        // PANEL HEADER
        // ========================================================

        NVGRenderer.rect(
            x,
            y,
            width,
            headerHeight,
            0xFF202020.toInt()
        )

        // ========================================================
        // PANEL BODY
        // ========================================================

        NVGRenderer.rect(
            x,
            visibleTop,
            width,
            visibleBottom - visibleTop,
            0xFF151515.toInt()
        )

        // ========================================================
        // MODULE BUTTONS (shapes)
        // ========================================================

        for (button in moduleButtons) {
            button.drawNVG(
                g,
                visibleTop,
                visibleBottom
            )
        }
    }

    // ============================================================
    // PASS 2 — MINECRAFT TEXT ONLY
    // ============================================================
    //
    // Called from ClickGUI.extractRenderState() AFTER
    // NVGSpecialRenderer.draw() has returned — normal call order, real
    // framebuffer bound. Uses lastVisibleTop/Bottom captured by
    // updateHover() this same frame.

    fun drawText(
        g: GuiGraphicsExtractor
    ) {

        val visibleTop = lastVisibleTop
        val visibleBottom = lastVisibleBottom

        // Header text
        NVGRenderer.text(
            g,
            category.name,
            x + 8f,
            y + 6f,
            16f,
            0xFFFFFFFF.toInt(),
            NVGRenderer.defaultFont
        )

        // ========================================================
        // MODULE BUTTONS (text)
        // ========================================================

        for (button in moduleButtons) {
            button.drawText(
                g,
                visibleTop,
                visibleBottom
            )
        }

        // ========================================================
        // SCROLL HINTS
        // ========================================================

        if (maxScroll() > 0f) {

            // Scrolled down -> show up arrow
            if (scrollOffset > 0.5f) {

                NVGRenderer.text(
                    g,
                    "^",
                    x + width / 2f - 3f,
                    visibleTop + 1f,
                    11f,
                    0xAAFFFFFF.toInt(),
                    NVGRenderer.defaultFont
                )
            }

            // More content below -> show down arrow
            if (
                scrollOffset <
                maxScroll() - 0.5f
            ) {

                NVGRenderer.text(
                    g,
                    "v",
                    x + width / 2f - 3f,
                    visibleBottom - 13f,
                    11f,
                    0xAAFFFFFF.toInt(),
                    NVGRenderer.defaultFont
                )
            }
        }
    }

    // ============================================================
    // MOUSE CLICK
    // ============================================================

    fun mouseClicked(
        mouseX: Double,
        mouseY: Double,
        button: Int
    ): Boolean {

        // --------------------------------------------------------
        // 1. MODULES / SETTINGS FIRST
        // --------------------------------------------------------

        for (btn in moduleButtons) {

            if (
                btn.mouseClicked(
                    mouseX,
                    mouseY,
                    button
                )
            ) {
                return true
            }
        }

        // --------------------------------------------------------
        // 2. PANEL HEADER DRAGGING
        // --------------------------------------------------------

        if (
            mouseX >= x &&
            mouseX <= x + width &&
            mouseY >= y &&
            mouseY <= y + headerHeight &&
            button == 0
        ) {

            dragging = true

            dragOffsetX =
                (mouseX - x).toFloat()

            dragOffsetY =
                (mouseY - y).toFloat()

            return true
        }

        return false
    }

    // ============================================================
    // MOUSE DRAG
    // ============================================================

    fun mouseDragged(
        mouseX: Double,
        mouseY: Double,
        button: Int,
        dragX: Double,
        dragY: Double
    ) {

        // Give every ModuleButton a chance to handle slider dragging.
        for (btn in moduleButtons) {
            btn.mouseDragged(
                mouseX,
                mouseY
            )
        }

        // Move panel if the header is being dragged.
        if (dragging) {

            x =
                (mouseX - dragOffsetX)
                    .toFloat()

            y =
                (mouseY - dragOffsetY)
                    .toFloat()
        }
    }

    // ============================================================
    // MOUSE RELEASE
    // ============================================================

    fun mouseReleased(
        mouseX: Double,
        mouseY: Double,
        button: Int
    ) {

        dragging = false

        for (btn in moduleButtons) {

            btn.mouseReleased(
                mouseX,
                mouseY,
                button
            )
        }
    }

    // ============================================================
    // SCROLL
    // ============================================================

    /**
     * Called by ClickGUI when the mouse wheel moves.
     *
     * Returns true if this panel consumed the scroll.
     */
    fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollY: Double
    ): Boolean {

        // Mouse must be horizontally inside the panel.
        if (
            mouseX < x ||
            mouseX > x + width
        ) {
            return false
        }

        // Mouse must be below the header.
        if (mouseY < y + headerHeight) {
            return false
        }

        // Nothing to scroll.
        if (maxScroll() <= 0f) {
            return false
        }

        scrollOffset =
            (
                    scrollOffset -
                            scrollY.toFloat() * 15f
                    ).coerceIn(
                    0f,
                    maxScroll()
                )

        return true
    }
}