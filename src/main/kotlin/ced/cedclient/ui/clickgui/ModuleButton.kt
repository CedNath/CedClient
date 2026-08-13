package ced.cedclient.ui.clickgui

import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting
import ced.cedclient.ui.nvg.NVGRenderer
import ced.cedclient.utils.Debug
import net.minecraft.client.gui.GuiGraphicsExtractor

class ModuleButton(
    val module: Module,
    val panel: Panel
) {

    private var lastX = 0f
    private var lastY = 0f

    private val moduleHeight = 28f

    // Normal settings
    private val baseSettingHeight = 22f

    // NumberSetting: name/value + slider
    private val numberSettingHeight = 34f

    // "Show N more" / "Hide advanced"
    private val advancedToggleHeight = 20f

    // Whether advanced settings are currently visible.
    private var showAdvanced = false

    // Slider drag state
    private var draggingSetting: NumberSetting? = null
    private var sliderTrackX = 0f
    private var sliderTrackW = 0f

    // ------------------------------------------------------------
    // Cached hover state, computed once per frame by updateHover()
    // and read back by drawNVG()/drawText(). See the big comment on
    // updateHover() for why this exists as a separate pass.
    // ------------------------------------------------------------
    private var headerHovered = false
    private var advancedHovered = false
    private val hoveredSettings = HashSet<Setting<*>>()

    /**
     * All visible settings.
     */
    private fun allSettings(): List<Setting<*>> =
        module.getSettings().filter { it.visible }

    /**
     * Whether this module has any advanced settings.
     */
    private fun hasAdvancedSettings(): Boolean =
        allSettings().any { it.advanced }

    /**
     * Settings currently displayed.
     *
     * Normal settings are always visible.
     * Advanced settings only appear when showAdvanced == true.
     */
    private fun displayedSettings(): List<Setting<*>> {
        val all = allSettings()

        return if (showAdvanced) {
            all
        } else {
            all.filter { !it.advanced }
        }
    }

    /**
     * Total height of this module button including its settings.
     */
    val height: Float
        get() {
            if (!module.expanded) {
                return moduleHeight
            }

            var total = moduleHeight

            for (setting in displayedSettings()) {
                total += getSettingHeight(setting)
            }

            if (hasAdvancedSettings()) {
                total += advancedToggleHeight
            }

            return total
        }

    private fun getSettingHeight(setting: Setting<*>): Float {
        return if (setting is NumberSetting) {
            numberSettingHeight
        } else {
            baseSettingHeight
        }
    }

    /**
     * Convert a mouse position into a NumberSetting value.
     */
    private fun updateSliderValue(
        setting: NumberSetting,
        mouseX: Double,
        trackX: Float,
        trackW: Float
    ) {
        if (trackW <= 0f) return

        val ratio =
            ((mouseX - trackX) / trackW)
                .coerceIn(0.0, 1.0)

        setting.set(
            setting.min +
                    ratio * (setting.max - setting.min)
        )
    }

    /**
     * Right-hand edge used for tooltips.
     */
    private fun tooltipAnchorX(): Float {
        return panel.x + panel.width + 8f
    }

    // ============================================================
    // PASS 0 — HOVER + TOOLTIP REQUEST (synchronous, NOT inside NVG)
    // ============================================================
    //
    // Must be called from ClickGUI.extractRenderState() BEFORE
    // NVGSpecialRenderer.draw() is even invoked — not from inside its
    // renderContent lambda.
    //
    // Root cause of the empty tooltip box: renderContent doesn't run
    // synchronously when NVGSpecialRenderer.draw() is called — it's a
    // PictureInPictureRenderer, and renderToTexture() (which actually
    // invokes renderContent) runs later, during Minecraft's real
    // render pass, AFTER extractRenderState() (including our own
    // Pass 2 / TooltipManager.drawText()) has already returned for
    // that frame. TooltipManager.request() used to live inside
    // drawNVG(), which used to be called from renderContent — so
    // "text" was always still null when drawText() read it, and only
    // got set one step too late for a request that had already been
    // missed. Confirmed by the log: "drawText called, text = null"
    // printed BEFORE "Tooltip requested: ..." every single frame.
    //
    // Hover-testing and TooltipManager.request() don't need an NVG
    // frame at all — they're pure Kotlin state — so they're moved
    // here, into a plain synchronous pass. Only actual nvgRect() etc.
    // calls need to stay deferred inside renderContent.

    fun updateHover(
        mouseX: Int,
        mouseY: Int,
        px: Float,
        py: Float,
        visibleTop: Float = Float.NEGATIVE_INFINITY,
        visibleBottom: Float = Float.POSITIVE_INFINITY
    ) {
        lastX = px
        lastY = py

        headerHovered = false
        advancedHovered = false
        hoveredSettings.clear()

        val width = panel.width

        // Completely outside the visible panel body.
        if (py + height < visibleTop || py > visibleBottom) {
            return
        }

        // ------------------------------------------------------------
        // MODULE HEADER
        // ------------------------------------------------------------

        headerHovered =
            mouseX >= px &&
                    mouseX <= px + width &&
                    mouseY >= py &&
                    mouseY <= py + moduleHeight

        if (headerHovered) {
            val desc = module.description

            if (!desc.isNullOrBlank()) {
                TooltipManager.request(
                    desc,
                    tooltipAnchorX(),
                    py
                )
            }
        }

        if (!module.expanded) {
            return
        }

        // ------------------------------------------------------------
        // SETTINGS
        // ------------------------------------------------------------

        var offsetY = py + moduleHeight

        val settings = displayedSettings()

        for (setting in settings) {

            val settingHeight =
                getSettingHeight(setting)

            if (
                offsetY + settingHeight >= visibleTop &&
                offsetY <= visibleBottom
            ) {

                val x = px + 10f
                val y = offsetY
                val width2 = width - 20f

                val hovered =
                    mouseX >= x &&
                            mouseX <= x + width2 &&
                            mouseY >= y &&
                            mouseY <= y + settingHeight

                if (hovered) {
                    hoveredSettings += setting

                    val fontSize = 13f

                    when (setting) {

                        is BooleanSetting -> {
                            val labelMaxWidth = width2 - 18f - 4f
                            val label = NVGRenderer.truncate(
                                setting.name,
                                labelMaxWidth,
                                fontSize,
                                NVGRenderer.defaultFont
                            )
                            if (label != setting.name) {
                                TooltipManager.request(
                                    setting.name,
                                    tooltipAnchorX(),
                                    y
                                )
                            }
                        }

                        is ActionSetting -> {
                            val labelMaxWidth = width2 - 6f - 6f
                            val label = NVGRenderer.truncate(
                                setting.label,
                                labelMaxWidth,
                                fontSize,
                                NVGRenderer.defaultFont
                            )
                            if (label != setting.label) {
                                TooltipManager.request(
                                    setting.label,
                                    tooltipAnchorX(),
                                    y
                                )
                            }
                        }

                        is NumberSetting -> {
                            val valueText = "%.1f".format(setting.value)
                            val valueWidth = NVGRenderer.textWidth(
                                valueText,
                                12f,
                                NVGRenderer.defaultFont
                            )
                            val gap = 8f
                            val nameMaxWidth =
                                width2 - 6f - 6f - valueWidth - gap
                            val name = NVGRenderer.truncate(
                                setting.name,
                                nameMaxWidth,
                                12f,
                                NVGRenderer.defaultFont
                            )
                            if (name != setting.name) {
                                TooltipManager.request(
                                    setting.name,
                                    tooltipAnchorX(),
                                    y
                                )
                            }
                        }

                        else -> {}
                    }
                }
            }

            offsetY += settingHeight
        }

        // ------------------------------------------------------------
        // ADVANCED SETTINGS TOGGLE
        // ------------------------------------------------------------

        if (hasAdvancedSettings()) {

            if (
                offsetY + advancedToggleHeight >= visibleTop &&
                offsetY <= visibleBottom
            ) {
                val x = px + 10f
                val y = offsetY
                val width2 = width - 20f

                advancedHovered =
                    mouseX >= x &&
                            mouseX <= x + width2 &&
                            mouseY >= y &&
                            mouseY <= y + advancedToggleHeight
            }
        }
    }

    // ============================================================
    // PASS 1 — NANOVG SHAPES ONLY
    // ============================================================
    //
    // Runs inside NVGSpecialRenderer's renderContent lambda. Reads
    // ONLY the hover flags already decided by updateHover() — no
    // hover math, no TooltipManager.request() calls here. See the
    // long comment on updateHover() for why.
    //
    // NO NVGRenderer.text()/g.text() calls belong in here either —
    // that's a separate rule from before: g.text() only appends to
    // guiRenderState, and doing that from in here (while the wrong
    // FBO is bound, at an unpredictable point relative to Pass 2)
    // is what caused tooltip-behind-panel / panel-text-disappearing
    // originally.

    fun drawNVG(
        g: GuiGraphicsExtractor,
        visibleTop: Float = Float.NEGATIVE_INFINITY,
        visibleBottom: Float = Float.POSITIVE_INFINITY
    ) {
        val px = lastX
        val py = lastY
        val width = panel.width

        if (py + height < visibleTop || py > visibleBottom) {
            return
        }

        // ------------------------------------------------------------
        // MODULE HEADER
        // ------------------------------------------------------------

        val bgColor =
            if (module.enabled) {
                0xFF3A8FFF.toInt()
            } else {
                0xFF2A2A2A.toInt()
            }

        NVGRenderer.rect(
            px,
            py,
            width,
            moduleHeight,
            bgColor
        )

        if (headerHovered) {
            NVGRenderer.rect(
                px,
                py,
                width,
                moduleHeight,
                0x20FFFFFF
            )
        }

        // ------------------------------------------------------------
        // SETTINGS
        // ------------------------------------------------------------

        if (!module.expanded) {
            return
        }

        var offsetY = py + moduleHeight

        val settings = displayedSettings()

        for (setting in settings) {

            val settingHeight =
                getSettingHeight(setting)

            if (
                offsetY + settingHeight >= visibleTop &&
                offsetY <= visibleBottom
            ) {
                drawSetting(
                    setting,
                    px + 10f,
                    offsetY,
                    width - 20f
                )
            }

            offsetY += settingHeight
        }

        // ------------------------------------------------------------
        // ADVANCED SETTINGS TOGGLE (shape only)
        // ------------------------------------------------------------

        if (hasAdvancedSettings()) {

            if (
                offsetY + advancedToggleHeight >= visibleTop &&
                offsetY <= visibleBottom
            ) {
                NVGRenderer.rect(
                    px + 10f,
                    offsetY,
                    width - 20f,
                    advancedToggleHeight,
                    if (advancedHovered) {
                        0xFF3A3A3A.toInt()
                    } else {
                        0xFF2A2A2A.toInt()
                    }
                )
            }
        }
    }

    // ============================================================
    // PASS 2 — MINECRAFT TEXT ONLY
    // ============================================================
    //
    // Called from Panel.drawText(), AFTER NVGSpecialRenderer.draw()
    // has returned in ClickGUI.extractRenderState() — normal call
    // order, real framebuffer bound. Uses lastX/lastY captured by
    // updateHover() this same frame.

    fun drawText(
        g: GuiGraphicsExtractor,
        visibleTop: Float = Float.NEGATIVE_INFINITY,
        visibleBottom: Float = Float.POSITIVE_INFINITY
    ) {
        val px = lastX
        val py = lastY
        val width = panel.width

        if (py + height < visibleTop || py > visibleBottom) {
            return
        }

        // ------------------------------------------------------------
        // MODULE NAME
        // ------------------------------------------------------------

        val hasArrow = module.getSettings().isNotEmpty()

        val nameMaxWidth =
            width -
                    8f -
                    if (hasArrow) 16f else 6f

        val displayName =
            NVGRenderer.truncate(
                module.name,
                nameMaxWidth,
                14f,
                NVGRenderer.defaultFont
            )

        NVGRenderer.text(
            g,
            displayName,
            px + 8f,
            py + 4f,
            14f,
            0xFFFFFFFF.toInt(),
            NVGRenderer.defaultFont
        )

        // ------------------------------------------------------------
        // EXPAND ARROW
        // ------------------------------------------------------------

        if (hasArrow) {

            val arrow =
                if (module.expanded) {
                    "▼"
                } else {
                    "▶"
                }

            NVGRenderer.text(
                g,
                arrow,
                px + width - 14f,
                py + 4f,
                14f,
                0xFFFFFFFF.toInt(),
                NVGRenderer.defaultFont
            )
        }

        if (!module.expanded) {
            return
        }

        // ------------------------------------------------------------
        // SETTINGS TEXT
        // ------------------------------------------------------------

        var offsetY = py + moduleHeight

        val settings = displayedSettings()

        for (setting in settings) {

            val settingHeight =
                getSettingHeight(setting)

            if (
                offsetY + settingHeight >= visibleTop &&
                offsetY <= visibleBottom
            ) {
                drawSettingText(
                    g,
                    setting,
                    px + 10f,
                    offsetY,
                    width - 20f
                )
            }

            offsetY += settingHeight
        }

        // ------------------------------------------------------------
        // ADVANCED SETTINGS TOGGLE TEXT
        // ------------------------------------------------------------

        if (hasAdvancedSettings()) {

            if (
                offsetY + advancedToggleHeight >= visibleTop &&
                offsetY <= visibleBottom
            ) {
                val hiddenCount =
                    allSettings().count { it.advanced }

                val label =
                    if (showAdvanced) {
                        "\u25BE Hide advanced"
                    } else {
                        "\u25B8 Show $hiddenCount more"
                    }

                NVGRenderer.text(
                    g,
                    label,
                    px + 10f + 6f,
                    offsetY + 4f,
                    12f,
                    0xFFAAAAAA.toInt(),
                    NVGRenderer.defaultFont
                )
            }
        }
    }

    // ============================================================
    // SETTINGS — SHAPES ONLY (reads cached hover)
    // ============================================================

    private fun drawSetting(
        setting: Setting<*>,
        x: Float,
        y: Float,
        width: Float
    ) {

        val settingHeight =
            getSettingHeight(setting)

        val hovered = setting in hoveredSettings

        // Hover background
        if (hovered) {
            NVGRenderer.rect(
                x,
                y,
                width,
                settingHeight,
                0x20FFFFFF
            )
        }

        when (setting) {

            // ====================================================
            // BOOLEAN
            // ====================================================

            is BooleanSetting -> {

                val boxSize = 12f

                NVGRenderer.rect(
                    x,
                    y + (baseSettingHeight - boxSize) / 2f,
                    boxSize,
                    boxSize,
                    if (setting.value) {
                        0xFF3A8FFF.toInt()
                    } else {
                        0xFF555555.toInt()
                    }
                )
            }

            // ====================================================
            // ACTION
            // ====================================================

            is ActionSetting -> {

                NVGRenderer.rect(
                    x,
                    y,
                    width,
                    baseSettingHeight,
                    0xFF3A3A3A.toInt()
                )
            }

            // ====================================================
            // NUMBER
            // ====================================================

            is NumberSetting -> {

                NVGRenderer.rect(
                    x,
                    y,
                    width,
                    numberSettingHeight,
                    0xFF2E2E2E.toInt()
                )

                // -----------------------------------------------
                // SLIDER TRACK
                // -----------------------------------------------

                val trackX =
                    x + 6f

                val trackW =
                    width - 12f

                val trackY =
                    y + 24f

                val trackH =
                    6f

                // Track background
                NVGRenderer.rect(
                    trackX,
                    trackY,
                    trackW,
                    trackH,
                    0xFF1A1A1A.toInt(),
                    trackH / 2f
                )

                // -----------------------------------------------
                // SLIDER FILL
                // -----------------------------------------------

                val range =
                    setting.max - setting.min

                val ratio =
                    if (range == 0.0) {
                        0f
                    } else {
                        (
                                (setting.value - setting.min) /
                                        range
                                )
                            .coerceIn(0.0, 1.0)
                            .toFloat()
                    }

                val fillW =
                    trackW * ratio

                if (fillW > 0f) {
                    NVGRenderer.rect(
                        trackX,
                        trackY,
                        fillW,
                        trackH,
                        0xFF3A8FFF.toInt(),
                        trackH / 2f
                    )
                }

                // -----------------------------------------------
                // SLIDER HANDLE
                // -----------------------------------------------

                NVGRenderer.circle(
                    trackX + fillW,
                    trackY + trackH / 2f,
                    6f,
                    0xFFFFFFFF.toInt()
                )
            }
        }
    }

    // ============================================================
    // SETTINGS — TEXT ONLY
    // ============================================================

    private fun drawSettingText(
        g: GuiGraphicsExtractor,
        setting: Setting<*>,
        x: Float,
        y: Float,
        width: Float
    ) {

        val textY = y + 6f
        val fontSize = 13f

        when (setting) {

            is BooleanSetting -> {

                val labelMaxWidth =
                    width - 18f - 4f

                val label =
                    NVGRenderer.truncate(
                        setting.name,
                        labelMaxWidth,
                        fontSize,
                        NVGRenderer.defaultFont
                    )

                NVGRenderer.text(
                    g,
                    label,
                    x + 18f,
                    textY,
                    fontSize,
                    0xFFFFFFFF.toInt(),
                    NVGRenderer.defaultFont
                )
            }

            is ActionSetting -> {

                val labelMaxWidth =
                    width - 6f - 6f

                val label =
                    NVGRenderer.truncate(
                        setting.label,
                        labelMaxWidth,
                        fontSize,
                        NVGRenderer.defaultFont
                    )

                NVGRenderer.text(
                    g,
                    label,
                    x + 6f,
                    textY,
                    fontSize,
                    0xFFFFFFFF.toInt(),
                    NVGRenderer.defaultFont
                )
            }

            is NumberSetting -> {

                val labelY = y + 4f

                val valueText =
                    "%.1f".format(setting.value)

                val valueWidth =
                    NVGRenderer.textWidth(
                        valueText,
                        12f,
                        NVGRenderer.defaultFont
                    )

                val gap = 8f

                val nameMaxWidth =
                    width -
                            6f -
                            6f -
                            valueWidth -
                            gap

                val name =
                    NVGRenderer.truncate(
                        setting.name,
                        nameMaxWidth,
                        12f,
                        NVGRenderer.defaultFont
                    )

                NVGRenderer.text(
                    g,
                    name,
                    x + 6f,
                    labelY,
                    12f,
                    0xFFFFFFFF.toInt(),
                    NVGRenderer.defaultFont
                )

                NVGRenderer.text(
                    g,
                    valueText,
                    x + width - 6f - valueWidth,
                    labelY,
                    12f,
                    0xFFAAAAAA.toInt(),
                    NVGRenderer.defaultFont
                )
            }
        }
    }

    // ============================================================
    // MOUSE INPUT
    // ============================================================

    fun mouseClicked(
        mouseX: Double,
        mouseY: Double,
        button: Int
    ): Boolean {

        val x = lastX
        val y = lastY
        val width = panel.width

        // --------------------------------------------------------
        // MODULE HEADER
        // --------------------------------------------------------

        if (
            mouseX >= x &&
            mouseX <= x + width &&
            mouseY >= y &&
            mouseY <= y + moduleHeight
        ) {

            when (button) {

                // Left click = toggle module
                0 -> {
                    module.toggle()
                    return true
                }

                // Right click = expand/collapse
                1 -> {
                    if (module.getSettings().isNotEmpty()) {
                        module.expanded =
                            !module.expanded

                        return true
                    }
                }
            }
        }

        // --------------------------------------------------------
        // SETTINGS
        // --------------------------------------------------------

        if (module.expanded) {

            var offsetY =
                y + moduleHeight

            val settings =
                displayedSettings()

            for (setting in settings) {

                val settingHeight =
                    getSettingHeight(setting)

                if (
                    mouseX >= x &&
                    mouseX <= x + width &&
                    mouseY >= offsetY &&
                    mouseY <= offsetY + settingHeight
                ) {

                    when (setting) {

                        // ----------------------------------------
                        // BOOLEAN
                        // ----------------------------------------

                        is BooleanSetting -> {

                            if (button == 0) {
                                setting.set(
                                    !setting.value
                                )

                                return true
                            }
                        }

                        // ----------------------------------------
                        // ACTION
                        // ----------------------------------------

                        is ActionSetting -> {

                            if (button == 0) {

                                Debug.log(
                                    "DEBUG: Click detected on ActionSetting '${setting.name}'"
                                )

                                setting.debugInvoke()

                                return true
                            }
                        }

                        // ----------------------------------------
                        // NUMBER
                        // ----------------------------------------

                        is NumberSetting -> {

                            val trackX =
                                x + 6f

                            val trackW =
                                width - 12f

                            val trackY =
                                offsetY + 24f

                            val trackH =
                                6f

                            // Give the thin slider a larger
                            // clickable area.
                            val hitPadding =
                                6f

                            if (
                                button == 0 &&
                                mouseX >= trackX &&
                                mouseX <= trackX + trackW &&
                                mouseY >= trackY - hitPadding &&
                                mouseY <= trackY + trackH + hitPadding
                            ) {

                                updateSliderValue(
                                    setting,
                                    mouseX,
                                    trackX,
                                    trackW
                                )

                                draggingSetting =
                                    setting

                                sliderTrackX =
                                    trackX

                                sliderTrackW =
                                    trackW

                                return true
                            }
                        }

                        else -> {}
                    }
                }

                offsetY += settingHeight
            }

            // ----------------------------------------------------
            // ADVANCED SETTINGS TOGGLE
            // ----------------------------------------------------

            if (hasAdvancedSettings()) {

                if (
                    button == 0 &&
                    mouseX >= x &&
                    mouseX <= x + width &&
                    mouseY >= offsetY &&
                    mouseY <= offsetY + advancedToggleHeight
                ) {

                    showAdvanced =
                        !showAdvanced

                    return true
                }
            }
        }

        return false
    }

    // ============================================================
    // SLIDER DRAGGING
    // ============================================================

    /**
     * Called by Panel.mouseDragged() for every ModuleButton.
     *
     * Only the ModuleButton that owns the active slider will
     * actually update anything.
     */
    fun mouseDragged(
        mouseX: Double,
        mouseY: Double
    ) {

        val setting =
            draggingSetting
                ?: return

        updateSliderValue(
            setting,
            mouseX,
            sliderTrackX,
            sliderTrackW
        )
    }

    // ============================================================
    // MOUSE RELEASE
    // ============================================================

    fun mouseReleased(
        mouseX: Double,
        mouseY: Double,
        button: Int
    ) {

        draggingSetting = null
    }
}