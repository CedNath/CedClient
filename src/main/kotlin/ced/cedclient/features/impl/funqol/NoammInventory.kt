package ced.cedclient.features.impl.funqol

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.mixin.accessor.AbstractContainerScreenAccessor
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.Color
import ced.cedclient.utils.Debug
import ced.cedclient.utils.cleanName
import ced.cedclient.utils.isRecombobulated
import ced.cedclient.utils.loreLines
import ced.cedclient.utils.skyblockId
import ced.cedclient.utils.skyblockUuid
import ced.cedclient.utils.stars
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW

// ------------------------------------------------------------------------------------------------ Protect Item

/**
 * Protect Item, after Noamm's: mark an item and it can't be dropped or sold by accident.
 * Mark: hover it in a menu and press the Protect Key (again to unmark), or /cc protectitem for the held item. An item is
 * remembered by its UUID, or by its SkyBlock id when it has no UUID (then every item with that id is protected).
 * Starred and recombobulated items can protect themselves. Blocked: the drop key (menu, and in-game with the item held,
 * not during a dungeon run), a click outside the window with the item on the cursor, and clicks on it in a selling menu
 * (one with a "Sell Item" hopper or "Click to buyback" items). Protected items get a tooltip line and a small P.
 */
object ProtectItem : Module("Protect Item", Category.Skyblock, "Mark items so you can't drop or sell them by accident.") {
    private val notify = BooleanSetting("Protect Notification", true, "Say so when an action is blocked.")
    private val protectKey = KeybindSetting("Protect Key", GLFW.GLFW_KEY_L, "Hover an item in a menu and press this to protect / unprotect it.")
    private val showProtected = BooleanSetting("Show Protected Items", true, "A small P on protected items in menus.")
    private val protectUuid = BooleanSetting("Protect UUID", true, "Items marked by their UUID.")
    private val protectId = BooleanSetting("Protect Skyblock ID", true, "Items marked by their SkyBlock id (all items with it).")
    private val protectStarred = BooleanSetting("Protect Starred", true, "Starred items are protected without marking them.")
    private val protectRecomb = BooleanSetting("Protect Recombobulated", true, "Recombobulated items are protected without marking them.")

    private val gson = Gson()
    private val file by lazy { CedPaths.file("protected_items.json") }
    private val uuids = LinkedHashSet<String>()
    private val ids = LinkedHashSet<String>()
    private var lastWarn = 0L

