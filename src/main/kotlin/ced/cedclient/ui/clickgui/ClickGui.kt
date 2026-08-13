package ced.cedclient.ui.clickgui

import ced.cedclient.config.ConfigManager
import ced.cedclient.features.Category
import ced.cedclient.features.ModuleManager
import ced.cedclient.ui.inventory.editor.Popup
import ced.cedclient.ui.nvg.NVGSpecialRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

class ClickGUI : Screen(Component.literal("CedClient ClickGUI")) {

    private val panels = mutableListOf<Panel>()

    private var currentPopup: Popup? = null

    private val mc: Minecraft
        get() = Minecraft.getInstance()

    // Tracks whether a panel was moved since the last save.
    private var dirty = false

    fun openPopup(p: Popup) {
        currentPopup = p
    }

    fun closePopup() {
        currentPopup = null
    }

    // Expose panels for modules that need to apply changes immediately.
    fun getPanels(): List<Panel> = panels

    init {
        val scale = mc.window.guiScale.toFloat()
        val windowWidth = mc.window.width.toFloat()

        // Only create panels for categories that actually contain modules.
        val activeCategories = Category.values().filter { category ->
            ModuleManager.modules.any { it.category == category }
        }

        /*
         * Panel positions are stored/rendered in framebuffer coordinates.
         *
         * Keep WIDTH + GAP together so panels cannot overlap because of
         * different hardcoded layout values.
         */
        val panelWidth = (Panel.WIDTH + Panel.GAP) * scale
        val totalWidth = activeCategories.size * panelWidth
        val startX = (windowWidth - totalWidth) / 2f

        var x = startX

        for (category in activeCategories) {
            panels += Panel(
                category = category,
                x = x,
                y = 80f * scale
            )

            x += panelWidth
        }

        // Load panel positions.
        try {
            PanelConfig.ensureDefaults()
            PanelConfig.applyTo(panels)
        } catch (t: Throwable) {
            t.printStackTrace()
            println(
                "Warning: PanelConfig.applyTo failed; " +
                        "using default panel positions."
            )
        }

        // Load module/settings config.
        try {
            ConfigManager.load(panels)
        } catch (t: Throwable) {
            t.printStackTrace()
            println(
                "Warning: ConfigManager.load failed while opening " +
                        "ClickGUI; continuing with defaults."
            )
        }
    }

