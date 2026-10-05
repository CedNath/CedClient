package ced.cedclient.ui.pv

import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.ui.animations.EaseOutAnimation
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.HoverHandler
import ced.cedclient.ui.pv.tabs.ComingSoonTab
import ced.cedclient.ui.pv.tabs.CollectionsTab
import ced.cedclient.ui.pv.tabs.HomeTab
import ced.cedclient.utils.Color.Companion.withAlpha
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.clickGuiScale
import ced.cedclient.utils.ui.isAreaHovered
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import ced.cedclient.utils.ui.mouseX as cedMouseX
import ced.cedclient.utils.ui.mouseY as cedMouseY

/**
 * The Profile Viewer screen, opened by `/pv [username]`. Same shape as
 * skyblock-pv: a row of tabs across the top, a left sidebar for whichever
 * tab has more than one [PvCategory], content underneath.
 *
 * Built on the exact same plumbing ClickGUI already uses -- Screen wrapped
 * in NVGSpecialRenderer.draw, clickGuiScale, cedMouseX/cedMouseY, HoverHandler,
 * EaseOutAnimation -- on purpose: it means this screen inherits ClickGUI's
 * already-solved GUI-Scale/DPI coordinate handling for free instead of
 * re-deriving it. See NVGRenderer.canvasWidth's doc comment if you need to
 * know exactly why cedMouseX/scale, not context.guiWidth(), is the space
 * everything here draws and hit-tests in.
 *
 * ProfileViewer.lastPlayerJson/lastSkyblockJson/loading/lastError are
 * @Volatile fields on a companion object that the fetch chain in
 * ProfileViewer.kt writes to off-thread -- tabs just read them fresh every
 * draw call, so this screen doesn't need its own "data arrived" callback or
 * refresh mechanism; opening it before the fetch completes and watching it
 * fill in once Hypixel answers is expected, not a bug.
 *
 * The panel is capped at [MAX_PANEL_WIDTH]x[MAX_PANEL_HEIGHT] and centered
 * rather than just "margin from every edge" -- on a large/ultrawide
 * display, margin-from-edge alone stretched this to nearly the full
 * monitor, which read as a wall of mostly-empty dead space rather than a
 * window. [panelGeometry] is the one place that math happens; every method
 * below (draw, click, scroll) calls it instead of recomputing its own copy,
 * so hit-testing can't drift out of sync with what's actually drawn.
 */
class PvScreen : Screen(Component.literal("Profile Viewer")) {

    // Full lineup taken from skyblock-pv's README ("All Tabs" list) -- Home
    // and Collections are actually built; the rest are ComingSoonTab
    // placeholders so the tab bar has its final shape from day one instead
    // of growing tab-by-tab. Swap a placeholder out for the real
    // implementation as each one gets built.
    private val tabs: List<PvTab> = listOf(
        HomeTab,
        CollectionsTab,
        ComingSoonTab("Combat"),
        ComingSoonTab("Inventory"),
        ComingSoonTab("Mining"),
        ComingSoonTab("Fishing"),
        ComingSoonTab("Pets"),
        ComingSoonTab("Farming"),
        ComingSoonTab("Museum"),
        ComingSoonTab("Chocolate Factory"),
        ComingSoonTab("Rift")
    )

    private var selectedTab: PvTab = tabs.first()
    private var selectedCategory: PvCategory? = selectedTab.categories.firstOrNull()

    private val tabHover = tabs.associateWith { HoverHandler(150) }
    private val categoryHover = HashMap<String, HoverHandler>()

    private val openAnim = EaseOutAnimation(300)

    private fun selectTab(tab: PvTab) {
        if (tab == selectedTab) return
        selectedTab = tab
        selectedCategory = tab.categories.firstOrNull()
    }

    override fun init() {
        openAnim.start()
        super.init()
    }

    override fun isPauseScreen(): Boolean = false

