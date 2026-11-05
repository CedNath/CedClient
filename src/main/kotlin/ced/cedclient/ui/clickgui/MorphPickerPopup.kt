package ced.cedclient.ui.clickgui

import ced.cedclient.render.nvg.Gradient
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.state.MorphSync
import ced.cedclient.utils.Color
import ced.cedclient.utils.Colors
import ced.cedclient.utils.Keys
import ced.cedclient.utils.ui.TextInputHandler
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.MobCategory

/**
 * Opened by the Morph module's "Choose Mob". Left: searchable list of every mob. Right: a card with a live 3D preview that
 * turns to follow the mouse, the mob's name, kind and size, and the Apply / Remove buttons. Picking a row only previews;
 * Apply sends it (MorphSync handles the cooldown: during it you still see it on yourself, others get the latest pick later).
 */
class MorphPickerPopup(private val onChosen: (String) -> Unit = {}) : Popup("Choose Morph", 900f, 740f) {

    private class Entry(val id: String, val label: String, val kind: String)

    private var query = ""
    private var lastQuery = ""
    private var filtered: List<Entry> = ALL
    private var scrollOffset = 0f
    private var selected: Entry? = MorphSync.current()?.let { cur -> ALL.firstOrNull { it.id == cur } }
        ?: ALL.firstOrNull { it.id == "minecraft:zombie" } ?: ALL.firstOrNull()
    private var status = ""
    private var statusGood = true
    private var mx = 0f
    private var my = 0f

    private var draggingScrollbar = false
    private var dragStartMouseY = 0f
    private var dragStartScrollOffset = 0f

    private val input = TextInputHandler(textProvider = { query }, textSetter = { query = it.take(32) })

    init { input.focus() }

    private val listX get() = x + 12f
    private val inputY get() = y + 38f
    private val listY get() = y + 72f
    private val listHeight get() = height - 84f
    private val cardX get() = x + 12f + LIST_W + 12f
    private val cardY get() = y + 38f
    private val cardW get() = width - (cardX - x) - 12f
    private val cardH get() = height - 50f
    private val applyY get() = cardY + cardH - 92f
    private val removeY get() = cardY + cardH - 46f
    // The 3D preview box inside the card.
    private val pvX get() = cardX + 12f
    private val pvY get() = cardY + 92f
    private val pvW get() = cardW - 24f
    private val pvH get() = applyY - 26f - pvY

