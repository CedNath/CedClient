package ced.cedclient.features.impl.misc

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.Debug
import ced.cedclient.utils.Keys
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.io.File

/**
 * Storage Overlay: remembers what is in each ender chest page and backpack the last time you opened it, and shows all of
 * it in one searchable screen (key or the Open Storage button) from anywhere in the world. Read-only: clicking a page
 * title runs /ec N or /bp N to open the real one.
 *
 * Capture: while a container titled "Ender Chest (3/9)" or "<Name> Backpack (Slot #2)" is open, its contents are copied
 * every tick and written to config/cedclient/storage/<player uuid>.json when it closes. Items are stored with the game's
 * own ItemStack codec (JSON), so they come back exactly, which is why browsing needs a world to be loaded.
 * The titles and the filler items skipped below follow what SkyBlock shows; not testable outside Hypixel.
 */
object StorageOverlay : Module(
    "Storage Overlay", Category.Skyblock,
    "Remembers your ender chest and backpacks and lets you browse and search all of it anywhere."
) {
    private val openKey = KeybindSetting("Open Key", Keys.UNKNOWN, "Opens the storage browser.")
    private val openButton = ActionSetting("Open Storage", "Opens the storage browser.") {
        Minecraft.getInstance().execute { open() }
    }

    class Stored(val title: String, val order: Int, val at: Long, val items: JsonObject)

    private val pages = LinkedHashMap<String, Stored>()
    private var loadedFor: String? = null
    private val gson = GsonBuilder().create()

    private val enderRe = Regex("^Ender Chest \\((\\d+)/(\\d+)\\)$")
    private val backpackRe = Regex("^(.+ Backpack) \\(Slot #(\\d+)\\)$")
    private val skipNames = setOf("Close", "Go Back", "Next Page", "Previous Page")
    private val skipPrefixes = listOf("Ender Chest Page", "Backpack Slot", "Empty Backpack Slot")

    private val openWithMenu = BooleanSetting("Open With Storage Menu", true, "Opening /storage shows this browser instead of the plain menu (once something is remembered).")

    private var keyWasDown = false

    init {
        addSettings(openKey, openButton, openWithMenu)

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (!isEnabled || screen !is AbstractContainerScreen<*>) return@register
            // The /storage overview: swap it for the browser (a tick later, a screen can't be replaced inside its own init).
            if (openWithMenu.value && screen.title.string == "Storage") {
                Minecraft.getInstance().execute { if (Minecraft.getInstance().gui.screen() === screen) open() }
                return@register
            }
            val page = pageOf(screen.title.string) ?: return@register
            var ticks = 0
            ScreenEvents.afterTick(screen).register {
                ticks++
                snapshot(screen, page, ticks)
            }
            ScreenEvents.remove(screen).register { save() }
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val down = isEnabled && client.gui.screen() == null && Keys.isDown(openKey.value)
            if (down && !keyWasDown) open()
            keyWasDown = down
        }
    }

    private class PageInfo(val key: String, val order: Int, val title: String)

    private fun pageOf(title: String): PageInfo? {
        enderRe.find(title)?.let { return PageInfo("ec:${it.groupValues[1]}", it.groupValues[1].toInt(), title) }
        backpackRe.find(title)?.let { return PageInfo("bp:${it.groupValues[2]}", 100 + it.groupValues[2].toInt(), title) }
        return null
    }

    private fun file(uuid: String) = File(CedPaths.dir, "storage/$uuid.json")

    private fun ensureLoaded() {
        val id = Minecraft.getInstance().player?.uuid?.toString() ?: return
        if (loadedFor == id) return
        loadedFor = id
        pages.clear()
        runCatching {
            val f = file(id)
            if (!f.exists()) return
            for ((key, v) in JsonParser.parseString(f.readText()).asJsonObject.entrySet()) {
                val o = v.asJsonObject
                pages[key] = Stored(o["title"].asString, o["order"].asInt, o["at"].asLong, o["items"].asJsonObject)
            }
        }.onFailure { Debug.line(name, "couldn't read the cache: ${it.message}") }
    }

    private fun save() {
        val id = loadedFor ?: return
        runCatching {
            val root = JsonObject()
            for ((key, p) in pages) root.add(key, JsonObject().apply {
                addProperty("title", p.title); addProperty("order", p.order); addProperty("at", p.at); add("items", p.items)
            })
            val f = file(id)
            f.parentFile.mkdirs()
            f.writeText(gson.toJson(root))
        }.onFailure { Debug.line(name, "couldn't save the cache: ${it.message}") }
    }

    private fun snapshot(screen: AbstractContainerScreen<*>, page: PageInfo, ticks: Int) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        ensureLoaded()
        val ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE)
        val slots = screen.menu.slots
        val items = JsonObject()
        for (i in 0 until (slots.size - 36).coerceAtLeast(0)) {
            val stack = slots[i].item
            if (stack.isEmpty) continue
            val label = stack.hoverName.string
            if (label.isBlank() || label in skipNames || skipPrefixes.any { label.startsWith(it) }) continue
            ItemStack.CODEC.encodeStart(ops, stack).result().ifPresent { items.add(i.toString(), it) }
        }
        // The contents arrive a moment after the screen opens: don't overwrite a good copy with an empty one too early.
        if (items.size() == 0 && ticks < 10) return
        val old = pages[page.key]
        if (old != null && old.items == items) return
        pages[page.key] = Stored(page.title, page.order, System.currentTimeMillis(), items)
        Debug.line(name, "${page.title}: ${items.size()} items")
    }

    fun open() {
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level == null || mc.player == null) return
        ensureLoaded()
        if (pages.isEmpty()) {
            mc.player?.sendSystemMessage(ChatPrefix.msg("§cNothing stored yet. §7Open your ender chest pages and backpacks once and they are remembered."))
            return
        }
        val ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE)
        val shown = pages.entries.sortedBy { it.value.order }.map { (key, p) ->
            val cells = p.items.entrySet().mapNotNull { (slot, json) ->
                val stack = ItemStack.CODEC.parse(ops, json).result().orElse(null) ?: return@mapNotNull null
                StorageScreen.Cell(slot.toInt(), stack, (stack.hoverName.string + " " + BuiltInRegistries.ITEM.getKey(stack.item).path).lowercase())
            }
            StorageScreen.Page(key, p.title, cells)
        }
        mc.gui.setScreen(StorageScreen(shown))
    }

    class StorageScreen(private val pages: List<Page>) : Screen(Component.literal("Storage")) {
        class Cell(val slot: Int, val stack: ItemStack, val text: String)
        class Page(val key: String, val title: String, val cells: List<Cell>)

        /** Rows of a page: the container's top row only holds the page buttons, so items start at slot 9 = row 0; ender chest pages are always 5 rows. */
        private fun rowOf(c: Cell) = (c.slot / COLS - 1).coerceAtLeast(0)
        private fun rowsOf(p: Page) = maxOf(if (p.key.startsWith("ec:")) 5 else 1, (p.cells.maxOfOrNull { rowOf(it) } ?: 0) + 1)

        private var query = ""
        private var scroll = 0
        private var contentH = 0
        private class TitleHit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val page: Page)
        private val titleHits = ArrayList<TitleHit>()

        private fun matches(c: Cell) = query.isBlank() || c.text.contains(query.lowercase())

        override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
            val font = Minecraft.getInstance().font
            g.fill(0, 0, width, height, 0xC0101018.toInt())
            // As many pages side by side as fit (up to 3), each a 9 wide grid, like the real storage menu.
            val pageW = COLS * CELL
            val perRow = ((width - 40 + GAP) / (pageW + GAP)).coerceIn(1, 3)
            val panelW = perRow * pageW + (perRow - 1) * GAP + 24
            val px = (width - panelW) / 2
            g.fill(px, 4, px + panelW, height - 4, 0xE0181820.toInt())
            g.text(font, "Search: $query" + if (System.currentTimeMillis() / 500 % 2 == 0L) "_" else "", px + 12, 10, -1)

            val top = 26
            val bottom = height - 8
            titleHits.clear()
            var hover: ItemStack? = null
            g.enableScissor(px, top, px + panelW, bottom)
            var y = top - scroll
            val visible = pages.filter { query.isBlank() || it.cells.any { c -> matches(c) } }
            for (chunk in visible.chunked(perRow)) {
                chunk.forEachIndexed { n, page ->
                    val x0 = px + 12 + n * (pageW + GAP)
                    g.text(font, page.title, x0, y + 1, 0xFFFFD700.toInt())
                    titleHits += TitleHit(x0, y, x0 + pageW, y + 10, page)
                    val gridY = y + 12
                    val rows = rowsOf(page)
                    for (r in 0 until rows) for (col in 0 until COLS) g.fill(x0 + col * CELL, gridY + r * CELL, x0 + col * CELL + CELL - 1, gridY + r * CELL + CELL - 1, 0xFF2A2A32.toInt())
                    for (c in page.cells) {
                        val cx = x0 + (c.slot % COLS) * CELL
                        val cy = gridY + rowOf(c) * CELL
                        if (cy + CELL < top || cy > bottom) continue
                        g.item(c.stack, cx + 1, cy + 1)
                        g.itemDecorations(font, c.stack, cx + 1, cy + 1)
                        if (query.isNotBlank()) {
                            if (matches(c)) g.outline(cx, cy, CELL - 1, CELL - 1, 0xFFFFD700.toInt())
                            else g.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, 0xA0101018.toInt())
                        }
                        if (mouseX in cx until cx + CELL && mouseY in cy until cy + CELL && mouseY in top until bottom) hover = c.stack
                    }
                }
                y += 12 + chunk.maxOf { rowsOf(it) } * CELL + 10
            }
            g.disableScissor()
            contentH = y + scroll - top
            hover?.let { g.setTooltipForNextFrame(font, it, mouseX, mouseY) }
        }

        override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
            val view = height - 8 - 26
            scroll = (scroll - (verticalAmount * 20).toInt()).coerceIn(0, maxOf(0, contentH - view))
            return true
        }

        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
            if (Keys.mouse(event) == 0) {
                val hit = titleHits.firstOrNull { event.x() >= it.x1 && event.x() < it.x2 && event.y() >= it.y1 && event.y() < it.y2 && event.y() >= 26 }?.page
                val kind = hit?.key?.substringBefore(':')
                val n = hit?.key?.substringAfter(':')
                val command = when (kind) { "ec" -> "ec $n"; "bp" -> "bp $n"; else -> null }
                if (command != null) {
                    onClose()
                    Minecraft.getInstance().player?.connection?.sendCommand(command)
                    return true
                }
            }
            return super.mouseClicked(event, doubleClick)
        }

        override fun keyPressed(event: KeyEvent): Boolean {
            if (event.key == Keys.BACKSPACE) { query = query.dropLast(1); scroll = 0; return true }
            return super.keyPressed(event)
        }

        override fun charTyped(event: CharacterEvent): Boolean {
            if (!event.isAllowedChatCharacter) return false
            query += event.codepointAsString()
            scroll = 0
            return true
        }

        // The search box always has the keyboard: letters must reach charTyped instead of the game's key bindings.
        override fun isInputCaptured(): Boolean = true

        override fun isPauseScreen(): Boolean = false

        private companion object {
            const val COLS = 9
            const val CELL = 18
            const val GAP = 14
        }
    }
}