    /**
     * (panelX, panelY, panelW, panelH) in the same scaled-canvas space
     * everything else in this class works in -- capped at
     * MAX_PANEL_WIDTH/MAX_PANEL_HEIGHT and centered, falling back to
     * margin-from-edge only on a window/canvas small enough that the cap
     * wouldn't fit anyway.
     */
    private fun panelGeometry(): PanelGeometry {
        val canvasWidth = NVGRenderer.canvasWidth / clickGuiScale
        val canvasHeight = NVGRenderer.canvasHeight / clickGuiScale

        val panelW = (canvasWidth - MARGIN * 2f).coerceAtMost(MAX_PANEL_WIDTH)
        val panelH = (canvasHeight - MARGIN * 2f).coerceAtMost(MAX_PANEL_HEIGHT)
        val panelX = (canvasWidth - panelW) / 2f
        val panelY = (canvasHeight - panelH) / 2f

        return PanelGeometry(panelX, panelY, panelW, panelH)
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        NVGSpecialRenderer.draw(context, 0, 0, context.guiWidth(), context.guiHeight(), renderContent = {
            val scaledMouseX = cedMouseX / clickGuiScale
            val scaledMouseY = cedMouseY / clickGuiScale

            NVGRenderer.scale(clickGuiScale, clickGuiScale)

            // canvasWidth/canvasHeight, not context.guiWidth()/guiHeight() -- see
            // NVGRenderer's doc comment on canvasWidth for why those differ.
            val canvasWidth = NVGRenderer.canvasWidth / clickGuiScale
            val canvasHeight = NVGRenderer.canvasHeight / clickGuiScale

            val (panelX, panelY, panelW, panelH) = panelGeometry()

            NVGRenderer.rect(0f, 0f, canvasWidth, canvasHeight, Colors.BLACK.withAlpha(0.55f).rgba)

            if (openAnim.isAnimating()) {
                val scale = openAnim.get(0.94f, 1f)
                val alpha = openAnim.get(0f, 1f)
                val cx = panelX + panelW / 2f
                val cy = panelY + panelH / 2f
                NVGRenderer.translate(cx, cy)
                NVGRenderer.scale(scale, scale)
                NVGRenderer.translate(-cx, -cy)
                NVGRenderer.globalAlpha(alpha)
            }

            NVGRenderer.rect(panelX, panelY, panelW, panelH, Colors.gray26.withAlpha(0.95f).rgba, 8f)

            drawTabBar(panelX, panelY, panelW, scaledMouseX, scaledMouseY)

            val contentY = panelY + TAB_BAR_HEIGHT
            val contentH = panelH - TAB_BAR_HEIGHT
            val categories = selectedTab.categories

            if (categories.size > 1) {
                drawSidebar(panelX, contentY, contentH, scaledMouseX, scaledMouseY, categories)
                NVGRenderer.pushScissor(panelX + SIDEBAR_WIDTH, contentY, panelW - SIDEBAR_WIDTH, contentH)
                selectedTab.draw(
                    panelX + SIDEBAR_WIDTH, contentY, panelW - SIDEBAR_WIDTH, contentH,
                    scaledMouseX, scaledMouseY, selectedCategory
                )
                NVGRenderer.popScissor()
            } else {
                NVGRenderer.pushScissor(panelX, contentY, panelW, contentH)
                selectedTab.draw(panelX, contentY, panelW, contentH, scaledMouseX, scaledMouseY, selectedCategory)
                NVGRenderer.popScissor()
            }

            NVGRenderer.globalAlpha(1f)
        })
        super.extractRenderState(context, mouseX, mouseY, deltaTicks)
    }

    private fun drawTabBar(x: Float, y: Float, w: Float, mouseX: Float, mouseY: Float) {
        NVGRenderer.rect(x, y, w, TAB_BAR_HEIGHT, Colors.gray38.rgba)

        var tabX = x + 8f
        for (tab in tabs) {
            val labelW = NVGRenderer.textWidth(tab.displayName, 16f, NVGRenderer.defaultFont)
            val tabW = labelW + 24f

            val hover = tabHover.getValue(tab)
            hover.handle(tabX, y, tabW, TAB_BAR_HEIGHT)

            val selected = tab == selectedTab
            when {
                selected -> NVGRenderer.rect(tabX, y, tabW, TAB_BAR_HEIGHT, ClickGUI.clickGUIColor.withAlpha(0.85f).rgba)
                hover.percent() > 0f -> NVGRenderer.rect(
                    tabX, y, tabW, TAB_BAR_HEIGHT,
                    Colors.WHITE.withAlpha(hover.percent() / 100f * 0.08f).rgba
                )
            }

            NVGRenderer.text(
                tab.displayName, tabX + 12f, y + TAB_BAR_HEIGHT / 2f - 8f, 16f, Colors.WHITE.rgba, NVGRenderer.defaultFont
            )

            tabBounds[tab] = TabBounds(tabX, tabW)
            tabX += tabW + 4f
        }
    }

