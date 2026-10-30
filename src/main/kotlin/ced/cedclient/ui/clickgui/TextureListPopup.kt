package ced.cedclient.ui.clickgui

import ced.cedclient.utils.Keys
import ced.cedclient.features.impl.render.VanillaTextures
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.TextInputHandler
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

/**
 * Vanilla Textures > "Choose Textures": every SkyBlock item, searchable by name or ID. Click one to keep
 * Hypixel's texture for it (green "Hypixel"), click again to go back to the old look ("Vanilla").
 * Typing "kept" lists only the items you've kept.
 */
class TextureListPopup : Popup("Choose Textures", WIDTH, HEIGHT) {

    private var search = ""
    private val input = TextInputHandler(textProvider = { search }, textSetter = { search = it.take(40); scroll = 0f })
    private var scroll = 0f

    init { input.focus() }

    private val inputY get() = y + 40f
    private val listTop get() = y + 76f
    private val listHeight get() = height - 86f
    private val rowH = 24f

    private var cacheFor: Triple<List<Pair<String, String>>, String, String>? = null
    private var cached: List<Pair<String, String>> = emptyList()

    /** Filtered list, redone only when the search, the item list or (for "kept") your picks change. */
    private fun shown(): List<Pair<String, String>> {
        val all = VanillaTextures.items()
        val q = search.trim()
        val keyKept = if (q.equals("kept", ignoreCase = true)) all.count { VanillaTextures.isKept(it.first) }.toString() else ""
        val key = Triple(all, q, keyKept)
        if (cacheFor?.let { it.first === all && it.second == q && it.third == keyKept } == true) return cached
        cacheFor = key
        cached = when {
            q.equals("kept", ignoreCase = true) -> all.filter { VanillaTextures.isKept(it.first) }
            q.isEmpty() -> all
            else -> all.filter { it.second.contains(q, ignoreCase = true) || it.first.contains(q.replace(' ', '_'), ignoreCase = true) }
        }
        return cached
    }

    override fun drawContent(mouseX: Float, mouseY: Float) {
        val font = NVGRenderer.defaultFont
        NVGRenderer.rect(x + 12f, inputY, width - 24f, 24f, Colors.INPUT_BG.rgba, 4f)
        NVGRenderer.hollowRect(x + 12f, inputY, width - 24f, 24f, 1f, Colors.INPUT_BORDER.rgba, 4f)
        if (search.isEmpty()) NVGRenderer.text("Search items...  (type \"kept\" for the ones you kept)", x + 18f, inputY + 5f, 14f, Colors.MINECRAFT_GRAY.rgba, font)
        input.x = x + 12f; input.y = inputY + 1f; input.width = width - 24f; input.height = 22f
        input.draw(mouseX, mouseY)

        val items = shown()
        if (items.isEmpty()) {
            NVGRenderer.text("Nothing found.", x + 16f, listTop + 6f, 14f, Colors.MINECRAFT_GRAY.rgba, font)
            return
        }
        NVGRenderer.pushScissor(x, listTop, width, listHeight)
        val first = (-scroll / rowH).toInt().coerceAtLeast(0)
        val last = minOf(items.size, first + (listHeight / rowH).toInt() + 2)
        for (i in first until last) {
            val (id, name) = items[i]
            val rowY = listTop + scroll + i * rowH
            if (isAreaHovered(x + 8f, rowY, width - 16f, rowH - 2f, mouseX, mouseY)) NVGRenderer.rect(x + 8f, rowY, width - 16f, rowH - 2f, Colors.gray38.rgba, 4f)
            NVGRenderer.text(name, x + 16f, rowY + 4f, 15f, Colors.WHITE.rgba, font)
            NVGRenderer.text(id, x + 300f, rowY + 5f, 13f, Colors.MINECRAFT_GRAY.rgba, font)
            val kept = VanillaTextures.isKept(id)
            val label = if (kept) "Hypixel" else "Vanilla"
            NVGRenderer.text(label, x + width - 20f - NVGRenderer.textWidth(label, 14f, font), rowY + 4f, 14f,
                (if (kept) Colors.MINECRAFT_GREEN else Colors.MINECRAFT_GRAY).rgba, font)
        }
        NVGRenderer.popScissor()
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (input.mouseClicked(mouseX, mouseY, click)) return true
        if (Keys.mouse(click) != 0 || mouseY !in listTop..(listTop + listHeight)) return false
        val (id, _) = shown().getOrNull(((mouseY - listTop - scroll) / rowH).toInt()) ?: return false
        VanillaTextures.toggleKeep(id)
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean = input.key != Keys.ESCAPE && this.input.keyPressed(input)
    override fun keyTyped(input: CharacterEvent): Boolean = this.input.keyTyped(input)

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Int): Boolean {
        scroll = (scroll + amount * rowH * 3).coerceIn(minOf(0f, listHeight - shown().size * rowH), 0f)
        return true
    }
}
