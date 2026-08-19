package ced.cedclient.ui.clickgui

import ced.cedclient.config.ConfigManager
import ced.cedclient.features.Category
import ced.cedclient.ui.animations.EaseOutAnimation
import ced.cedclient.ui.nvg.NVGRenderer
import ced.cedclient.ui.nvg.NVGSpecialRenderer
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
        Category.entries.map { PanelSetting(it) }.also { ConfigManager.load(it) }

    private val panels: List<Panel> = panelSettings.map { Panel(it) }

    private var openAnim = EaseOutAnimation(500)

    private var activePopup: Popup? = null

    /** Live panel layout state, exposed for ResetPanels to mutate while this screen is open. */
    fun getPanels(): List<PanelSetting> = panelSettings

    /** Opens [popup] as a modal on top of this ClickGUI, replacing whichever popup (if any) is already open. */
    fun openPopup(popup: Popup) {
        activePopup = popup
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

            NVGRenderer.scale(clickGuiScale, clickGuiScale)

            // canvasWidth/canvasHeight (NOT context.guiWidth()/guiHeight()) is the actual
            // coordinate space everything in this block draws into -- see NVGRenderer's
            // doc comment on canvasWidth for why those two aren't the same thing.
            val canvasWidth = NVGRenderer.canvasWidth / clickGuiScale
            val canvasHeight = NVGRenderer.canvasHeight / clickGuiScale

            // Bottom-center, a fixed 50px gap above the screen edge.
            SearchBar.draw(
                canvasWidth / 2f - 175f,
                canvasHeight - 90f,
                scaledMouseX,
                scaledMouseY
            )

            if (openAnim.isAnimating()) {
                val scale = openAnim.get(0f, 1f)

                val centerX = canvasWidth
                val centerY = canvasHeight
                NVGRenderer.translate(centerX, centerY)
                NVGRenderer.scale(scale, scale)
                NVGRenderer.translate(-centerX, -centerY)
            }

            val draggedPanel = panels.firstOrNull { it.dragging }
            for (panel in panels) {
                if (panel != draggedPanel) panel.draw(scaledMouseX, scaledMouseY)
            }

            draggedPanel?.draw(scaledMouseX, scaledMouseY)

            desc.render()

            activePopup?.let { popup ->
                popup.x = (canvasWidth - popup.width) / 2f
                popup.y = (canvasHeight - popup.height) / 2f

                NVGRenderer.rect(0f, 0f, canvasWidth, canvasHeight, Colors.BLACK.withAlpha(0.55f).rgba)
                popup.draw(scaledMouseX, scaledMouseY)
            }
        })
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
            if (keyEvent.key == GLFW.GLFW_KEY_ESCAPE) {
                closePopup()
                return true
            }
            return popup.keyPressed(keyEvent)
        }

        SearchBar.keyPressed(keyEvent)
        for (i in panels.size - 1 downTo 0) {
            if (panels[i].keyPressed(keyEvent)) return true
        }
        return super.keyPressed(keyEvent)
    }

    override fun init() {
        openAnim.start()
        super.init()
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
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false

    companion object {

        const val roundedPanelBottom = true
        val clickGUIColor: Color = Color(110, 50, 200)

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
                NVGRenderer.rect(x, y, area[2] - area[0] + 16f, area[3] - area[1] + 16f, Colors.gray38.rgba, 5f)
                NVGRenderer.hollowRect(
                    x,
                    y,
                    area[2] - area[0] + 16f,
                    area[3] - area[1] + 16f,
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