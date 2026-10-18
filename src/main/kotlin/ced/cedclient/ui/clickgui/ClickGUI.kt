package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.misc.ChatPing
import ced.cedclient.features.impl.misc.Theme
import ced.cedclient.config.ConfigManager
import ced.cedclient.features.Category
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.utils.Color
import ced.cedclient.utils.Color.Companion.withAlpha
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.clickGuiScale
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.sign
import ced.cedclient.utils.ui.mouseX as cedMouseX
import ced.cedclient.utils.ui.mouseY as cedMouseY

/**
 * The ClickGUI screen. Ported from Odin's clickgui/ClickGUI.kt, which is an
 * `object` — this has to be a `class` instead, since CedClient.kt and
 * CedClientCommand.kt already do `client.setScreen(ClickGUI())`, i.e. treat
 * it as instantiable. The description-tooltip state (`setDescription`) that
 * RenderableSetting calls statically lives in the companion object, so that
 * call site keeps working unchanged no matter how many times a ClickGUI gets
 * opened and closed.
 *
 * getPanels() exists because ResetPanels.kt (Phase-Misc) needs it to mutate
 * the live, currently-open panel layout.
 *
 * openPopup()/closePopup() (Phase 6) give EntityESP.kt's three "Select ___"
 * ActionSettings — `(mc.screen as? ClickGUI)?.openPopup(MobFilterPopup())`
 * etc. — a modal to open into. Only one popup can be open at a time; opening
 * a second just replaces the first. While a popup is open it gets first and
 * exclusive claim on input (see the override methods below) — panels don't
 * receive clicks/scroll/keys until it closes, whether that's via its own
 * close button, clicking outside it, or Escape.
 */
class ClickGUI : Screen(Component.literal("Click GUI")) {

    private val panelSettings: List<PanelSetting> =
        Category.entries.map { PanelSetting(it) }.also { ConfigManager.loadPanels(it) }

    private val panels: List<Panel> = panelSettings.map { Panel(it) }

    /** When this screen opened / the current popup appeared (System.nanoTime), for the open animations. */
    private var openedAt = System.nanoTime()
    private var popupOpenedAt = 0L

    private var activePopup: Popup? = null

    /** Live panel layout state, exposed for ResetPanels to mutate while this screen is open. */
    fun getPanels(): List<PanelSetting> = panelSettings

    /** Opens [popup] as a modal on top of this ClickGUI, replacing whichever popup (if any) is already open. */
    fun openPopup(popup: Popup) {
        val now = System.nanoTime()
        // Opened straight into a popup (/cc cosmetic etc.): skip the panel slide-in behind it, just pop the popup.
        if ((now - openedAt) / 1_000_000f < 50f) openedAt = now - PANELS_DONE_NANOS
        activePopup = popup
        popupOpenedAt = now
    }