    private fun save() = runCatching {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(mapOf("uuids" to uuids.toList(), "ids" to ids.toList())))
    }

    private fun load() {
        runCatching {
            val el = JsonParser.parseString(file.readText())
            if (el is JsonArray) { // first version: one list, "id:<ID>" for ids
                el.forEach { s -> val v = s.asString; if (v.startsWith("id:")) ids += v.removePrefix("id:") else uuids += v }
            } else {
                val o = el.asJsonObject
                o.getAsJsonArray("uuids")?.forEach { uuids += it.asString }
                o.getAsJsonArray("ids")?.forEach { ids += it.asString }
            }
        }
    }

    /** Why [stack] is protected (UUID, SkyblockID, Starred, RarityUpgraded), or null. */
    fun typeOf(stack: ItemStack): String? {
        if (stack.isEmpty) return null
        val id = stack.skyblockId ?: return null
        if (protectUuid.value && stack.skyblockUuid?.let { it in uuids } == true) return "UUID"
        if (protectId.value && id in ids) return "SkyblockID"
        if (protectStarred.value && (stack.stars > 0 || stack.hoverName.string.contains("✪"))) return "Starred"
        if (protectRecomb.value && stack.isRecombobulated) return "RarityUpgraded"
        return null
    }

    fun isProtected(stack: ItemStack) = typeOf(stack) != null

    private fun toggle(stack: ItemStack) {
        val uuid = stack.skyblockUuid
        val id = stack.skyblockId
        val (set, key) = when {
            !uuid.isNullOrBlank() -> uuids to uuid
            id != null -> ids to id
            else -> { say("Item has no protectable ID/UUID."); return }
        }
        if (set.remove(key)) say("§cUnprotected §f${stack.hoverName.string}") else { set.add(key); say("§aProtected §f${stack.hoverName.string}") }
        Debug.line("Protect Item", "toggle $key, now ${uuids.size} uuids ${ids.size} ids")
        save()
    }

    private fun warn() {
        val now = System.currentTimeMillis()
        if (now - lastWarn > 1500) { lastWarn = now; if (notify.value) say("§cAction Blocked §7- protected item.") }
    }

    /** Mixin: LocalPlayer.drop. True = cancel the drop of the held item (not while a dungeon run is on). */
    @JvmStatic
    fun blocksHeldDrop(): Boolean {
        if (!isEnabled || DungeonState.inDungeon) return false
        val held = mc.player?.mainHandItem ?: return false
        if (!isProtected(held)) return false
        warn()
        return true
    }

    /** A selling menu: a "Sell Item" hopper or "Click to buyback" items among the first 54 slots. */
    private fun isSellMenu(screen: AbstractContainerScreen<*>): Boolean = screen.menu.slots.take(54).any { s ->
        val st = s.item
        !st.isEmpty && ((st.`is`(Items.HOPPER) && st.cleanName.contains("Sell Item")) || st.loreLines.any { it.contains("Click to buyback") })
    }

    fun commandTree(): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal("protectitem").executes {
            val held = mc.player?.mainHandItem
            if (held == null || held.isEmpty) say("Hold an item first.") else toggle(held)
            1
        }

    init {
        addSettings(notify, protectKey, showProtected, protectUuid, protectId, protectStarred, protectRecomb)
        load()

        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            if (!isEnabled) return@register
            // Red, right under the item's name.
            typeOf(stack)?.let {
                lines.add(minOf(1, lines.size), Component.literal("Item Protected").withColor(0xFF5555)
                    .append(Component.literal(" ($it)").withColor(ced.cedclient.features.impl.misc.Theme.hudBorderRgba and 0xFFFFFF)))
            }
        }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            ScreenKeyboardEvents.allowKeyPress(screen).register { s, event ->
                if (!isEnabled) return@register true
                val slot = hovered(s)
                if (event.key == protectKey.value && slot != null && !slot.item.isEmpty) { toggle(slot.item); return@register false }
                if (mc.options.keyDrop.matches(event) && slot != null && isProtected(slot.item)) { warn(); return@register false }
                true
            }
            ScreenMouseEvents.allowMouseClick(screen).register { s, _ ->
                if (!isEnabled) return@register true
                val slot = hovered(s)
                val cs = s as AbstractContainerScreen<*>
                // a click outside the window drops the carried stack
                if (slot == null && isProtected(cs.menu.carried)) { warn(); return@register false }
                if (slot != null && isProtected(slot.item) && isSellMenu(cs)) { warn(); return@register false }
                true
            }
            ScreenEvents.afterExtract(screen).register { s, g, _, _, _ ->
                if (!isEnabled || !showProtected.value) return@register
                val acc = s as AbstractContainerScreenAccessor
                for (slot in (s as AbstractContainerScreen<*>).menu.slots) {
                    if (!isProtected(slot.item)) continue
                    val x = acc.`ced$getLeftPos`() + slot.x
                    val y = acc.`ced$getTopPos`() + slot.y
                    g.pose().pushMatrix()
                    g.pose().translate(x.toFloat(), y.toFloat())
                    g.pose().scale(0.75f, 0.75f)
                    g.text(mc.font, "P", 0, 0, 0xFF55FF55.toInt())
                    g.pose().popMatrix()
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Slot Binding

/**
 * Slot Binding, after Noamm's: link an inventory slot to a hotbar slot, then Shift + left click either to swap them.
 * Press the Bind Key on a slot, then on a slot of the other kind (hotbar / inventory) to link them. Pressing it on a bound
 * slot removes the link (on a hotbar slot: all its links). Unlike Noamm's, many inventory slots can share one hotbar slot:
 * press the key on the hotbar slot, then on each inventory slot, then on the hotbar slot again; clicking the hotbar slot
 * cycles through its slots. Bound slots get a border and a line between the pair.
 */
object SlotBinding : Module("Slot Binding", Category.Skyblock, "Link inventory slots to hotbar slots (several to one), then Shift + click to swap.") {
    private val bindKey = KeybindSetting("Binding Key", GLFW.GLFW_KEY_R, "Press while hovering a hotbar slot and an inventory slot to link them.")
    private val showBound = BooleanSetting("Show Bound Slots", true, "Draw the links.")
    private val hoverOnly = BooleanSetting("Hover Only", false, "Only show a link while you hover one of its slots.").withDependency { showBound.value }
    private val drawBorder = BooleanSetting("Draw Border", true, "A border around bound slots.").withDependency { showBound.value }
    private val drawLine = BooleanSetting("Draw Line", true, "A line between the two slots of a link.").withDependency { showBound.value }
    private val borderColor = ColorSetting("Border Color", Color(0xC0, 0x00, 0x00, 0xAA / 255f), "Border of bound slots.")
        .apply { oldDefault = 0xFF69B4 } // the pink it started with: a saved pink moves to the new default
        .withDependency { showBound.value && drawBorder.value }
    private val lineColor = ColorSetting("Line Color", Color(255, 255, 255), "Line between bound slots.").withDependency { showBound.value && drawLine.value }

    private val gson = Gson()
    private val file by lazy { CedPaths.file("slot_bindings.json") }
    /** main inventory index (9-35) -> hotbar index (0-8); many mains can share a hotbar slot */
    private val bindings = LinkedHashMap<Int, Int>()
    private val cycle = HashMap<Int, Int>()
    private var pending: Int? = null

    private fun save() = runCatching { file.parentFile?.mkdirs(); file.writeText(gson.toJson(bindings)) }

    private fun load() {
        runCatching {
            val map: Map<String, Int> = gson.fromJson(file.readText(), object : TypeToken<Map<String, Int>>() {}.type)
            map.forEach { (k, v) -> bindings[k.toInt()] = v }
        }
    }

    private fun mainsOf(hot: Int) = bindings.filterValues { it == hot }.keys.toList()

    private fun press(slot: Slot) {
        if (slot.container !is Inventory) return
        val idx = slot.containerSlot
        if (idx !in 0..35) return
        val first = pending
        when {
            first == null -> when {
                idx >= 9 && bindings.containsKey(idx) -> { bindings.remove(idx); save(); say("Binding removed.") }
                idx < 9 && bindings.containsValue(idx) && !keyDown(GLFW.GLFW_KEY_LEFT_SHIFT) -> { bindings.entries.removeIf { it.value == idx }; save(); say("Removed all links of hotbar ${idx + 1}.") }
                else -> {
                    pending = idx
                    say(if (idx < 9) "Hotbar ${idx + 1}: press the key on each inventory slot to link, then on this slot again to finish."
                        else "Now hover the hotbar slot and press the key again.")
                }
            }
            first < 9 -> when { // adding inventory slots to one hotbar slot
                idx == first -> { pending = null; say("Done: hotbar ${first + 1} has ${mainsOf(first).size} linked slot(s).") }
                idx < 9 -> { pending = idx; say("Hotbar ${idx + 1}: press the key on each inventory slot.") }
                bindings[idx] == first -> { bindings.remove(idx); save(); say("Removed slot $idx from hotbar ${first + 1}.") }
                else -> { bindings[idx] = first; save(); say("§aLinked slot $idx to hotbar ${first + 1} §7(${mainsOf(first).size} now).") }
            }
            idx >= 9 -> { pending = idx; say("Pick a hotbar slot.") } // two inventory slots: start over from the second
            else -> { bindings[first] = idx; pending = null; save(); say("§aLinked slot $first to hotbar ${idx + 1}.") }
        }
        Debug.line("Slot Binding", "press idx=$idx pending=$pending bindings=${bindings.size}")
    }

    /** The inventory slot to swap when [slot] is shift-clicked, and the hotbar slot it swaps with. */
    private fun swapFor(slot: Slot): Pair<Int, Int>? {
        val idx = slot.containerSlot
        if (idx >= 9) return bindings[idx]?.let { idx to it }
        val mains = mainsOf(idx).ifEmpty { return null }
        val n = (cycle[idx] ?: -1) + 1
        cycle[idx] = n % mains.size
        return mains[n % mains.size] to idx
    }

    private fun line(g: GuiGraphicsExtractor, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        val steps = maxOf(Math.abs(x2 - x1), Math.abs(y2 - y1)).coerceAtLeast(1)
        for (i in 0..steps) {
            val x = x1 + (x2 - x1) * i / steps
            val y = y1 + (y2 - y1) * i / steps
            g.fill(x, y, x + 1, y + 1, color)
        }
    }

    private fun border(g: GuiGraphicsExtractor, x: Int, y: Int, c: Int) {
        g.fill(x, y, x + 16, y + 1, c); g.fill(x, y + 15, x + 16, y + 16, c)
        g.fill(x, y + 1, x + 1, y + 15, c); g.fill(x + 15, y + 1, x + 16, y + 15, c)
    }

    init {
        addSettings(bindKey, showBound, hoverOnly, drawBorder, drawLine, borderColor, lineColor)
        load()
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            ScreenKeyboardEvents.allowKeyPress(screen).register { s, event ->
                if (!isEnabled || event.key != bindKey.value) return@register true
                val slot = hovered(s) ?: return@register true
                press(slot)
                false
            }
            ScreenMouseEvents.allowMouseClick(screen).register { s, event ->
                if (!isEnabled || event.button() != 0 || !(keyDown(GLFW.GLFW_KEY_LEFT_SHIFT) || keyDown(GLFW.GLFW_KEY_RIGHT_SHIFT))) return@register true
                val slot = hovered(s) ?: return@register true
                if (slot.container !is Inventory) return@register true
                val (main, hot) = swapFor(slot) ?: return@register true
                val cs = s as AbstractContainerScreen<*>
                val target = menuSlot(cs, main) ?: return@register true
                val player = mc.player ?: return@register true
                mc.gameMode?.handleContainerInput(cs.menu.containerId, target.index, hot, ContainerInput.SWAP, player)
                Debug.line("Slot Binding", "swap main=$main hotbar=$hot")
                false
            }
            ScreenEvents.afterExtract(screen).register { s, g, mx, my, _ ->
                if (!isEnabled) return@register
                val acc = s as AbstractContainerScreenAccessor
                val cs = s as AbstractContainerScreen<*>
                fun px(slot: Slot) = acc.`ced$getLeftPos`() + slot.x
                fun py(slot: Slot) = acc.`ced$getTopPos`() + slot.y
                val hov = hovered(s)?.takeIf { it.container is Inventory }?.containerSlot
                if (showBound.value) for ((main, hot) in bindings) {
                    if (hoverOnly.value && hov != main && hov != hot) continue
                    val a = menuSlot(cs, main) ?: continue
                    val b = menuSlot(cs, hot) ?: continue
                    if (drawLine.value) line(g, px(a) + 8, py(a) + 8, px(b) + 8, py(b) + 8, lineColor.value.rgba)
                    if (drawBorder.value) { border(g, px(a), py(a), borderColor.value.rgba); border(g, px(b), py(b), borderColor.value.rgba) }
                }
                // a pending link follows the cursor
                pending?.let { p -> menuSlot(cs, p)?.let { slot ->
                    line(g, px(slot) + 8, py(slot) + 8, mx, my, lineColor.value.rgba)
                    border(g, px(slot), py(slot), borderColor.value.rgba)
                } }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Loadout / Wardrobe keybinds

/**
 * Shared by the two keybind modules: while a menu whose title matches [titleOk] is open, a key press equips the slot it is
 * bound to (when that slot has something to equip), with a 300 ms gap between equips like Noamm's. The press is swallowed.
 */
private class MenuKeys(
    private val module: Module,
    private val titleOk: (String) -> Boolean,
    private val slots: List<Int>,
    private val keys: List<KeybindSetting>,
    private val useHotbar: BooleanSetting,
    private val canEquip: (Slot) -> Boolean,
    private val closeAfter: BooleanSetting,
    private val tag: String,
) {
    private var lastClick = 0L

    fun register() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            ScreenKeyboardEvents.allowKeyPress(screen).register { s, event ->
                val cs = s as AbstractContainerScreen<*>
                if (!module.isEnabled || !titleOk(cs.title.string)) return@register true
                if (event.key == GLFW.GLFW_KEY_ESCAPE || mc.options.keyInventory.matches(event)) return@register true
                val idx = if (useHotbar.value) mc.options.keyHotbarSlots.indexOfFirst { it.matches(event) }.takeIf { it in slots.indices }
                else keys.indexOfFirst { it.value == event.key && event.key > 0 }.takeIf { it >= 0 }
                if (idx == null) return@register true
                val now = System.currentTimeMillis()
                if (now - lastClick < 300) return@register false
                val slot = cs.menu.slots.getOrNull(slots[idx])
                if (slot == null || slot.item.isEmpty) return@register false
                if (!canEquip(slot)) return@register false
                lastClick = now
                val player = mc.player ?: return@register false
                mc.gameMode?.handleContainerInput(cs.menu.containerId, slots[idx], 0, ContainerInput.PICKUP, player)
                Debug.line(tag, "equip ${idx + 1} (menu slot ${slots[idx]})")
                if (closeAfter.value) player.closeContainer()
                false
            }
        }
    }
}

/**
 * Loadout Keybinds, after Noamm's: in the SkyBlock Loadouts menu ("(1/5) Loadouts"), a key equips that loadout: 12 keys for
 * the 12 slots (default 1-9, 0, -, =), or the hotbar number keys. Only slots that say "Left-click to equip!" are clicked.
 * Block Barrier Click stops a stray click on the barrier slot.
 */
object LoadoutKeybinds : Module("Loadout Keybinds", Category.Skyblock, "Keys that equip your SkyBlock loadouts in the Loadouts menu.") {
    private val blockBarrier = BooleanSetting("Block Barrier Click", true, "Clicking the barrier slot of the menu does nothing.")
    private val useHotbar = BooleanSetting("Use Hotbar Binds", false, "Use the hotbar number keys instead of the keys below.")
    private val closeAfter = BooleanSetting("Close After Use", false, "Close the menu once a loadout is equipped.")
    private val defaults = listOf(GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_4, GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_6,
        GLFW.GLFW_KEY_7, GLFW.GLFW_KEY_8, GLFW.GLFW_KEY_9, GLFW.GLFW_KEY_0, GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_EQUAL)
    private val keys = defaults.mapIndexed { i, k -> KeybindSetting("Loadout Slot ${i + 1}", k, "Equips loadout ${i + 1}.").withDependency { !useHotbar.value } }
    private val TITLE = Regex("""^\(\d+/\d+\) Loadouts$""")
    private val SLOTS = listOf(14, 15, 16, 23, 24, 25, 32, 33, 34, 41, 42, 43)

    init {
        addSettings(blockBarrier, useHotbar, closeAfter, *keys.toTypedArray())
        MenuKeys(this, { TITLE.matches(it.trim()) }, SLOTS, keys, useHotbar, { it.item.loreLines.any { l -> l.contains("Left-click to equip!") } }, closeAfter, "Loadout Keybinds").register()
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            ScreenMouseEvents.allowMouseClick(screen).register { s, _ ->
                !(isEnabled && blockBarrier.value && TITLE.matches((s as AbstractContainerScreen<*>).title.string.trim()) && hovered(s)?.index == 49)
            }
        }
    }
}

/**
 * Wardrobe Keybinds, after Noamm's: in the Wardrobe menu ("(1/3) Armor Sets", or the older "Wardrobe (1/2)"), keys 1-9 (or
 * the hotbar number keys) equip that armor set: the bottom row, menu slots 36-44, of the page that is open. Prevent Unequip
 * does nothing on the set you already wear (its slot shows a lime dye), so a key never takes it off.
 */
object WardrobeKeybinds : Module("Wardrobe Keybinds", Category.Skyblock, "Keys that equip armor sets 1-9 in the Wardrobe menu.") {
    private val useHotbar = BooleanSetting("Use Hotbar Binds", false, "Use the hotbar number keys instead of the keys below.")
    private val preventUnequip = BooleanSetting("Prevent Unequip", true, "A key for the set you already wear does nothing.")
    private val closeAfter = BooleanSetting("Close After Use", false, "Close the menu once a set is equipped.")
    private val keys = (0 until 9).map { KeybindSetting("Wardrobe Slot ${it + 1}", GLFW.GLFW_KEY_1 + it, "Equips armor set ${it + 1}.").withDependency { !useHotbar.value } }
    private val TITLE = Regex("""^\(\d+/\d+\) Armor Sets$|^Wardrobe \(\d+/\d+\)$""")
    private val SLOTS = (36..44).toList()

    init {
        addSettings(useHotbar, preventUnequip, closeAfter, *keys.toTypedArray())
        MenuKeys(this, { TITLE.matches(it.trim()) }, SLOTS, keys, useHotbar, { !(preventUnequip.value && it.item.`is`(Items.LIME_DYE)) }, closeAfter, "Wardrobe Keybinds").register()
    }
}
