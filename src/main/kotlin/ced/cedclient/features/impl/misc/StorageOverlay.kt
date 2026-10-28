package ced.cedclient.features.impl.misc

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
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

    private var keyWasDown = false

    init {
        addSettings(openKey, openButton)

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (!isEnabled || screen !is AbstractContainerScreen<*>) return@register
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

        private var query = ""
        private var scroll = 0
        private var contentH = 0
        private val titleHits = ArrayList<Triple<Int, Int, Page>>()

        private fun matches(c: Cell) = query.isBlank() || c.text.contains(query.lowercase())

        override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
            val font = Minecraft.getInstance().font
            g.fill(0, 0, width, height, 0xC0101018.toInt())
            val panelW = COLS * CELL + 16
            val px = (width - panelW) / 2
            g.fill(px, 4, px + panelW, height - 4, 0xE0181820.toInt())
            g.text(font, "Search: $query" + if (System.currentTimeMillis() / 500 % 2 == 0L) "_" else "", px + 8, 10, -1)

            val top = 26
            val bottom = height - 8
            titleHits.clear()
            var hover: ItemStack? = null
            g.enableScissor(px, top, px + panelW, bottom)
            var y = top - scroll
            for (page in pages) {
                if (query.isNotBlank() && page.cells.none { matches(it) }) continue
                g.text(font, page.title, px + 8, y + 1, 0xFFFFD700.toInt())
                titleHits += Triple(y, y + 10, page)
                y += 12
                val rows = (page.cells.maxOfOrNull { it.slot } ?: -1) / COLS + 1
                for (c in page.cells) {
                    val cx = px + 8 + (c.slot % COLS) * CELL
                    val cy = y + (c.slot / COLS) * CELL
                    if (cy + CELL < top || cy > bottom) continue
                    g.item(c.stack, cx + 1, cy + 1)
                    g.itemDecorations(font, c.stack, cx + 1, cy + 1)
                    if (query.isNotBlank()) {
                        if (matches(c)) g.outline(cx, cy, CELL, CELL, 0xFFFFD700.toInt())
                        else g.fill(cx, cy, cx + CELL, cy + CELL, 0xA0101018.toInt())
                    }
                    if (mouseX in cx until cx + CELL && mouseY in cy until cy + CELL && mouseY in top until bottom) hover = c.stack
                }
                y += rows * CELL + 6
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
            if (event.button() == 0) {
                val hit = titleHits.firstOrNull { event.y() >= it.first && event.y() < it.second && event.y() >= 26 }?.third
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

        override fun isPauseScreen(): Boolean = false

        private companion object {
            const val COLS = 9
            const val CELL = 18
        }
    }
}
