package ced.cedclient.features.impl.funqol

import ced.cedclient.config.CedPaths
import ced.cedclient.events.core.TickEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.misc.Theme
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.mixin.accessor.AbstractContainerScreenAccessor
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.state.DungeonState
import ced.cedclient.state.Sidebar
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.RunLootPopup
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.LowestBin
import ced.cedclient.utils.cleanName
import ced.cedclient.utils.loreLines
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.math.abs

/**
 * Run Profit (a Dungeon Run Tracker): every dungeon / Kuudra reward chest you open is logged with its items, what
 * they sell for and what the chest cost. A HUD shows this session's floor, runs and profit, /cc loot opens the
 * history (floor filter, sort, search), the Croesus chest list shows each chest's profit with the best one marked,
 * and the Fancy Menu swaps the reward chest screen for a clean item list with one Open button.
 *
 * Prices: Bazaar items at the Bazaar Price setting, the rest at lowest BIN ([LowestBin]); Croesus lore only names
 * its items, so those are matched through the NEU item repo (config/notenoughupdates/repo, same as Item Value).
 */
object RunProfit : Module(
    "Run Profit",
    Category.Skyblock,
    "Tracks the loot and profit of every dungeon and Kuudra chest you open: HUD, history (/cc loot), Croesus chest profits, Fancy Menu."
), HudElement {
    private val hud = BooleanSetting("HUD", true, "Floor, runs and profit of this session.")
    private val kuudra = BooleanSetting("Track Kuudra", true, "Log Kuudra chests too.")
    private val priceSide = DropdownSetting("Bazaar Price", listOf("Instant Sell", "Instant Buy"), "Instant Sell", "Which Bazaar price items are worth.")
    private val essence = BooleanSetting("Essence In Profit", true, "Count essence as loot.")
    private val keyCost = BooleanSetting("Count Chest Keys", true, "A Dungeon Chest Key used on a chest is part of its cost.")
    private val croesus = BooleanSetting("Croesus Profit", true, "Shows each chest's profit on the Croesus chest list.")
    private val croesusBest = BooleanSetting("Highlight Best Chest", true, "Marks the most profitable chest gold.")
    private val fancy = BooleanSetting("Fancy Menu", false, "Replaces the reward chest screen with a clean item list and an Open button.")
    private val historyButton = ActionSetting("Loot History", "Every logged chest (same as /cc loot).") {
        (mc.screen as? ClickGUI)?.openPopup(RunLootPopup())
    }

    private val mc get() = Minecraft.getInstance()
    private val startedAt = System.currentTimeMillis()

    // ---------------------------------------------------------------- log

    class Item(var name: String = "", var count: Int = 1, var value: Double = 0.0, var essence: Boolean = false)

    class Entry {
        var time = 0L
        var mode = "Dungeon"
        var floor = "?"
        var chest = ""
        var cost = 0.0
        var items: MutableList<Item> = ArrayList()
    }

    /** Saved chests (run_profit.json), oldest first. */
    object Log {
        private const val MAX = 5000
        private val gson = GsonBuilder().create()
        private val file: File by lazy { CedPaths.file("run_profit.json") }
        val entries: MutableList<Entry> by lazy {
            runCatching { gson.fromJson<MutableList<Entry>>(file.readText(), object : TypeToken<MutableList<Entry>>() {}.type) }.getOrNull() ?: mutableListOf()
        }

        fun add(entry: Entry) {
            entries += entry
            while (entries.size > MAX) entries.removeAt(0)
            runCatching { file.parentFile?.mkdirs(); file.writeText(gson.toJson(entries)) }
                .onFailure { println("[RunProfit] Failed to save the loot log: ${it.message}") }
        }
    }

    internal fun valueOf(e: Entry) = e.items.sumOf { if (essence.value || !it.essence) it.value else 0.0 }
    internal fun profitOf(e: Entry) = valueOf(e) - e.cost
    private fun session() = Log.entries.filter { it.time >= startedAt && (kuudra.value || it.mode != "Kuudra") }

    // ---------------------------------------------------------------- where am I

    private var ctxMode = "Dungeon"
    private var ctxFloor: String? = null
    private var ticks = 0
    @Volatile
    private var pendingPopup = false
    private var recorded = -1
    private val KUUDRA_TIER = Regex("""\(T(\d)\)""")
    private val CROESUS_FLOOR = Regex("""^(Master Mode )?(?:The )?Catacombs - Floor ([IVX]+)$""")

    private fun tick() {
        if (pendingPopup) {
            pendingPopup = false
            mc.setScreen(ClickGUI())
            (mc.screen as? ClickGUI)?.openPopup(RunLootPopup())
        }
        if (!isEnabled || ++ticks % 20 != 0) return
        if (DungeonState.inDungeon) {
            DungeonState.floor?.let { ctxMode = "Dungeon"; ctxFloor = it }
        } else {
            val area = Sidebar.area?.takeIf { it.contains("Kuudra") } ?: return
            ctxMode = "Kuudra"
            ctxFloor = KUUDRA_TIER.find(area)?.let { "T" + it.groupValues[1] } ?: "T?"
        }
    }

    /** The Croesus run screen names its floor ("Master Mode Catacombs - Floor VII"): chests opened from it belong there. */
    private fun contextFromTitle(screen: AbstractContainerScreen<*>) {
        val m = CROESUS_FLOOR.matchEntire(Sidebar.clean(screen.title.string).trim()) ?: return
        val n = roman(m.groupValues[2]) ?: return
        ctxMode = "Dungeon"
        ctxFloor = (if (m.groupValues[1].isNotEmpty()) "M" else "F") + n
    }

    private fun roman(s: String): Int? {
        var total = 0
        var prev = 0
        for (c in s.reversed()) {
            val v = when (c) { 'I' -> 1; 'V' -> 5; 'X' -> 10; 'L' -> 50; 'C' -> 100; else -> return null }
            total += if (v < prev) -v else v
            prev = maxOf(prev, v)
        }
        return total.takeIf { it > 0 }
    }

    // ---------------------------------------------------------------- prices

    private val ESSENCE = Regex("""^(\w+) Essence x(\d+)$""")
    private val COINS = Regex("""([\d,]+(?:\.\d+)?)\s*Coins""")
    private val ENCHANT = Regex("""^(.+) ([IVXLC]+)$""")

    /** Display name (colour codes off) -> NEU internal name, for Croesus lore lines. Filled in the background. */
    @Volatile
    private var names: Map<String, String> = emptyMap()

    private fun loadNames() {
        val dir = FabricLoader.getInstance().configDir.resolve("notenoughupdates/repo/items")
        if (!Files.isDirectory(dir)) return
        val codes = Regex("§.")
        val map = HashMap<String, String>()
        Files.list(dir).use { files ->
            files.forEach { f ->
                runCatching {
                    val o = JsonParser.parseString(Files.readString(f)).asJsonObject
                    map.putIfAbsent(o["displayname"].asString.replace(codes, "").trim(), o["internalname"].asString)
                }
            }
        }
        names = map
    }

    private fun side(bz: Pair<Double, Double>) = if (priceSide.value == "Instant Buy") bz.first else bz.second
    private fun unit(s: ItemStack) = LowestBin.bazaarOf(s)?.let(::side) ?: LowestBin.of(s) ?: 0.0
    private fun essencePrice(type: String): Double {
        val id = "ESSENCE_${type.uppercase()}"
        return LowestBin.bazaarOfId(id)?.let(::side) ?: LowestBin.ofId(id) ?: 0.0
    }

    private fun coins(line: String) = COINS.find(line)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

    /** The coins a "Cost" lore section adds up to (plus a Dungeon Chest Key when Count Chest Keys is on). */
    private fun costOf(lore: List<String>): Double {
        val at = lore.indexOf("Cost").takeIf { it >= 0 } ?: return 0.0
        var cost = 0.0
        for (line in lore.drop(at + 1)) {
            if (line.isBlank()) break
            coins(line)?.let { cost += it }
            if (keyCost.value && line.contains("Dungeon Chest Key")) cost += LowestBin.ofId("DUNGEON_CHEST_KEY") ?: 0.0
        }
        return cost
    }

    /** One Croesus lore line ("Rejuvenate IV", "Wither Essence x8", an item name) -> (coins, isEssence); null when it has no price. */
    private fun lineValue(line: String): Pair<Double, Boolean>? {
        ESSENCE.matchEntire(line)?.let { return essencePrice(it.groupValues[1]) * it.groupValues[2].toInt() to true }
        ENCHANT.matchEntire(line)?.let { m ->
            val level = roman(m.groupValues[2])
            val base = m.groupValues[1].uppercase().replace(' ', '_').replace('-', '_')
            if (level != null) {
                (LowestBin.ofId("ULTIMATE_$base;$level") ?: LowestBin.ofId("$base;$level"))?.let { return it to false }
            }
        }
        return names[line]?.let(LowestBin::ofId)?.let { it to false }
    }

    // ---------------------------------------------------------------- reward chest

    private const val OPEN_SLOT = 31
    private val REWARD_TITLES = setOf("Wood Chest", "Gold Chest", "Diamond Chest", "Emerald Chest", "Obsidian Chest", "Bedrock Chest", "Free Chest", "Paid Chest")
    private val NOT_LOOT = setOf("Open Reward Chest", "Close", "Go Back")

    private class Reward(val stacks: List<ItemStack>, val items: List<Item>, val cost: Double, val ready: Boolean)

    private fun chestSlots(screen: AbstractContainerScreen<*>) = (screen.menu.slots.size - 36).coerceAtLeast(0)
    private fun title(screen: AbstractContainerScreen<*>) = Sidebar.clean(screen.title.string).trim()

    /** The "Open Reward Chest" button item, once the chest's items have arrived. */
    private fun openStack(screen: AbstractContainerScreen<*>): ItemStack? {
        if (OPEN_SLOT >= chestSlots(screen)) return null
        return screen.menu.slots[OPEN_SLOT].item.takeIf { !it.isEmpty && it.cleanName.contains("Reward Chest", ignoreCase = true) }
    }

    private fun isLoot(s: ItemStack) =
        !s.isEmpty && s.cleanName.isNotBlank() && s.cleanName !in NOT_LOOT && !BuiltInRegistries.ITEM.getKey(s.item).path.endsWith("glass_pane")

    private fun itemOf(s: ItemStack): Item {
        val name = s.cleanName
        ESSENCE.matchEntire(name)?.let { m ->
            val n = m.groupValues[2].toInt()
            return Item(name, n, essencePrice(m.groupValues[1]) * n, true)
        }
        val label = if (name == "Enchanted Book") s.loreLines.firstOrNull()?.takeIf { it.isNotBlank() } ?: name else name
        return Item(label, s.count, unit(s) * s.count)
    }

    private fun parseReward(screen: AbstractContainerScreen<*>): Reward {
        val open = openStack(screen)
        val stacks = (0 until chestSlots(screen)).filter { it != OPEN_SLOT }.map { screen.menu.slots[it].item }.filter(::isLoot)
        return Reward(stacks, stacks.map(::itemOf), open?.let { costOf(it.loreLines) } ?: 0.0, open != null)
    }

    private fun record(screen: AbstractContainerScreen<*>) {
        val id = screen.menu.containerId
        if (id == recorded) return
        val reward = parseReward(screen)
        if (!reward.ready || reward.items.isEmpty()) return
        val name = title(screen)
        val isKuudra = name.startsWith("Free") || name.startsWith("Paid") || name.contains("Kuudra")
        if (isKuudra && !kuudra.value) return
        recorded = id
        val mode = if (isKuudra) "Kuudra" else "Dungeon"
        Log.add(Entry().apply {
            time = System.currentTimeMillis()
            this.mode = mode
            floor = ctxFloor.takeIf { ctxMode == mode } ?: "?"
            chest = name
            cost = reward.cost
            items = reward.items.toMutableList()
        })
    }

    /** Mixin: a click on the screen. True = swallow it (Fancy Menu), else the chest button's click is logged and goes on. */
    fun onClick(screen: AbstractContainerScreen<*>, mx: Double, my: Double, button: Int): Boolean {
        if (!isEnabled) return false
        if (handles(screen)) return fancyClick(screen, mx, my, button)
        if (button != 0 || openStack(screen) == null) return false
        val hovered = (screen as AbstractContainerScreenAccessor).`ced$getHoveredSlot`() ?: return false
        if (screen.menu.slots.indexOf(hovered) == OPEN_SLOT) record(screen)
        return false
    }

    // ---------------------------------------------------------------- croesus overlay

    private class View(val slot: Int, val profit: Double)

    private var viewId = -1
    private var viewAt = 0L
    private var views: List<View> = emptyList()

    /** Every chest button of the screen with a Contents and a Cost section in its lore, with its profit. */
    private fun croesusChests(screen: AbstractContainerScreen<*>): List<View> {
        val now = System.currentTimeMillis()
        if (screen.menu.containerId == viewId && now - viewAt < 500) return views
        viewId = screen.menu.containerId
        viewAt = now
        views = (0 until chestSlots(screen)).mapNotNull { i ->
            val stack = screen.menu.slots[i].item.takeIf { !it.isEmpty } ?: return@mapNotNull null
            val lore = stack.loreLines
            val contents = lore.indexOf("Contents").takeIf { it >= 0 } ?: return@mapNotNull null
            if ("Cost" !in lore) return@mapNotNull null
            var value = 0.0
            for (line in lore.drop(contents + 1)) {
                if (line.isBlank()) break
                lineValue(line)?.let { (v, isEssence) -> if (essence.value || !isEssence) value += v }
            }
            View(i, value - costOf(lore))
        }
        return views
    }

    /** Mixin: right after the slots are drawn, so tooltips stay on top. */
    fun drawOverlays(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor) {
        if (!isEnabled || !croesus.value || screen !== mc.screen || screen !is ContainerScreen) return
        contextFromTitle(screen)
        val chests = croesusChests(screen)
        if (chests.isEmpty()) return
        val best = if (croesusBest.value) chests.maxByOrNull { it.profit } else null
        val acc = screen as AbstractContainerScreenAccessor
        for (c in chests) {
            val slot = screen.menu.slots[c.slot]
            val x = acc.`ced$getLeftPos`() + slot.x
            val y = acc.`ced$getTopPos`() + slot.y
            if (c === best) g.fill(x, y, x + 16, y + 16, 0x66FFD700)
            val text = signed(c.profit)
            val scale = 0.6f
            g.pose().pushMatrix()
            g.pose().translate(x + 8f - mc.font.width(text) * scale / 2f, y + 16.5f)
            g.pose().scale(scale, scale)
            g.text(mc.font, text, 0, 0, if (c.profit >= 0) GREEN else RED, true)
            g.pose().popMatrix()
        }
    }

    // ---------------------------------------------------------------- fancy menu

    private const val PW = 260f
    private const val ROW = 18f
    private const val GREEN = 0xFF55FF55.toInt()
    private const val RED = 0xFFFF5555.toInt()
    private const val GRAY = 0xFFBBBBBB.toInt()

    fun handles(screen: AbstractContainerScreen<*>) =
        isEnabled && fancy.value && screen is ContainerScreen && title(screen) in REWARD_TITLES

    private fun panel(screen: AbstractContainerScreen<*>, rows: Int): FloatArray {
        val h = 44f + rows.coerceAtLeast(1) * ROW + 66f
        return floatArrayOf((screen.width - PW) / 2f, (screen.height - h) / 2f, h)
    }

    fun render(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        contextFromTitle(screen)
        val reward = parseReward(screen)
        val (x, y, h) = panel(screen, reward.items.size).let { Triple(it[0], it[1], it[2]) }
        val value = reward.items.sumOf { if (essence.value || !it.essence) it.value else 0.0 }
        val profit = value - reward.cost
        val btnY = y + h - 34f
        val hover = reward.ready && mouseX >= x + 16f && mouseX <= x + PW - 16f && mouseY >= btnY && mouseY <= btnY + 24f
        HudCard.draw(g, 0, 0) {
            NVGRenderer.rect(0f, 0f, screen.width.toFloat(), screen.height.toFloat(), 0x88000000.toInt())
            NVGRenderer.rect(x, y, PW, h, HudCard.BACKGROUND, 8f)
            NVGRenderer.hollowRect(x, y, PW, h, 1f, Theme.hudBorderRgba, 8f)
            HudCard.text(title(screen), x + 14f, y + 12f, 14f, -1)
            if (!reward.ready) HudCard.text("Loading...", x + 14f, y + 44f, 11f, GRAY)
            reward.items.forEachIndexed { i, item ->
                val ry = y + 42f + i * ROW
                HudCard.text((if (item.count > 1) "${item.count}x " else "") + item.name, x + 36f, ry + 3f, 11f, -1)
                val v = short(item.value)
                HudCard.text(v, x + PW - 14f - HudCard.width(v, 11f), ry + 3f, 11f, if (item.value > 0) 0xFFFFD700.toInt() else 0xFF777777.toInt())
            }
            val sy = y + 44f + reward.items.size.coerceAtLeast(1) * ROW
            HudCard.text("Value ${short(value)}   Cost ${short(reward.cost)}", x + 14f, sy + 2f, 11f, GRAY)
            val p = "Profit ${signed(profit)}"
            HudCard.text(p, x + PW - 14f - HudCard.width(p, 11f), sy + 2f, 11f, if (profit >= 0) GREEN else RED)
            NVGRenderer.rect(x + 16f, btnY, PW - 32f, 24f, if (hover) 0xF0303040.toInt() else 0xFF222230.toInt(), 6f)
            NVGRenderer.hollowRect(x + 16f, btnY, PW - 32f, 24f, 1f, if (reward.ready) Theme.hudBorderRgba else 0xFF555555.toInt(), 6f)
            val label = if (reward.ready) "Open Chest" else "Waiting for the chest..."
            HudCard.text(label, x + PW / 2f - HudCard.width(label, 12f) / 2f, btnY + 6f, 12f, if (reward.ready) -1 else 0xFF777777.toInt())
        }
        // Item icons are vanilla sprites: a stratum above the NanoVG panel so they land on top of it.
        g.nextStratum()
        reward.stacks.forEachIndexed { i, stack -> g.item(stack, (x + 14f).toInt(), (y + 41f + i * ROW).toInt()) }
    }

    private fun fancyClick(screen: AbstractContainerScreen<*>, mx: Double, my: Double, button: Int): Boolean {
        if (button != 0) return true
        val reward = parseReward(screen)
        if (!reward.ready) return true
        val (x, y, h) = panel(screen, reward.items.size).let { Triple(it[0], it[1], it[2]) }
        val btnY = y + h - 34f
        if (mx < x + 16f || mx > x + PW - 16f || my < btnY || my > btnY + 24f) return true
        record(screen)
        val player = mc.player ?: return true
        mc.gameMode?.handleContainerInput(screen.menu.containerId, OPEN_SLOT, 0, ContainerInput.PICKUP, player)
        ced.cedclient.utils.Debug.line("Run Profit", "opened the reward chest")
        return true
    }

    // ---------------------------------------------------------------- formatting

    /** 5200000 -> "5.20M", 17750 -> "17.8k". */
    internal fun short(v: Double): String {
        val a = abs(v)
        return when {
            a >= 1e9 -> "%.2fB".format(Locale.ROOT, v / 1e9)
            a >= 1e6 -> "%.2fM".format(Locale.ROOT, v / 1e6)
            a >= 1e3 -> "%.1fk".format(Locale.ROOT, v / 1e3)
            else -> "%.0f".format(Locale.ROOT, v)
        }
    }

    internal fun signed(v: Double) = (if (v >= 0) "+" else "") + short(v)

    // ---------------------------------------------------------------- command

    fun commandTree(): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal("loot").executes { ctx ->
            if (!isEnabled) ctx.source.sendFeedback(ChatPrefix.msgFor("Run Profit", "§cRun Profit is off")) else pendingPopup = true
            1
        }

    // ---------------------------------------------------------------- HUD

    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") tickCounter: DeltaTracker) {
        if (!isEnabled || !hud.value || mc.options.hideGui) return
        val inKuudra = Sidebar.area?.contains("Kuudra") == true
        if (!DungeonState.inDungeon && !inKuudra && session().isEmpty()) return
        renderInternal(g)
    }

    override fun renderInternal(g: GuiGraphicsExtractor) {
        val runs = session()
        val profit = runs.sumOf(::profitOf)
        val rows = listOf("Floor" to (ctxFloor ?: "-"), "Runs" to runs.size.toString(), "Profit" to signed(profit))
        val s = panelScale
        val size = 10f * s
        val lineH = 12f * s
        val pad = 6f * s
        val gap = 10f * s
        val leftW = rows.maxOf { HudCard.width(it.first, size) }
        val rightW = rows.maxOf { HudCard.width(it.second, size) }
        val width = HudCard.steady("Run Profit", leftW + gap + rightW + pad * 2, s)
        val height = rows.size * lineH + pad * 2 - (lineH - size)
        lastWidth = (width / s).toInt()
        lastHeight = (height / s).toInt()
        HudCard.draw(g, panelX, panelY) {
            HudCard.card(width, height, s)
            rows.forEachIndexed { i, (left, right) ->
                val y = pad + i * lineH
                HudCard.text(left, pad, y, size, GRAY)
                val color = if (i == 2) (if (profit >= 0) GREEN else RED) else -1
                HudCard.text(right, width - pad - HudCard.width(right, size), y, size, color)
            }
        }
    }

    override val label = "Run Profit"
    private const val DEFAULT_X = 3
    private const val DEFAULT_Y = 120
    override var panelX = DEFAULT_X
    override var panelY = DEFAULT_Y
    override var panelScale = 1f
    override var lastWidth = 0
        private set
    override var lastHeight = 0
        private set

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val gson = Gson()
    private val hudFile by lazy { CedPaths.hud("run_profit") }

    override fun save() {
        runCatching { hudFile.parentFile?.mkdirs(); hudFile.writeText(gson.toJson(HudPosition(panelX, panelY, panelScale))) }
    }

    override fun resetPosition() { panelX = DEFAULT_X; panelY = DEFAULT_Y; panelScale = 1f }
    override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.4f, 3f) }

    init {
        addSettings(hud, kuudra, priceSide, essence, keyCost, croesus, croesusBest, fancy, historyButton)
        LowestBin.start()
        Thread(::loadNames, "CedClient-RunProfit-Names").apply { isDaemon = true }.start()
        runCatching { gson.fromJson(hudFile.readText(), HudPosition::class.java) }.getOrNull()?.let {
            panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.4f, 3f)
        }
        on<TickEvent.End> { tick() }
    }
}
