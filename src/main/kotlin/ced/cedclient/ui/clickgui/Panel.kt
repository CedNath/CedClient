package ced.cedclient.ui.clickgui

import ced.cedclient.features.ModuleManager
import ced.cedclient.features.ModuleGroups
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import ced.cedclient.utils.Color.Companion.withAlpha
import ced.cedclient.utils.ui.isAreaHovered
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import kotlin.math.floor

/**
 * Renders one category's panel: header, drag/collapse handling, and its
 * stack of ModuleButtons. Ported from Odin's clickgui/Panel.kt.
 *
 * Odin's Panel takes a Category and looks its PanelSetting up from a
 * ClickGUIModule config map. CedClient doesn't have that config-module layer
 * (Phase 6 territory at best), so this Panel is handed its PanelSetting
 * directly by ClickGUI, which owns the list built/loaded via ConfigManager.
 */
class Panel(val panelSetting: PanelSetting) {

    private val category = panelSetting.category

    // Modules in heading order (ModuleGroups); headings[i] is the heading of moduleButtons[i].
    private val arranged = ModuleGroups.arrange(category, ModuleManager.modulesByCategory[category] ?: listOf())
    val moduleButtons = arranged.map { ModuleButton(it.second, this@Panel) }
    private val headings = arranged.map { it.first }

    // Compact layout (CompactLayout): only big categories split (Skyblock, Cheats...), one entry per heading
    // (null = the unlisted rest); a small one (Cosmetic, HUD) stays a single entry.
    val split = moduleButtons.size > SPLIT_OVER && headings.any { it != null }
    val groupNames: List<String?> = if (split) headings.distinct() else listOf(null)

    fun hasMatch(query: String) = moduleButtons.any { it.module.name.contains(query, true) }

    /** The modules of one group matching [query]; a search looks across the whole category. */
    fun modulesIn(heading: String?, query: String): List<ModuleButton> = moduleButtons.filterIndexed { i, b ->
        b.module.name.contains(query, true) && (!split || query.isNotEmpty() || headings[i] == heading)
    }

    private fun visible(b: ModuleButton) = b.module.name.contains(SearchBar.currentSearch, true)

    private val textWidth = NVGRenderer.textWidth(category.displayName, 25f, NVGRenderer.defaultFont)
    /** Height drawn last frame; ClickGUI scales / rotates the open animation around it. */
    var previousHeight = 0f
        private set
    private var scrollOffset = 0f
    var dragging = false
        private set
    private var deltaX = 0f
    private var deltaY = 0f

    fun draw(mouseX: Float, mouseY: Float) {
        if (dragging) {
            panelSetting.x = floor(deltaX + mouseX)
            panelSetting.y = floor(deltaY + mouseY)
        }

        NVGRenderer.dropShadow(
            panelSetting.x,
            panelSetting.y,
            WIDTH,
            (previousHeight + if (ClickGUI.roundedPanelBottom) 10f else 0f).coerceAtLeast(HEIGHT),
            10f,
            3f,
            5f
        )

        NVGRenderer.drawHalfRoundedRect(panelSetting.x, panelSetting.y, WIDTH, HEIGHT, Colors.gray26.rgba, 5f, true)
        NVGRenderer.text(
            category.displayName,
            panelSetting.x + WIDTH / 2f - textWidth / 2,
            panelSetting.y + HEIGHT / 2f - 12.5f,
            25f,
            Colors.WHITE.rgba,
            NVGRenderer.defaultFont
        )

        if (scrollOffset != 0f) NVGRenderer.pushScissor(
            panelSetting.x,
            panelSetting.y + HEIGHT,
            WIDTH,
            previousHeight - HEIGHT + 10f
        )

        var startY = scrollOffset + HEIGHT
        val lastShown = moduleButtons.lastOrNull { visible(it) }
        if (panelSetting.extended) {
            var lastHeading: String? = null
            for ((i, button) in moduleButtons.withIndex()) {
                if (!visible(button)) continue
                // Headings only without a search, so a filtered panel stays a plain list.
                val heading = headings[i]
                if (heading != null && heading != lastHeading && SearchBar.currentSearch.isEmpty())
                    startY += drawHeading(heading, panelSetting.x, startY + panelSetting.y)
                lastHeading = heading
                startY += button.draw(panelSetting.x, startY + panelSetting.y, button == lastShown)
            }
        }
        previousHeight = startY

        if (ClickGUI.roundedPanelBottom) {
            NVGRenderer.drawHalfRoundedRect(
                panelSetting.x,
                panelSetting.y + startY,
                WIDTH,
                10f,
                if (lastShown?.module?.enabled == true) ClickGUI.clickGUIColor.rgba else Colors.gray26.rgba,
                5f,
                false
            )
        }
        if (scrollOffset != 0f) NVGRenderer.popScissor()
    }

    /** Small sub-heading row ("Dungeons", "Hunting"...) in the accent colour; returns its height. */
    private fun drawHeading(text: String, x: Float, y: Float): Float {
        NVGRenderer.rect(x, y, WIDTH, HEADING_HEIGHT, Colors.gray26.rgba)
        val w = NVGRenderer.textWidth(text, 15f, NVGRenderer.defaultFont)
        NVGRenderer.text(text, x + WIDTH / 2f - w / 2f, y + HEADING_HEIGHT / 2f - 7.5f, 15f, ClickGUI.clickGUIColor.rgba, NVGRenderer.defaultFont)
        NVGRenderer.rect(x + 12f, y + HEADING_HEIGHT - 1f, WIDTH - 24f, 1f, ClickGUI.clickGUIColor.withAlpha(0.35f).rgba)
        return HEADING_HEIGHT
    }

    fun handleScroll(amount: Int): Boolean {
        if (!isMouseOverExtended) return false
        scrollOffset = (scrollOffset + amount).coerceIn((-previousHeight + scrollOffset + 72f).coerceAtMost(0f), 0f)
        return true
    }

    fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (isAreaHovered(panelSetting.x, panelSetting.y, WIDTH, HEIGHT, true)) {
            if (click.button() == 0) {
                deltaX = (panelSetting.x - mouseX)
                deltaY = (panelSetting.y - mouseY)
                dragging = true
                return true
            } else if (click.button() == 1) {
                panelSetting.extended = !panelSetting.extended
                return true
            }
        } else if (isMouseOverExtended) {
            return moduleButtons.reversed().any {
                if (!visible(it)) return@any false
                it.mouseClicked(mouseX, mouseY, click)
            }
        }
        return false
    }

    fun mouseReleased(click: MouseButtonEvent) {
        dragging = false

        if (panelSetting.extended)
            moduleButtons.reversed().forEach {
                if (!visible(it)) return@forEach
                it.mouseReleased(click)
            }
    }

    fun keyTyped(input: CharacterEvent): Boolean {
        if (!panelSetting.extended) return false

        return moduleButtons.reversed().any {
            if (!visible(it)) return@any false
            it.keyTyped(input)
        }
    }

    fun keyPressed(input: KeyEvent): Boolean {
        if (!panelSetting.extended) return false

        return moduleButtons.reversed().any {
            if (!visible(it)) return@any false
            it.keyPressed(input)
        }
    }

    private inline val isMouseOverExtended
        get() = panelSetting.extended && isAreaHovered(
            panelSetting.x,
            panelSetting.y,
            WIDTH,
            previousHeight.coerceAtLeast(HEIGHT),
            true
        )

    companion object {
        const val WIDTH = 320f
        const val HEIGHT = 46f
        const val HEADING_HEIGHT = 26f
        private const val SPLIT_OVER = 8
    }
}