    override fun drawContent(mouseX: Float, mouseY: Float) {
        mx = mouseX; my = mouseY
        val accent = ClickGUI.clickGUIColor.rgba
        if (query != lastQuery) {
            lastQuery = query
            val q = query.trim().lowercase().replace(' ', '_')
            filtered = if (q.isEmpty()) ALL else ALL.filter { it.id.contains(q) || it.label.lowercase().replace(' ', '_').contains(q) }
            scrollOffset = 0f
        }

        // ---- list side
        NVGRenderer.rect(listX, inputY, LIST_W, 26f, Colors.gray38.rgba, 5f)
        if (query.isEmpty()) NVGRenderer.text("Search ${ALL.size} mobs...", listX + 8f, inputY + 6f, 15f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)
        input.x = listX; input.y = inputY + 1f; input.width = LIST_W; input.height = 24f
        input.draw(mouseX, mouseY)

        val contentHeight = filtered.size * ROW
        if (draggingScrollbar) {
            val travel = listHeight - thumbHeight(contentHeight)
            if (travel > 0f) scrollOffset = dragStartScrollOffset + (mouseY - dragStartMouseY) * (-(contentHeight - listHeight) / travel)
        }
        scrollOffset = scrollOffset.coerceIn((-contentHeight + listHeight).coerceAtMost(0f), 0f)

        NVGRenderer.pushScissor(listX, listY, LIST_W, listHeight)
        val first = (-scrollOffset / ROW).toInt().coerceAtLeast(0)
        val last = (first + (listHeight / ROW).toInt() + 2).coerceAtMost(filtered.size)
        for (i in first until last) {
            val e = filtered[i]
            val rowY = listY + scrollOffset + i * ROW
            val hovered = mouseY in listY..(listY + listHeight) && isAreaHovered(listX, rowY, LIST_W - 10f, ROW - 2f, mouseX, mouseY)
            val isSel = e === selected
            if (isSel) {
                NVGRenderer.rect(listX, rowY, LIST_W - 10f, ROW - 2f, Color(accent, 0.28f).rgba, 5f)
                NVGRenderer.rect(listX, rowY + 3f, 3f, ROW - 8f, accent, 1.5f)
            } else if (hovered) NVGRenderer.rect(listX, rowY, LIST_W - 10f, ROW - 2f, Colors.gray38.rgba, 5f)
            NVGRenderer.text(e.label, listX + 12f, rowY + 5f, 15f, if (isSel || hovered) Colors.WHITE.rgba else 0xFFC8C8C8.toInt(), NVGRenderer.defaultFont)
            val kw = NVGRenderer.textWidth(e.kind, 11f, NVGRenderer.defaultFont)
            NVGRenderer.text(e.kind, listX + LIST_W - 18f - kw, rowY + 7f, 11f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)
        }
        if (filtered.isEmpty()) NVGRenderer.text("No mob found", listX + 8f, listY + 8f, 15f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)
        NVGRenderer.popScissor()

        if (contentHeight > listHeight) {
            val sx = listX + LIST_W - 6f
            NVGRenderer.rect(sx, listY, 5f, listHeight, Colors.gray26.rgba, 2.5f)
            val hovered = isAreaHovered(sx - 4f, thumbY(contentHeight), 13f, thumbHeight(contentHeight), mouseX, mouseY)
            NVGRenderer.rect(sx, thumbY(contentHeight), 5f, thumbHeight(contentHeight), (if (draggingScrollbar || hovered) ClickGUI.clickGUIColor else Colors.gray38).rgba, 2.5f)
        }

        // ---- card
        NVGRenderer.dropShadow(cardX, cardY, cardW, cardH, 12f, 2f, 8f)
        NVGRenderer.gradientRect(cardX, cardY, cardW, cardH, 0xFF1C1C22.toInt(), 0xFF101014.toInt(), Gradient.TopToBottom, 8f)
        NVGRenderer.gradientRect(cardX, cardY, cardW, 120f, Color(accent, 0.32f).rgba, Color(accent, 0f).rgba, Gradient.TopToBottom, 8f)
        NVGRenderer.hollowRect(cardX, cardY, cardW, cardH, 1.2f, Color(accent, 0.65f).rgba, 8f)

        val sel = selected
        if (sel == null) {
            NVGRenderer.text("Pick a mob", cardX + 16f, cardY + 20f, 18f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)
        } else {
            val ent = MorphSync.previewEntity(sel.id)
            val kw = NVGRenderer.textWidth(sel.kind, 11f, NVGRenderer.defaultFont)
            NVGRenderer.rect(cardX + 14f, cardY + 14f, kw + 16f, 18f, Color(accent, 0.30f).rgba, 9f)
            NVGRenderer.text(sel.kind, cardX + 22f, cardY + 18f, 11f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
            NVGRenderer.textShadow(NVGRenderer.truncate(sel.label, cardW - 28f, 24f, NVGRenderer.defaultFont), cardX + 14f, cardY + 38f, 24f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
            NVGRenderer.text(sel.id, cardX + 14f, cardY + 68f, 12f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)
            if (ent != null) {
                val sizeText = "%.2f wide   %.2f tall".format(ent.bbWidth, ent.bbHeight)
                val sw = NVGRenderer.textWidth(sizeText, 11f, NVGRenderer.defaultFont)
                NVGRenderer.text(sizeText, cardX + cardW - 14f - sw, cardY + 68f, 11f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)
            }
        }
        // Preview well (the mob itself is drawn by extractItems on top)
        NVGRenderer.gradientRect(pvX, pvY, pvW, pvH, 0xFF0B0B0F.toInt(), Color(accent, 0.16f).rgba, Gradient.TopToBottom, 8f)
        NVGRenderer.hollowRect(pvX, pvY, pvW, pvH, 1f, 0x22FFFFFF, 8f)

        // Status line
        val cur = MorphSync.current()
        val left = MorphSync.cooldownLeft()
        val line = when {
            status.isNotEmpty() -> status
            cur != null -> "Now: ${cur.removePrefix("minecraft:").replace('_', ' ')}" + if (left > 0) "   (others update in ${left}s)" else ""
            else -> "No morph active"
        }
        val lineColor = if (status.isNotEmpty() && !statusGood) 0xFFFF6B6B.toInt() else if (status.isNotEmpty()) 0xFF7CE08A.toInt() else Colors.MINECRAFT_GRAY.rgba
        NVGRenderer.text(NVGRenderer.truncate(line, cardW - 24f, 12f, NVGRenderer.defaultFont), cardX + 14f, applyY - 20f, 12f, lineColor, NVGRenderer.defaultFont)

        // Buttons
        val applyHover = isAreaHovered(cardX + 12f, applyY, cardW - 24f, 36f, mouseX, mouseY)
        val top = if (applyHover) Color(accent, 1f).rgba else Color(accent, 0.88f).rgba
        NVGRenderer.gradientRect(cardX + 12f, applyY, cardW - 24f, 36f, top, Color(accent, 0.58f).rgba, Gradient.TopToBottom, 8f)
        val at = if (left > 0) "Apply Morph  -  others in ${left}s" else "Apply Morph"
        val aw = NVGRenderer.textWidth(at, 15f, NVGRenderer.defaultFont)
        NVGRenderer.text(at, cardX + cardW / 2f - aw / 2f, applyY + 10f, 15f, Colors.WHITE.rgba, NVGRenderer.defaultFont)

        val remHover = isAreaHovered(cardX + 12f, removeY, cardW - 24f, 30f, mouseX, mouseY)
        NVGRenderer.rect(cardX + 12f, removeY, cardW - 24f, 30f, (if (remHover) 0xFF3A2024 else 0xFF26262C).toInt(), 8f)
        val rw = NVGRenderer.textWidth("Remove Morph", 14f, NVGRenderer.defaultFont)
        NVGRenderer.text("Remove Morph", cardX + cardW / 2f - rw / 2f, removeY + 8f, 14f, if (remHover) 0xFFFF8A8A.toInt() else 0xFFB0B0B8.toInt(), NVGRenderer.defaultFont)
    }

    /** The 3D mob in the preview well; it turns to follow the mouse. */
    override fun extractItems(g: GuiGraphicsExtractor, toGui: Float) {
        val sel = selected ?: return
        val ent = MorphSync.previewEntity(sel.id) ?: return
        ent.tickCount = Minecraft.getInstance().player?.tickCount ?: ent.tickCount
        val x0 = (pvX * toGui).toInt(); val y0 = (pvY * toGui).toInt()
        val x1 = ((pvX + pvW) * toGui).toInt(); val y1 = ((pvY + pvH) * toGui).toInt()
        val fitH = (y1 - y0) * 0.78f / maxOf(ent.bbHeight, 0.3f)
        val fitW = (x1 - x0) * 0.78f / maxOf(ent.bbWidth * 1.1f, 0.3f)
        val scale = minOf(fitH, fitW).toInt().coerceIn(3, 600)
        g.enableScissor(x0, y0, x1, y1)
        try {
            InventoryScreen.extractEntityInInventoryFollowsMouse(g, x0, y0, x1, y1, scale, 0.0625f, mx * toGui, my * toGui, ent)
        } catch (_: Throwable) { }
        g.disableScissor()
    }

    private fun thumbHeight(content: Float) = (listHeight * listHeight / content).coerceAtLeast(20f)

    private fun thumbY(content: Float): Float {
        val maxScroll = -(content - listHeight)
        val pct = if (maxScroll < 0f) (scrollOffset / maxScroll).coerceIn(0f, 1f) else 0f
        return listY + (listHeight - thumbHeight(content)) * pct
    }

    private fun report(refusal: String?, okText: String) {
        if (refusal == null) { status = okText; statusGood = true } else { status = refusal; statusGood = false }
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (Keys.mouse(click) != 0) return false
        if (input.mouseClicked(mouseX, mouseY, click)) return true

        if (isAreaHovered(cardX + 12f, applyY, cardW - 24f, 36f, mouseX, mouseY)) {
            selected?.let { s ->
                val left = MorphSync.cooldownLeft()
                report(MorphSync.request(s.id), if (left > 0) "Queued ${s.label}; others get it in ${left}s" else "Sending ${s.label}...")
                onChosen(s.id)
            }
            return true
        }
        if (isAreaHovered(cardX + 12f, removeY, cardW - 24f, 30f, mouseX, mouseY)) {
            report(MorphSync.request(null), "Removing morph...")
            return true
        }

        val content = filtered.size * ROW
        if (content > listHeight && isAreaHovered(listX + LIST_W - 12f, listY, 13f, listHeight, mouseX, mouseY)) {
            val thumb = thumbHeight(content); val ty = thumbY(content)
            if (mouseY !in ty..(ty + thumb)) {
                val pct = ((mouseY - listY - thumb / 2f) / (listHeight - thumb)).coerceIn(0f, 1f)
                scrollOffset = -(content - listHeight) * pct
            }
            draggingScrollbar = true; dragStartMouseY = mouseY; dragStartScrollOffset = scrollOffset
            return true
        }
        if (mouseX in listX..(listX + LIST_W) && mouseY in listY..(listY + listHeight)) {
            filtered.getOrNull(((mouseY - listY - scrollOffset) / ROW).toInt())?.let { selected = it; status = "" }
        }
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent) { input.mouseReleased(); draggingScrollbar = false }

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Int): Boolean {
        if (mouseX > listX + LIST_W) return false
        scrollOffset = (scrollOffset + amount).coerceIn((-filtered.size * ROW + listHeight).coerceAtMost(0f), 0f)
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean = input.key != Keys.ESCAPE && this.input.keyPressed(input)
    override fun keyTyped(input: CharacterEvent): Boolean = this.input.keyTyped(input)

    companion object {
        private const val LIST_W = 300f
        private const val ROW = 24f

        private val ALL: List<Entry> by lazy {
            BuiltInRegistries.ENTITY_TYPE
                .filter { it.category != MobCategory.MISC }
                .map { t ->
                    val key = BuiltInRegistries.ENTITY_TYPE.getKey(t)
                    Entry(key.toString(), t.description.string, t.category.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase))
                }
                .sortedBy { it.label }
        }
    }
}