    override fun extractRenderState(
        context: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float
    ) {
        super.extractRenderState(
            context,
            mouseX,
            mouseY,
            delta
        )

        val scale = mc.window.guiScale.toFloat()

        /*
         * Minecraft gives us GUI-scaled mouse coordinates.
         *
         * Our Panel coordinates are framebuffer coordinates, so convert
         * the mouse coordinates before passing them to panels.
         */
        val fbMouseX = (mouseX * scale).toInt()
        val fbMouseY = (mouseY * scale).toInt()

        val fbWidth = mc.window.width
        val fbHeight = mc.window.height

        /*
         * ============================================================
         * PASS 1 — NANOVG
         * ============================================================
         *
         * Only NVG rendering happens inside this callback.
         *
         * No Minecraft text rendering is performed here. This is a
         * hard rule: renderContent runs later, inside
         * NVGSpecialRenderer.renderToTexture(), while GL_FRAMEBUFFER
         * is bound to the offscreen NVG texture rather than the real
         * one — any g.text() call queued from in here lands in the
         * wrong slot relative to Pass 2 below, which is what caused
         * tooltips-behind-panels and panel text disappearing.
         */
        TooltipManager.clear()

        /*
         * ============================================================
         * PASS 0 — HOVER + TOOLTIP REQUEST
         * ============================================================
         *
         * Must run BEFORE NVGSpecialRenderer.draw() below — hover
         * testing and any TooltipManager.request() calls it triggers
         * are plain synchronous Kotlin state, not NVG drawing, and
         * need to land before TooltipManager.drawText() reads them
         * back in Pass 2. This is also where each panel pins its
         * visible bounds and scroll position for the frame, which
         * drawNVG()/drawText() below both depend on.
         */
        for (panel in panels) {
            try {
                panel.updateHover(
                    fbMouseX,
                    fbMouseY
                )
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        NVGSpecialRenderer.draw(
            context,
            0,
            0,
            fbWidth,
            fbHeight,
            renderContent = {

                // Full-screen dark overlay.
                ced.cedclient.ui.nvg.NVGRenderer.rect(
                    0f,
                    0f,
                    fbWidth.toFloat(),
                    fbHeight.toFloat(),
                    0xAA000000.toInt()
                )

                // Draw panel backgrounds, buttons, sliders, etc. (shapes only).
                for (panel in panels) {
                    try {
                        panel.drawNVG(
                            context
                        )
                    } catch (t: Throwable) {
                        t.printStackTrace()
                    }
                }

                /*
                 * Tooltip background is NVG because it needs to sit above
                 * the NVG panel backgrounds.
                 */
                try {
                    TooltipManager.drawBackground()
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            })

        /*
         * ============================================================
         * PASS 2 — MINECRAFT TEXT
         * ============================================================
         *
         * Panel text is extracted after the NVG pass, in normal call
         * order, with the real framebuffer bound.
         */

        for (panel in panels) {
            try {
                panel.drawText(
                    context
                )
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        /*
         * Tooltip text comes after panel text so it appears above it.
         */
        try {
            TooltipManager.drawText(context)
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        /*
         * Popup is deliberately last so it appears above everything.
         */
        currentPopup?.let { popup ->
            try {
                popup.render(
                    context,
                    mouseX,
                    mouseY,
                    delta
                )
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    // ================================================================
    // MOUSE INPUT
    // ================================================================

    override fun mouseClicked(
        event: MouseButtonEvent,
        focused: Boolean
    ): Boolean {

        /*
         * Popup gets first priority.
         */
        currentPopup?.let { popup ->

            val mouseX = event.x().toInt()
            val mouseY = event.y().toInt()

            if (
                popup.mouseClicked(
                    mouseX,
                    mouseY,
                    event.button()
                )
            ) {
                return true
            }

            if (popup.isInside(mouseX, mouseY)) {
                return true
            }

            // Clicked outside popup → close it.
            currentPopup = null

            return true
        }

        val scale = mc.window.guiScale.toDouble()

        val fbX = event.x() * scale
        val fbY = event.y() * scale
        val button = event.button()

        /*
         * Panels are checked in order.
         *
         * Panel itself handles:
         * - module clicks
         * - settings
         * - sliders
         * - panel dragging
         */
        for (panel in panels) {
            try {
                if (
                    panel.mouseClicked(
                        fbX,
                        fbY,
                        button
                    )
                ) {
                    dirty = true
                    return true
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        return super.mouseClicked(
            event,
            focused
        )
    }

    override fun mouseReleased(
        event: MouseButtonEvent
    ): Boolean {

        currentPopup?.let { popup ->

            if (
                popup.mouseReleased(
                    event.x().toInt(),
                    event.y().toInt(),
                    event.button()
                )
            ) {
                return true
            }
        }

        val scale = mc.window.guiScale.toDouble()

        val fbX = event.x() * scale
        val fbY = event.y() * scale
        val button = event.button()

        for (panel in panels) {
            try {
                panel.mouseReleased(
                    fbX,
                    fbY,
                    button
                )
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        /*
         * Save panel positions after a drag.
         */
        if (dirty) {
            try {
                PanelConfig.saveAll(panels)
            } catch (t: Throwable) {
                t.printStackTrace()
            }

            dirty = false
        }

        return super.mouseReleased(event)
    }

    override fun mouseDragged(
        event: MouseButtonEvent,
        dragX: Double,
        dragY: Double
    ): Boolean {

        currentPopup?.let { popup ->

            if (
                popup.mouseDragged(
                    event.x().toInt(),
                    event.y().toInt(),
                    event.button(),
                    dragX,
                    dragY
                )
            ) {
                return true
            }
        }

        val scale = mc.window.guiScale.toDouble()

        val fbX = event.x() * scale
        val fbY = event.y() * scale

        val fbDragX = dragX * scale
        val fbDragY = dragY * scale

        val button = event.button()

        for (panel in panels) {
            try {
                panel.mouseDragged(
                    fbX,
                    fbY,
                    button,
                    fbDragX,
                    fbDragY
                )
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        /*
         * A drag may move a panel even though mouseClicked() was handled
         * before this method.
         */
        dirty = true

        return super.mouseDragged(
            event,
            dragX,
            dragY
        )
    }

    override fun mouseScrolled(
        x: Double,
        y: Double,
        scrollX: Double,
        scrollY: Double
    ): Boolean {

        /*
         * Popup gets priority.
         */
        currentPopup?.let { popup ->

            if (
                popup.mouseScrolled(
                    x.toInt(),
                    y.toInt(),
                    scrollX,
                    scrollY
                )
            ) {
                return true
            }
        }

        val scale = mc.window.guiScale.toDouble()

        val fbX = x * scale
        val fbY = y * scale

        /*
         * Give the wheel event to the panel under the cursor.
         */
        for (panel in panels) {
            try {
                if (
                    panel.mouseScrolled(
                        fbX,
                        fbY,
                        scrollY
                    )
                ) {
                    return true
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        return super.mouseScrolled(
            x,
            y,
            scrollX,
            scrollY
        )
    }

    // ================================================================
    // KEYBOARD INPUT
    // ================================================================

    override fun keyPressed(
        event: KeyEvent
    ): Boolean {

        currentPopup?.let { popup ->

            if (popup.keyPressed(event)) {
                return true
            }
        }

        return super.keyPressed(event)
    }

    override fun charTyped(
        event: CharacterEvent
    ): Boolean {

        currentPopup?.let { popup ->

            if (popup.charTyped(event)) {
                return true
            }
        }

        return super.charTyped(event)
    }

    // ================================================================
    // CLOSE
    // ================================================================

    override fun onClose() {

        /*
         * Always save positions when the GUI closes.
         */
        try {
            PanelConfig.saveAll(panels)
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        try {
            ConfigManager.save(panels)
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false
}