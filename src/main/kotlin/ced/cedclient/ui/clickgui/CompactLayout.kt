package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.misc.Theme
import ced.cedclient.render.nvg.Gradient
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Color
import ced.cedclient.utils.Color.Companion.withAlpha
import ced.cedclient.utils.Colors
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

/**
 * Theme > Menu Layout "Compact": one window in the middle of the screen. A title and the search box on top, a row of
 * tabs (one per group of modules, wrapping onto more rows if there are many), then the modules of the chosen tab as a
 * list of cards with the settings of the module you right-clicked beside it. ClickGUI owns the screen (dim, popups,
 * animation timing) and hands this its panels (used only as the source of modules and their groups).
 *
 * Big categories split by their ModuleGroups headings into one tab each (Skyblock > Dungeons...); small ones
 * (see Panel.split) stay a single tab.
 */
class CompactLayout(
    private val panels: List<Panel>,
    /** ClickGUI's open animation: style, ms since it should start (negative = waiting), then the box x, y, w, h. */
    private val animated: (String, Float, Float, Float, Float, Float, () -> Unit) -> Unit,
) {
    private class Group(val panel: Int, val heading: String?, val name: String)
    private class Tab(val group: Int, val x: Float, val y: Float, val w: Float)

    private val groups = panels.flatMapIndexed { i, p ->
        p.groupNames.map { Group(i, it, it ?: if (p.split) "Other" else p.panelSetting.category.displayName) }
    }

    private var selGroup = lastGroup.coerceIn(0, groups.size - 1)
    private var listChangedAt = 0L
    private var modScroll = 0f

    // Where the window sits this frame; the click / scroll handlers use the same numbers.
    private var x0 = 0f
    private var y0 = 0f
    private var winH = 0f
    private var tabsH = 0f
    private var bodyY = 0f
    private var bodyH = 0f
    private val tabs = ArrayList<Tab>()
    private val x1 get() = x0 + PAD
    private val x2 get() = x1 + MOD_W + GAP

    init { selectedModule = null }

    private fun shown(): List<ModuleButton> {
        val g = groups[selGroup]
        return panels[g.panel].modulesIn(g.heading, SearchBar.currentSearch)
    }

    private fun selectGroup(i: Int) {
        if (i == selGroup) return
        selGroup = i
        lastGroup = i
        listChangedAt = System.nanoTime()
        modScroll = 0f
        selectedModule?.extended = false
        selectedModule = null
    }

    fun draw(cw: Float, ch: Float, elapsed: Float, style: String, mx: Float, my: Float) {
        // A search jumps to the first tab that has a match.
        val query = SearchBar.currentSearch
        if (query.isNotEmpty() && !panels[groups[selGroup].panel].hasMatch(query)) {
            val i = groups.indexOfFirst { panels[it.panel].hasMatch(query) }
            if (i >= 0) selectGroup(i)
        }

        val speed = Theme.animationSpeed.value.toFloat()
        winH = (ch - 90f).coerceIn(380f, 680f)
        x0 = ((cw - WIN_W) / 2f).coerceAtLeast(8f)
        y0 = ((ch - winH) / 2f).coerceAtLeast(8f)
        layoutTabs()
        bodyY = y0 + HEAD_H + tabsH
        bodyH = y0 + winH - PAD - bodyY

        animated(style, elapsed, x0, y0, WIN_W, winH) { drawWindow(mx, my, speed) }
    }

    /** Tabs in reading order, wrapping to a new row when one would run past the window's edge. */
    private fun layoutTabs() {
        tabs.clear()
        val left = x0 + PAD
        val right = x0 + WIN_W - PAD
        var tx = left
        var ty = y0 + HEAD_H
        groups.forEachIndexed { i, g ->
            val w = NVGRenderer.textWidth(g.name, TAB_TEXT, NVGRenderer.defaultFont) + 2 * TAB_PAD
            if (tx + w > right && tx > left) { tx = left; ty += TAB_H + TAB_GAP }
            tabs += Tab(i, tx, ty, w)
            tx += w + TAB_GAP
        }
        tabsH = ty + TAB_H + PAD - (y0 + HEAD_H)
    }

    private fun frame(x: Float, y: Float, w: Float, h: Float) {
        NVGRenderer.dropShadow(x, y, w, h, 12f, 2f, 12f)
        val accent = ClickGUI.clickGUIColor
        when (Theme.menuStyle.value) {
            // Frosted glass: the blurred game shows through a sheen (white, or the accent for Tinted Glass), with a bright rim and a top highlight.
            "Glass", "Tinted Glass" -> {
                val tinted = Theme.menuStyle.value == "Tinted Glass"
                NVGRenderer.rect(x, y, w, h, Colors.BLACK.withAlpha(0.22f).rgba, 14f)
                NVGRenderer.gradientRect(
                    x, y, w, h,
                    (if (tinted) accent.withAlpha(0.5f) else Colors.WHITE.withAlpha(0.20f)).rgba,
                    (if (tinted) accent.withAlpha(0.1f) else Colors.WHITE.withAlpha(0.05f)).rgba,
                    Gradient.TopToBottom, 14f
                )
                NVGRenderer.hollowRect(x, y, w, h, 1.5f, Colors.WHITE.withAlpha(0.38f).rgba, 14f)
                NVGRenderer.rect(x + 14f, y + 1f, w - 28f, 1.5f, Colors.WHITE.withAlpha(0.6f).rgba, 1f)
            }
            // Neon: a near-black panel ringed by a glowing accent outline.
            "Neon" -> {
                NVGRenderer.rect(x, y, w, h, tint(NEON_BG), 8f)
                NVGRenderer.hollowRect(x - 1f, y - 1f, w + 2f, h + 2f, 6f, accent.withAlpha(0.22f).rgba, 9f)
                NVGRenderer.hollowRect(x, y, w, h, 2f, accent.rgba, 8f)
            }
            // Gradient: the accent fades into the panel colour from the top.
            "Gradient" -> {
                NVGRenderer.gradientRect(x, y, w, h, accent.withAlpha(0.55f * Theme.menuOpacity.value.toFloat() / 100f).rgba, BG.withAlpha(Theme.menuOpacity.value.toFloat() / 100f).rgba, Gradient.TopToBottom, 8f)
                NVGRenderer.hollowRect(x, y, w, h, 1.5f, EDGE.rgba, 8f)
            }
            // Minimal: a faint dark wash and a hairline, so the game stays visible.
            "Minimal" -> {
                NVGRenderer.rect(x, y, w, h, Colors.BLACK.withAlpha(0.40f * Theme.menuOpacity.value.toFloat() / 100f).rgba, 6f)
                NVGRenderer.hollowRect(x, y, w, h, 1f, EDGE.withAlpha(0.8f).rgba, 6f)
            }
            else -> {
                NVGRenderer.rect(x, y, w, h, tint(BG), 8f)
                NVGRenderer.hollowRect(x, y, w, h, 1.5f, EDGE.rgba, 8f)
            }
        }
    }

    private fun centered(text: String, cx: Float, y: Float, size: Float, color: Int) {
        val w = NVGRenderer.textWidth(text, size, NVGRenderer.defaultFont)
        NVGRenderer.text(text, cx - w / 2f, y, size, color, NVGRenderer.defaultFont)
    }

    private fun drawWindow(mx: Float, my: Float, speed: Float) {
        frame(x0, y0, WIN_W, winH)
        drawHeader(mx, my)
        drawTabs(mx, my)
        drawModules(speed)
        drawSettings(speed)
    }

    /** The name on the left (with an accent underline) and the search box on the right. */
    private fun drawHeader(mx: Float, my: Float) {
        val nameW = NVGRenderer.textWidth("CedClient", 27f, NVGRenderer.defaultFont)
        val nx = x0 + PAD + 8f
        NVGRenderer.text("CedClient", nx, y0 + HEAD_H / 2f - 15f, 27f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
        NVGRenderer.rect(nx, y0 + HEAD_H - 9f, nameW, 3f, ClickGUI.clickGUIColor.rgba, 2f)
        SearchBar.draw(x0 + WIN_W - PAD - 350f, y0 + (HEAD_H - 40f) / 2f, mx, my)
    }

    private fun drawTabs(mx: Float, my: Float) {
        val accent = ClickGUI.clickGUIColor
        for (t in tabs) {
            val selected = t.group == selGroup
            val hovered = mx in t.x..(t.x + t.w) && my in t.y..(t.y + TAB_H)
            when {
                selected -> NVGRenderer.rect(t.x, t.y, t.w, TAB_H, accent.rgba, 8f)
                hovered -> NVGRenderer.rect(t.x, t.y, t.w, TAB_H, tint(CARD_HOVER), 8f)
                else -> NVGRenderer.rect(t.x, t.y, t.w, TAB_H, tint(CARD), 8f)
            }
            NVGRenderer.text(
                groups[t.group].name, t.x + TAB_PAD, t.y + TAB_H / 2f - TAB_TEXT / 2f, TAB_TEXT,
                if (selected) Colors.WHITE.rgba else TEXT.rgba, NVGRenderer.defaultFont
            )
        }
    }

    /** Runs [draw] fading in (and sliding [dx], [dy] px into place) over SWITCH_MS from [sinceNs]; 0 = already settled. */
    private fun fadeIn(sinceNs: Long, speed: Float, dx: Float, dy: Float, delayMs: Float = 0f, draw: () -> Unit) {
        val t = if (sinceNs == 0L) SWITCH_MS else (System.nanoTime() - sinceNs) / 1_000_000f * speed - delayMs
        val p = (t / SWITCH_MS).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) * (1f - it) }
        if (p <= 0f) return
        NVGRenderer.push()
        // Settled content leaves the alpha alone, so the menu's own open animation (a parent) still fades it.
        if (p < 1f) {
            NVGRenderer.globalAlpha(p)
            NVGRenderer.translate((1f - p) * dx, (1f - p) * dy)
        }
        draw()
        NVGRenderer.pop()
    }

    /** A recessed backdrop for the module list and the settings, so the cards on it stand out. */
    private fun well(x: Float, w: Float) = NVGRenderer.rect(x, bodyY, w, bodyH, Colors.BLACK.withAlpha(0.20f).rgba, 8f)

    private fun drawModules(speed: Float) {
        well(x1, MOD_W)
        val list = shown()
        fadeIn(listChangedAt, speed, 0f, 6f) {
            NVGRenderer.text(groups[selGroup].name, x1 + PAD + 8f, bodyY + 11f, 20f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
            val count = "${list.size} modules"
            val cw = NVGRenderer.textWidth(count, 15f, NVGRenderer.defaultFont)
            NVGRenderer.text(count, x1 + MOD_W - PAD - 8f - cw, bodyY + 14f, 15f, MUTED.rgba, NVGRenderer.defaultFont)
        }

        val vpY = bodyY + LIST_HEAD
        val vpH = bodyH - LIST_HEAD - PAD
        val contentH = list.size * (CARD_H + CARD_GAP)
        modScroll = modScroll.coerceIn((vpH - contentH).coerceAtMost(0f), 0f)
        NVGRenderer.pushScissor(x1, vpY, MOD_W, vpH)
        if (list.isEmpty()) centered("No modules match", x1 + MOD_W / 2f, vpY + 30f, 17f, MUTED.rgba)
        // Cards ripple in one after another (the first few; the rest come with them).
        list.forEachIndexed { i, btn ->
            fadeIn(listChangedAt, speed, 0f, 14f, minOf(i, 8) * 22f) {
                btn.drawCard(x1 + PAD, vpY + modScroll + i * (CARD_H + CARD_GAP), MOD_W - 2 * PAD, CARD_H)
            }
        }
        NVGRenderer.popScissor()
    }

    private fun drawSettings(speed: Float) {
        well(x2, SET_W)
        val btn = selectedModule
        if (btn == null) {
            centered("Right-click a module", x2 + SET_W / 2f, bodyY + bodyH / 2f - 22f, 18f, MUTED.rgba)
            centered("to see its settings", x2 + SET_W / 2f, bodyY + bodyH / 2f + 2f, 18f, MUTED.rgba)
            return
        }
        val vpY = bodyY + SET_HEAD
        val vpH = bodyH - SET_HEAD - PAD
        val contentH = btn.compactHeight()
        settingsScroll = settingsScroll.coerceIn((vpH - contentH).coerceAtMost(0f), 0f)
        // Picking a module slides its settings in from the right.
        fadeIn(selectedAt, speed, 16f, 0f) {
            NVGRenderer.text(btn.module.name, x2 + PAD + 8f, bodyY + 11f, 20f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
            NVGRenderer.pushScissor(x2 + PAD, vpY, SET_W - 2 * PAD, vpH)
            btn.drawSettings(x2 + PAD, vpY + settingsScroll)
            NVGRenderer.popScissor()
        }
    }

    private fun tabAt(mx: Float, my: Float): Int? =
        tabs.firstOrNull { mx in it.x..(it.x + it.w) && my in it.y..(it.y + TAB_H) }?.group

    private fun inModules(mx: Float, my: Float) = mx in x1..(x1 + MOD_W) && my in (bodyY + LIST_HEAD)..(bodyY + bodyH - PAD)
    private fun inSettings(mx: Float, my: Float) = mx in x2..(x2 + SET_W) && my in (bodyY + SET_HEAD)..(bodyY + bodyH - PAD)

    fun mouseClicked(mx: Float, my: Float, click: MouseButtonEvent) {
        tabAt(mx, my)?.let { selectGroup(it); return }
        if (inModules(mx, my)) shown().asReversed().any { it.mouseClicked(mx, my, click) }
        else if (inSettings(mx, my)) selectedModule?.clickSettings(mx, my, click)
    }

    fun mouseScrolled(mx: Float, my: Float, amount: Int) {
        when {
            mx in x1..(x1 + MOD_W) && my in bodyY..(bodyY + bodyH) -> modScroll += amount * 2
            mx in x2..(x2 + SET_W) && my in bodyY..(bodyY + bodyH) -> settingsScroll += amount
        }
    }

    fun mouseReleased(click: MouseButtonEvent) { selectedModule?.mouseReleased(click) }
    fun keyTyped(input: CharacterEvent): Boolean = selectedModule?.keyTyped(input) == true
    fun keyPressed(input: KeyEvent): Boolean = selectedModule?.keyPressed(input) == true

    companion object {
        /** Theme > Menu Opacity: the panel, card and settings backgrounds get this alpha; text, bars and outlines stay solid. */
        fun tint(c: Color): Int {
            val hover = c === CARD_HOVER
            val card = hover || c === CARD
            // Cards and the settings backdrop follow the style: see-through washes where the panel shows through, solid colours otherwise.
            return when (if (ClickGUI.compact) Theme.menuStyle.value else "Solid") {
                "Glass", "Tinted Glass" -> Colors.WHITE.withAlpha(if (hover) 0.14f else if (card) 0.07f else 0.05f).rgba
                "Gradient" -> Colors.BLACK.withAlpha(if (hover) 0.42f else if (card) 0.30f else 0.28f).rgba
                "Minimal" -> Colors.WHITE.withAlpha(if (hover) 0.10f else if (card) 0.05f else 0.03f).rgba
                else -> c.withAlpha(Theme.menuOpacity.value.toFloat() / 100f).rgba
            }
        }

        /** Theme > Menu Style "Glass" / "Tinted Glass": frosted panels (ignore Menu Opacity, always blur the game behind). */
        val glass: Boolean get() = ClickGUI.compact && (Theme.menuStyle.value == "Glass" || Theme.menuStyle.value == "Tinted Glass")

        val BG = Color(18, 18, 26)
        val NEON_BG = Color(8, 8, 14)
        val CARD = Color(30, 30, 42)
        val CARD_HOVER = Color(42, 42, 58)
        val EDGE = Color(52, 52, 72)
        val MUTED = Color(150, 150, 172)
        val TEXT = Color(214, 214, 228)
        val PILL_OFF = Color(44, 44, 60)

        // The settings cards are a fixed width (ModuleButton), so the settings column is too.
        private const val MOD_W = 380f
        private const val SET_W = 356f
        private const val GAP = 12f
        private const val PAD = 10f
        private const val WIN_W = PAD + MOD_W + GAP + SET_W + PAD
        private const val HEAD_H = 56f
        private const val TAB_H = 32f
        private const val TAB_GAP = 6f
        private const val TAB_PAD = 14f
        private const val TAB_TEXT = 17f
        private const val LIST_HEAD = 42f
        private const val SET_HEAD = 42f
        private const val CARD_H = 54f
        private const val CARD_GAP = 8f
        /** How long a tab or module change takes to fade and slide in (ms at Animation Speed 1). */
        private const val SWITCH_MS = 240f

        private var lastGroup = 0
        var selectedModule: ModuleButton? = null
            private set
        private var selectedAt = 0L
        private var settingsScroll = 0f

        /** Right-clicking a module opens its settings in the right panel (a second right-click closes them). */
        fun selectModule(button: ModuleButton) {
            val old = selectedModule
            old?.extended = false
            if (old === button) { selectedModule = null; return }
            button.extended = true
            selectedModule = button
            selectedAt = System.nanoTime()
            settingsScroll = 0f
        }
    }
}