    fun closePopup() {
        activePopup = null
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        // NVGSpecialRenderer.draw's last parameter is afterRender (nullable, defaulted), not
        // renderContent -- a trailing lambda here binds to afterRender and leaves renderContent
        // unfilled, so this has to be a named argument instead of trailing-lambda syntax.
        NVGSpecialRenderer.draw(context, 0, 0, context.guiWidth(), context.guiHeight(), renderContent = {
            val scaledMouseX = cedMouseX / clickGuiScale
            val scaledMouseY = cedMouseY / clickGuiScale

            // Whatever is hovered re-sets the description below while drawing. Clearing it first stops a stale one
            // (e.g. the GUI was closed while hovering a setting, so its hover state never reset) lingering on reopen.
            desc.text = ""

            NVGRenderer.scale(clickGuiScale, clickGuiScale)

            // canvasWidth/canvasHeight (NOT context.guiWidth()/guiHeight()) is the actual
            // coordinate space everything in this block draws into -- see NVGRenderer's
            // doc comment on canvasWidth for why those two aren't the same thing.
            val canvasWidth = NVGRenderer.canvasWidth / clickGuiScale
            val canvasHeight = NVGRenderer.canvasHeight / clickGuiScale

            // Our own background dim instead of vanilla's menu blur (see extractBackground).
            // Theme > Animation Speed scales every open / popup timing.
            val elapsed = (System.nanoTime() - openedAt) / 1_000_000f * Theme.animationSpeed.value.toFloat()
            val dim = easeOutCubic(elapsed / DIM_FADE_MS)
            NVGRenderer.rect(0f, 0f, canvasWidth, canvasHeight, Colors.BLACK.withAlpha(BACKGROUND_DIM * dim).rgba)

            // Bottom-center, a fixed 50px gap above the screen edge.
            NVGRenderer.push()
            NVGRenderer.globalAlpha(dim)
            SearchBar.draw(
                canvasWidth / 2f - 175f,
                canvasHeight - 90f,
                scaledMouseX,
                scaledMouseY
            )
            NVGRenderer.pop()

            // Opening: each panel animates in (Theme > Open Animation), left to right, slightly after the one before it.
            val draggedPanel = panels.firstOrNull { it.dragging }
            val openStyle = Theme.openAnimation.value
            panels.forEachIndexed { i, panel ->
                if (panel == draggedPanel) return@forEachIndexed
                val raw = if (openStyle == "None") 1f else ((elapsed - i * PANEL_STAGGER_MS) / PANEL_ANIM_MS).coerceIn(0f, 1f)
                if (raw <= 0f) return@forEachIndexed
                NVGRenderer.push()
                panelOpenTransform(openStyle, raw, panel)
                panel.draw(scaledMouseX, scaledMouseY)
                NVGRenderer.pop()
            }

            draggedPanel?.draw(scaledMouseX, scaledMouseY)

            activePopup?.let { popup ->
                popup.x = (canvasWidth - popup.width) / 2f
                popup.y = (canvasHeight - popup.height) / 2f

                // Popup: its own quick animation (Theme > Popup Animation) around its centre.
                val raw = popupProgress()
                NVGRenderer.rect(0f, 0f, canvasWidth, canvasHeight, Colors.BLACK.withAlpha(0.55f * easeOutCubic(raw)).rgba)
                NVGRenderer.push()
                popupTransform(Theme.popupAnimation.value, raw, popup)
                popup.draw(scaledMouseX, scaledMouseY)
                NVGRenderer.pop()
            }

            // After the popup, so settings shown inside a SettingsPopup get their tooltip on top of it.
            desc.render()
        })

        // Vanilla-drawn popup extras (item icons), on a layer above the NanoVG one. Skipped while the popup is
        // still popping in (they wouldn't follow its fade/scale) and before NanoVG has laid out a first frame.
        activePopup?.let { popup ->
            val popupDone = popupProgress() >= 1f
            if (popupDone && NVGRenderer.canvasWidth > 0f) {
                context.nextStratum()
                popup.extractItems(context, clickGuiScale * context.guiWidth() / NVGRenderer.canvasWidth)
            }
        }
        super.extractRenderState(context, mouseX, mouseY, deltaTicks)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double
    ): Boolean {
        // Same deal as mouseClicked above: the mouseX/mouseY Minecraft hands this
        // callback are in its own GUI-scaled coordinate space, not the raw
        // cedMouseX/cedMouseY (mouseHandler.xpos/ypos) space everything else in
        // this class -- popup positioning, hover checks, mouseClicked -- is built
        // on. Using the passed-in doubles here meant the popup's hover check
        // almost never matched, so wheel-scrolling over a popup silently did
        // nothing. Panels never had this bug: Panel.handleScroll() doesn't take
        // mouse coordinates at all, it reads cedMouseX/cedMouseY internally via
        // isAreaHovered.
        val actualAmount = (verticalAmount.sign * 16).toInt()
        val scaledMouseX = cedMouseX / clickGuiScale
        val scaledMouseY = cedMouseY / clickGuiScale

        activePopup?.let { popup ->
            popup.mouseScrolled(scaledMouseX, scaledMouseY, actualAmount)
            return true
        }

        for (i in panels.size - 1 downTo 0) {
            if (panels[i].handleScroll(actualAmount)) return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        // Screen doesn't expose bare mouseX/mouseY fields in this API -- cedMouseX/cedMouseY
        // (ced.cedclient.utils.ui.mouseX/mouseY) is the live-mouse-position source the rest of
        // this class already uses (see extractRenderState).
        val scaledMouseX = cedMouseX / clickGuiScale
        val scaledMouseY = cedMouseY / clickGuiScale

        activePopup?.let { popup ->
            when {
                popup.isCloseButtonHovered(scaledMouseX, scaledMouseY) -> closePopup()
                popup.isInside(scaledMouseX, scaledMouseY) -> popup.mouseClicked(scaledMouseX, scaledMouseY, mouseButtonEvent)
                else -> closePopup() // click landed outside the modal — dismiss it
            }
            return true
        }

        SearchBar.mouseClicked(scaledMouseX, scaledMouseY, mouseButtonEvent)
        for (i in panels.size - 1 downTo 0) {
            if (panels[i].mouseClicked(scaledMouseX, scaledMouseY, mouseButtonEvent)) return true
        }
        return super.mouseClicked(mouseButtonEvent, bl)
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        activePopup?.let { popup ->
            popup.mouseReleased(mouseButtonEvent)
            return true
        }

        SearchBar.mouseReleased()
        for (i in panels.size - 1 downTo 0) {
            panels[i].mouseReleased(mouseButtonEvent)
        }
        return super.mouseReleased(mouseButtonEvent)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        activePopup?.let { popup ->
            return popup.keyTyped(characterEvent)
        }

        SearchBar.keyTyped(characterEvent)
        for (i in panels.size - 1 downTo 0) {
            if (panels[i].keyTyped(characterEvent)) return true
        }
        return super.charTyped(characterEvent)
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        activePopup?.let { popup ->
            // The popup gets first go, so a keybind setting inside it can take Escape to unbind.
            if (popup.keyPressed(keyEvent)) return true
            if (keyEvent.key == GLFW.GLFW_KEY_ESCAPE) closePopup()
            return true
        }

        SearchBar.keyPressed(keyEvent)
        for (i in panels.size - 1 downTo 0) {
            if (panels[i].keyPressed(keyEvent)) return true
        }
        return super.keyPressed(keyEvent)
    }

    // openedAt is set at construction, not in init(): init() also runs on every window resize.
    override fun init() {
        super.init()
    }

    private fun popupProgress(): Float =
        if (Theme.popupAnimation.value == "None") 1f
        else ((System.nanoTime() - popupOpenedAt) / 1_000_000f * Theme.animationSpeed.value.toFloat() / POPUP_ANIM_MS).coerceIn(0f, 1f)

    /** Sets alpha + transform for one panel at open-animation progress [raw] (0..1, linear). */
    private fun panelOpenTransform(style: String, raw: Float, panel: Panel) {
        val e = easeOutCubic(raw)
        val x = panel.panelSetting.x
        val y = panel.panelSetting.y
        val cx = x + Panel.WIDTH / 2f
        val cy = y + panel.previousHeight.coerceAtLeast(Panel.HEIGHT) / 2f
        var alpha = e
        when (style) {
            "Slide Up" -> NVGRenderer.translate(0f, (1f - e) * PANEL_SLIDE)
            "Slide Down" -> NVGRenderer.translate(0f, -(1f - e) * PANEL_SLIDE)
            "Slide Left" -> NVGRenderer.translate((1f - e) * PANEL_SLIDE * 2f, 0f)
            "Slide Right" -> NVGRenderer.translate(-(1f - e) * PANEL_SLIDE * 2f, 0f)
            "Zoom In" -> (0.85f + 0.15f * e).let { scaleAround(cx, cy, it, it) }
            "Zoom Out" -> (1.15f - 0.15f * e).let { scaleAround(cx, cy, it, it) }
            "Pop" -> easeOutBack(raw).let { scaleAround(cx, cy, it, it) }
            "Bounce" -> {
                NVGRenderer.translate(0f, (1f - easeOutBounce(raw)) * -PANEL_SLIDE * 3f)
                alpha = (raw * 4f).coerceAtMost(1f)
            }
            "Elastic" -> {
                NVGRenderer.translate(0f, (1f - easeOutElastic(raw)) * PANEL_SLIDE * 2f)
                alpha = (raw * 4f).coerceAtMost(1f)
            }
            // Falls in from above the screen and lands with a bounce.
            "Drop" -> {
                NVGRenderer.translate(0f, (1f - easeOutBounce(raw)) * -(y + panel.previousHeight + 40f))
                alpha = 1f
            }
            // Unfolds downwards from its header.
            "Flip" -> scaleAround(cx, y, 1f, e)
            // Swings down around its top-left corner.
            "Swing" -> {
                NVGRenderer.translate(x, y)
                NVGRenderer.rotate((1f - easeOutBack(raw)) * -0.6f)
                NVGRenderer.translate(-x, -y)
            }
            "Spin" -> {
                NVGRenderer.translate(cx, cy)
                NVGRenderer.rotate((1f - e) * -1.2f)
                NVGRenderer.scale(0.6f + 0.4f * e, 0.6f + 0.4f * e)
                NVGRenderer.translate(-cx, -cy)
            }
            "Cascade" -> NVGRenderer.translate(-(1f - e) * PANEL_SLIDE * 1.5f, -(1f - e) * PANEL_SLIDE * 1.5f)
            "None" -> alpha = 1f
            else -> {} // Fade: alpha only
        }
        NVGRenderer.globalAlpha(alpha)
    }

    /** Same idea for the popup, around its centre. */
    private fun popupTransform(style: String, raw: Float, popup: Popup) {
        val e = easeOutCubic(raw)
        val cx = popup.x + popup.width / 2f
        val cy = popup.y + popup.height / 2f
        var alpha = e
        when (style) {
            "Pop" -> (0.96f + 0.04f * e).let { scaleAround(cx, cy, it, it) }
            "Zoom" -> (0.5f + 0.5f * easeOutBack(raw)).let { scaleAround(cx, cy, it, it) }
            "Slide Up" -> NVGRenderer.translate(0f, (1f - e) * 40f)
            "Slide Down" -> NVGRenderer.translate(0f, -(1f - e) * 40f)
            "Bounce" -> {
                NVGRenderer.translate(0f, (1f - easeOutBounce(raw)) * -80f)
                alpha = (raw * 4f).coerceAtMost(1f)
            }
            "None" -> alpha = 1f
            else -> {} // Fade
        }
        NVGRenderer.globalAlpha(alpha)
    }

    private fun scaleAround(cx: Float, cy: Float, sx: Float, sy: Float) {
        NVGRenderer.translate(cx, cy)
        NVGRenderer.scale(sx, sy.coerceAtLeast(0.001f))
        NVGRenderer.translate(-cx, -cy)
    }

    private fun easeOutCubic(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        val inv = 1f - t
        return 1f - inv * inv * inv
    }

    /** Overshoots to about 110% then settles. */
    private fun easeOutBack(x: Float): Float {
        val t = x.coerceIn(0f, 1f) - 1f
        val c = 1.70158f
        return 1f + (c + 1f) * t * t * t + c * t * t
    }

    private fun easeOutBounce(x: Float): Float {
        var t = x.coerceIn(0f, 1f)
        val n = 7.5625f
        val d = 2.75f
        return when {
            t < 1f / d -> n * t * t
            t < 2f / d -> { t -= 1.5f / d; n * t * t + 0.75f }
            t < 2.5f / d -> { t -= 2.25f / d; n * t * t + 0.9375f }
            else -> { t -= 2.625f / d; n * t * t + 0.984375f }
        }
    }

    private fun easeOutElastic(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        if (t == 0f || t == 1f) return t
        return (Math.pow(2.0, -10.0 * t) * Math.sin((t * 10.0 - 0.75) * (2.0 * Math.PI / 3.0))).toFloat() + 1f
    }

    /**
     * No vanilla menu background texture; the dim is drawn in extractRenderState, fading in with the open animation.
     * Mod Settings > Menu Blur asks for vanilla's blur pass, at Blur Strength (GameRendererBlurMixin), not the
     * player's own menu blur setting.
     */
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        if (blurRadius() >= 1) graphics.blurBeforeThisStratum()
    }