    private fun drawSidebar(x: Float, y: Float, h: Float, mouseX: Float, mouseY: Float, categories: List<PvCategory>) {
        NVGRenderer.rect(x, y, SIDEBAR_WIDTH, h, Colors.gray38.withAlpha(0.6f).rgba)

        var entryY = y + 6f
        for (category in categories) {
            val hover = categoryHover.getOrPut(category.id) { HoverHandler(150) }
            hover.handle(x + 6f, entryY, SIDEBAR_WIDTH - 12f, CATEGORY_HEIGHT)

            val selected = category == selectedCategory
            when {
                selected -> NVGRenderer.rect(
                    x + 6f, entryY, SIDEBAR_WIDTH - 12f, CATEGORY_HEIGHT, ClickGUI.clickGUIColor.withAlpha(0.75f).rgba, 5f
                )
                hover.percent() > 0f -> NVGRenderer.rect(
                    x + 6f, entryY, SIDEBAR_WIDTH - 12f, CATEGORY_HEIGHT,
                    Colors.WHITE.withAlpha(hover.percent() / 100f * 0.08f).rgba, 5f
                )
            }

            NVGRenderer.text(
                category.displayName, x + 16f, entryY + CATEGORY_HEIGHT / 2f - 7f, 14f, Colors.WHITE.rgba, NVGRenderer.defaultFont
            )

            categoryBounds[category.id] = entryY
            entryY += CATEGORY_HEIGHT + 4f
        }
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val scaledMouseX = cedMouseX / clickGuiScale
        val scaledMouseY = cedMouseY / clickGuiScale

        val (panelX, panelY, _, _) = panelGeometry()

        for ((tab, bounds) in tabBounds) {
            if (isAreaHovered(bounds.x, panelY, bounds.width, TAB_BAR_HEIGHT, scaled = true)) {
                selectTab(tab)
                return true
            }
        }

        val categories = selectedTab.categories
        if (categories.size > 1) {
            val sidebarX = panelX + 6f
            for (category in categories) {
                val entryY = categoryBounds[category.id] ?: continue
                if (isAreaHovered(sidebarX, entryY, SIDEBAR_WIDTH - 12f, CATEGORY_HEIGHT, scaled = true)) {
                    selectedCategory = category
                    return true
                }
            }
        }

        if (selectedTab.mouseClicked(scaledMouseX, scaledMouseY, mouseButtonEvent)) return true
        return super.mouseClicked(mouseButtonEvent, bl)
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        if (selectedTab.mouseReleased(mouseButtonEvent)) return true
        return super.mouseReleased(mouseButtonEvent)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double
    ): Boolean {
        val scaledMouseX = cedMouseX / clickGuiScale
        val scaledMouseY = cedMouseY / clickGuiScale
        val actualAmount = (kotlin.math.sign(verticalAmount) * 16).toInt()

        if (selectedTab.mouseScrolled(scaledMouseX, scaledMouseY, actualAmount)) return true
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        if (selectedTab.keyPressed(keyEvent)) return true
        return super.keyPressed(keyEvent)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        if (selectedTab.charTyped(characterEvent)) return true
        return super.charTyped(characterEvent)
    }

    private data class TabBounds(val x: Float, val width: Float)
    private data class PanelGeometry(val x: Float, val y: Float, val w: Float, val h: Float)

    private val tabBounds = HashMap<PvTab, TabBounds>()
    private val categoryBounds = HashMap<String, Float>()

    companion object {
        private const val MARGIN = 30f
        private const val MAX_PANEL_WIDTH = 1100f
        private const val MAX_PANEL_HEIGHT = 750f
        private const val TAB_BAR_HEIGHT = 32f
        private const val SIDEBAR_WIDTH = 140f
        private const val CATEGORY_HEIGHT = 26f
    }
}