    /** Blur radius this frame: Blur Strength, eased in with the background dim when the menu opens. 0 = off. */
    fun blurRadius(): Int {
        if (!Theme.menuBlur.value) return 0
        val elapsed = (System.nanoTime() - openedAt) / 1_000_000f * Theme.animationSpeed.value.toFloat()
        return Math.round(Theme.blurStrength.value.toFloat() * easeOutCubic(elapsed / DIM_FADE_MS))
    }

    override fun onClose() {
        for (panel in panels.filter { it.panelSetting.extended }.reversed()) {
            for (moduleButton in panel.moduleButtons.filter { it.extended }) {
                for (setting in moduleButton.representableSettings) {
                    setting.listening = false
                }
            }
        }

        ConfigManager.save(panelSettings)
        ChatPing.saveAlerts()
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false

    companion object {

        const val roundedPanelBottom = true
        /** The GUI accent -- set in the Theme module. */
        val clickGUIColor: Color get() = Theme.guiAccent.value

        // Open animation timings (ms) and sizes.
        private const val BACKGROUND_DIM = 0.35f
        private const val DIM_FADE_MS = 160f
        private const val PANEL_ANIM_MS = 260f
        private const val PANEL_STAGGER_MS = 45f
        private const val PANEL_SLIDE = 24f
        private const val POPUP_ANIM_MS = 150f
        /** Far enough in the past that every panel (and the dim) has finished animating. */
        private const val PANELS_DONE_NANOS = 2_000_000_000L

        private var desc = Description("", 0f, 0f, HoverHandler(150))

        /** Sets the description without creating a new data class which isn't optimal */
        fun setDescription(text: String, x: Float, y: Float, hoverHandler: HoverHandler) {
            desc.text = text
            desc.x = x
            desc.y = y
            desc.hoverHandler = hoverHandler
        }

        data class Description(var text: String, var x: Float, var y: Float, var hoverHandler: HoverHandler) {

            fun render() {
                if (text.isEmpty() || hoverHandler.percent() < 100) return
                val area = NVGRenderer.wrappedTextBounds(text, 300f, 16f, NVGRenderer.defaultFont)
//#if CHEAT
                val w = area[2] - area[0] + 16f
                val h = area[3] - area[1] + 16f
                // Callers place it right of the panel; on the rightmost panel that runs off screen, so flip it left.
                val screenW = NVGRenderer.canvasWidth / clickGuiScale
                val screenH = NVGRenderer.canvasHeight / clickGuiScale
                val x = if (x + w > screenW - 4f) x - w - Panel.WIDTH - 20f else x
                val y = y.coerceAtMost(screenH - h - 4f)
                NVGRenderer.rect(x, y, w, h, Colors.gray38.rgba, 5f)
//#else
//$                NVGRenderer.rect(x, y, area[2] - area[0] + 16f, area[3] - area[1] + 16f, Colors.gray38.rgba, 5f)
//#endif
                NVGRenderer.hollowRect(
                    x,
                    y,
//#if CHEAT
                    w,
                    h,
//#else
//$                    area[2] - area[0] + 16f,
//$                    area[3] - area[1] + 16f,
//#endif
                    1.5f,
                    clickGUIColor.rgba,
                    5f
                )
                NVGRenderer.drawWrappedString(
                    text,
                    x + 8f,
                    y + 8f,
                    300f,
                    16f,
                    Colors.WHITE.rgba,
                    NVGRenderer.defaultFont
                )
            }
        }
    }
}
