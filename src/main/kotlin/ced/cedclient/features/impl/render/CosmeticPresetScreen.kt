package ced.cedclient.features.impl.render

import ced.cedclient.utils.Keys
import ced.cedclient.render.cosmetic.CosmeticBackup
import ced.cedclient.render.cosmetic.CosmeticBackup.ImportData
import ced.cedclient.render.cosmetic.CosmeticRooms
import ced.cedclient.render.cosmetic.CosmeticStore
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.utils.Color.Companion.withAlpha
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.TextInputHandler
import ced.cedclient.utils.ui.clickGuiScale
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.ceil
import kotlin.math.sign
import ced.cedclient.utils.ui.mouseX as cedMouseX
import ced.cedclient.utils.ui.mouseY as cedMouseY

/**
 * Cosmetic Blocks preset manager. Left: every location that can hold presets -- each dungeon boss floor (F1-F7,
 * M1-M7), the dungeon room layer and every SkyBlock island. Right: that location's presets (click to make active,
 * copy / move them to another location, delete) or, for Dungeon Rooms, the rooms with blocks.
 *
 * "Import..." switches to the import view: pick a source (clipboard, cosmetic_exports, or the cb mod's own configs
 * in config/cb), then pick where it goes -- any boss floor or island for world-coordinate data, Dungeon Rooms for
 * room data -- plus the preset name and merge / replace.
 *
 * Opened with the "Preset Switcher Key" or /cc cosmetic presets.
 */
class CosmeticPresetScreen : Screen(Component.literal("Cosmetic Presets")) {

    private enum class Mode { BROWSE, IMPORT }

    /** A left-column row: a section header, a preset scope, or the dungeon room layer. */
    private sealed interface Side {
        data class Header(val text: String) : Side
        data class Scope(val key: String) : Side
        data object Rooms : Side
    }

    /** One clickable area registered while drawing; checked in [mouseClicked] (last registered wins). */
    private class Hit(val x: Float, val y: Float, val w: Float, val h: Float, val left: () -> Unit, val right: (() -> Unit)?)

    private var mode = Mode.BROWSE
    private var status = ""
    private val hits = ArrayList<Hit>()

    // ---- browse ----
    private val sideItems: List<Side> = buildList {
        add(Side.Header("Dungeon Bosses"))
        CosmeticStore.FLOORS.forEach { add(Side.Scope(it)) }
        add(Side.Header("Dungeon"))
        add(Side.Rooms)
        add(Side.Header("Islands"))
        CosmeticStore.islandNames().forEach { add(Side.Scope(it)) }
    }
    private var selected: Side = Side.Scope(CosmeticStore.islandKey)
    private var sideScroll = 0f
    private var paneScroll = 0f
    private var newName = ""
    private val newInput = TextInputHandler(textProvider = { newName }, textSetter = { newName = it.take(32) })
    /** Preset waiting for a destination: (scope, name, move). */
    private var transfer: Triple<String, String, Boolean>? = null
    private var confirmDelete: String? = null

    // ---- import ----
    private var sources: List<CosmeticBackup.Source> = emptyList()
    private var sourceIndex = -1
    private var data: ImportData? = null
    private var target: String = CosmeticStore.islandKey
    private var importName = ""
    private val importInput = TextInputHandler(textProvider = { importName }, textSetter = { importName = it.take(32) })
    private var replace = false
    private var activate = true

    // ---- layout (canvas space, set every frame) ----
    private var x = 0f
    private var y = 0f
    private var height = 0f
    private val sideX get() = x + 10f
    private val listTop get() = y + 40f
    private val paneX get() = x + SIDE_W + 20f
    private val paneW get() = WIDTH - SIDE_W - 30f
    private val footerY get() = y + height - BUTTON_H - 28f
    private val listBottom get() = footerY - 10f

    private val font get() = NVGRenderer.defaultFont

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        NVGSpecialRenderer.draw(context, 0, 0, context.guiWidth(), context.guiHeight(), renderContent = {
            val mx = cedMouseX / clickGuiScale
            val my = cedMouseY / clickGuiScale
            NVGRenderer.scale(clickGuiScale, clickGuiScale)
            val cw = NVGRenderer.canvasWidth / clickGuiScale
            val ch = NVGRenderer.canvasHeight / clickGuiScale

            // Same open feel as the ClickGUI: the dim fades in, the window fades in with a small pop.
            val t = (System.nanoTime() - openedAt) / 1_000_000f
            val dim = easeOutCubic(t / 160f)
            val p = easeOutCubic(t / 180f)
            NVGRenderer.rect(0f, 0f, cw, ch, Colors.BLACK.withAlpha(0.35f * dim).rgba)
            height = HEIGHT.coerceAtMost(ch - 20f)
            x = (cw - WIDTH) / 2f
            y = (ch - height) / 2f
            hits.clear()
            NVGRenderer.push()
            NVGRenderer.globalAlpha(p)
            val s = 0.96f + 0.04f * p
            NVGRenderer.translate(cw / 2f, ch / 2f)
            NVGRenderer.scale(s, s)
            NVGRenderer.translate(-cw / 2f, -ch / 2f)
            draw(mx, my)
            NVGRenderer.pop()
        })
        super.extractRenderState(context, mouseX, mouseY, deltaTicks)
    }

    private fun draw(mx: Float, my: Float) {
        NVGRenderer.dropShadow(x, y, WIDTH, height, 10f, 3f, 5f)
        NVGRenderer.rect(x, y, WIDTH, height, Colors.gray26.rgba, 5f)
        NVGRenderer.hollowRect(x, y, WIDTH, height, 1.5f, ClickGUI.clickGUIColor.rgba, 5f)
        NVGRenderer.line(x + SIDE_W + 14f, listTop - 4f, x + SIDE_W + 14f, listBottom, 1f, Colors.gray38.rgba)

        val title = if (mode == Mode.BROWSE) "Cosmetic Presets" else "Import Cosmetic Blocks"
        NVGRenderer.text(title, x + WIDTH / 2f - NVGRenderer.textWidth(title, 18f, font) / 2f, y + 10f, 18f, Colors.WHITE.rgba, font)

        if (mode == Mode.BROWSE) drawBrowse(mx, my) else drawImport(mx, my)

        val hint = status.ifEmpty {
            when {
                mode == Mode.IMPORT -> "Pick a source on the left, then where it goes - Esc to go back"
                transfer != null -> "Click a location on the left to ${if (transfer!!.third) "move" else "copy"} '${transfer!!.second}' there - Esc cancels"
                else -> "Click a preset to make it active - you are on ${CosmeticStore.scopeLabel(CosmeticStore.islandKey)}"
            }
        }
        NVGRenderer.text(NVGRenderer.truncate(hint, WIDTH - 24f, 13f, font), x + 12f, footerY + BUTTON_H + 8f, 13f, Colors.MINECRAFT_GRAY.rgba, font)
    }

    // ------------------------------------------------------------------
    // Browse
    // ------------------------------------------------------------------

    private fun drawBrowse(mx: Float, my: Float) {
        drawSide(mx, my)
        when (val sel = selected) {
            is Side.Scope -> drawPresets(sel.key, mx, my)
            Side.Rooms -> drawRooms(mx, my)
            is Side.Header -> {}
        }
        val labels = listOf<Pair<String, () -> Unit>>(
            "Import..." to ::openImport,
            "Export File" to { status = CosmeticBlocks.exportConfigToFile(null) },
            "Copy All" to { status = CosmeticBlocks.copyConfig() },
            "Paste Config" to { status = CosmeticBlocks.pasteConfig(replace = false) },
        )
        val bw = (WIDTH - 24f - GAP * (labels.size - 1)) / labels.size
        labels.forEachIndexed { i, (label, action) ->
            button(label, x + 12f + i * (bw + GAP), footerY, bw, BUTTON_H, mx, my, action = action)
        }
    }

    private fun drawSide(mx: Float, my: Float) {
        val top = listTop
        val h = listBottom - top
        val here = CosmeticStore.islandKey
        sideScroll = sideScroll.coerceIn((h - sideItems.size * SIDE_ROW).coerceAtMost(0f), 0f)
        NVGRenderer.pushScissor(sideX, top, SIDE_W, h)
        sideItems.forEachIndexed { i, item ->
            val ry = top + sideScroll + i * SIDE_ROW
            if (ry + SIDE_ROW < top || ry > top + h) return@forEachIndexed
            when (item) {
                is Side.Header -> NVGRenderer.text(item.text.uppercase(), sideX + 4f, ry + 7f, 11f, ClickGUI.clickGUIColor.rgba, font)
                else -> {
                    val isSel = item == selected
                    val hov = my in top..(top + h) && hovered(sideX, ry, SIDE_W, SIDE_ROW - 2f, mx, my)
                    val canDrop = transfer != null && item is Side.Scope
                    when {
                        isSel -> NVGRenderer.rect(sideX, ry, SIDE_W, SIDE_ROW - 2f, ClickGUI.clickGUIColor.rgba, 4f)
                        hov -> NVGRenderer.rect(sideX, ry, SIDE_W, SIDE_ROW - 2f, Colors.gray38.rgba, 4f)
                        canDrop -> NVGRenderer.hollowRect(sideX, ry, SIDE_W, SIDE_ROW - 2f, 1f, ClickGUI.clickGUIColor.rgba, 4f)
                    }
                    val (label, count, isHere) = when (item) {
                        is Side.Scope -> Triple(CosmeticStore.scopeLabel(item.key), CosmeticStore.scopeSize(item.key), item.key == here)
                        else -> Triple("Dungeon Rooms", CosmeticRooms.listRooms().sumOf { it.second }, CosmeticRooms.currentRoom != null)
                    }
                    if (isHere) NVGRenderer.circle(sideX + 7f, ry + SIDE_ROW / 2f - 1f, 2.5f, Colors.MINECRAFT_GREEN.rgba)
                    val countText = if (count > 0) compact(count) else ""
                    val cwid = NVGRenderer.textWidth(countText, 11f, font)
                    NVGRenderer.text(NVGRenderer.truncate(label, SIDE_W - 26f - cwid, 13f, font), sideX + 14f, ry + 4f, 13f, Colors.WHITE.rgba, font)
                    NVGRenderer.text(countText, sideX + SIDE_W - 6f - cwid, ry + 5f, 11f, Colors.MINECRAFT_GRAY.rgba, font)
                    clipHit(sideX, ry, SIDE_W, SIDE_ROW - 2f, top, h, left = { clickSide(item) })
                }
            }
        }
        NVGRenderer.popScissor()
    }

    private fun clickSide(item: Side) {
        val t = transfer
        if (t != null && item is Side.Scope) {
            val (src, name, move) = t
            transfer = null
            val n = CosmeticStore.transferPreset(src, name, item.key, move)
            status = if (n == null) "§cCould not ${if (move) "move" else "copy"} '$name' there"
            else "${if (move) "Moved" else "Copied"} '$name' to ${CosmeticStore.scopeLabel(item.key)} as '$n'"
            selected = item
            return
        }
        transfer = null
        confirmDelete = null
        selected = item
        paneScroll = 0f
    }

    private fun drawPresets(scope: String, mx: Float, my: Float) {
        val here = scope == CosmeticStore.islandKey
        val active = CosmeticStore.activePresetOf(scope)
        val heading = CosmeticStore.scopeLabel(scope) + if (here) "  (you are here)" else ""
        NVGRenderer.text(NVGRenderer.truncate(heading, paneW, 15f, font), paneX, listTop, 15f, Colors.WHITE.rgba, font)

        val presets = (CosmeticStore.listPresets(scope) + active).distinct().sorted()
        val top = listTop + 22f
        val inputY = listBottom - INPUT_H
        val h = inputY - 8f - top
        paneScroll = paneScroll.coerceIn((h - presets.size * ROW_H).coerceAtMost(0f), 0f)
        NVGRenderer.pushScissor(paneX, top, paneW, h)
        presets.forEachIndexed { i, name ->
            val ry = top + paneScroll + i * ROW_H
            if (ry + ROW_H < top || ry > top + h) return@forEachIndexed
            val isActive = name == active
            val hov = my in top..(top + h) && hovered(paneX, ry, paneW, ROW_H - 2f, mx, my)
            when {
                isActive -> NVGRenderer.rect(paneX, ry, paneW, ROW_H - 2f, ClickGUI.clickGUIColor.withAlpha(0.6f).rgba, 4f)
                hov -> NVGRenderer.rect(paneX, ry, paneW, ROW_H - 2f, Colors.gray38.rgba, 4f)
            }
            clipHit(paneX, ry, paneW, ROW_H - 2f, top, h, left = {
                confirmDelete = null
                if (CosmeticStore.setActive(scope, name)) status = "'$name' is now active on ${CosmeticStore.scopeLabel(scope)}"
            })
            NVGRenderer.text(if (i < 9 && here) "${i + 1}" else "", paneX + 6f, ry + 4f, 14f, Colors.MINECRAFT_GRAY.rgba, font)
            val count = "${CosmeticStore.presetSize(name, scope)} blocks" + if (isActive) " - active" else ""
            val actionsW = 4 * SMALL_W + 3 * 4f
            val countW = NVGRenderer.textWidth(count, 12f, font)
            NVGRenderer.text(NVGRenderer.truncate(name, paneW - 30f - countW - actionsW - 16f, 14f, font), paneX + 22f, ry + 4f, 14f, Colors.WHITE.rgba, font)
            val ax = paneX + paneW - 4f - actionsW
            NVGRenderer.text(count, ax - 8f - countW, ry + 5f, 12f, Colors.MINECRAFT_GRAY.rgba, font)
            if (hov || confirmDelete == "$scope/$name") {
                val by = ry + 2f
                val bh = ROW_H - 6f
                smallButton("To cb", ax, by, bh, mx, my, top, h) { status = exportToCb(scope, name) }
                smallButton("Copy", ax + SMALL_W + 4f, by, bh, mx, my, top, h) { transfer = Triple(scope, name, false); status = "" }
                smallButton("Move", ax + 2 * (SMALL_W + 4f), by, bh, mx, my, top, h) { transfer = Triple(scope, name, true); status = "" }
                val del = confirmDelete == "$scope/$name"
                smallButton(if (del) "Sure?" else "Delete", ax + 3 * (SMALL_W + 4f), by, bh, mx, my, top, h, danger = true) {
                    if (del) {
                        confirmDelete = null
                        status = if (CosmeticStore.deletePreset(name, scope)) "Deleted '$name'" else "'$name' has no blocks to delete"
                    } else confirmDelete = "$scope/$name"
                }
            }
        }
        NVGRenderer.popScissor()

        // New preset in this location
        val fieldW = paneW - NEW_W - 6f
        NVGRenderer.rect(paneX, inputY, fieldW, INPUT_H, Colors.gray38.rgba, 4f)
        if (newName.isEmpty()) NVGRenderer.text("New preset name...", paneX + 6f, inputY + 5f, 14f, Colors.MINECRAFT_GRAY.rgba, font)
        newInput.x = paneX
        newInput.y = inputY + 1f
        newInput.width = fieldW
        newInput.height = INPUT_H - 2f
        newInput.draw(mx, my)
        button("New", paneX + paneW - NEW_W, inputY, NEW_W, INPUT_H, mx, my, action = ::createPreset)
    }

    private fun drawRooms(mx: Float, my: Float) {
        val current = CosmeticRooms.currentRoom?.data?.name
        NVGRenderer.text("Dungeon Rooms" + (current?.let { "  (in: $it)" } ?: ""), paneX, listTop, 15f, Colors.WHITE.rgba, font)
        val rooms = CosmeticRooms.listRooms().sortedBy { it.first }
        val top = listTop + 22f
        val h = listBottom - top
        if (rooms.isEmpty()) {
            NVGRenderer.text("No room blocks yet - edit inside a scanned room, or import a cb rooms file.", paneX, top + 4f, 12f, Colors.MINECRAFT_GRAY.rgba, font)
            return
        }
        paneScroll = paneScroll.coerceIn((h - rooms.size * ROW_H).coerceAtMost(0f), 0f)
        NVGRenderer.pushScissor(paneX, top, paneW, h)
        rooms.forEachIndexed { i, (name, count) ->
            val ry = top + paneScroll + i * ROW_H
            if (ry + ROW_H < top || ry > top + h) return@forEachIndexed
            val hov = my in top..(top + h) && hovered(paneX, ry, paneW, ROW_H - 2f, mx, my)
            when {
                name == current -> NVGRenderer.rect(paneX, ry, paneW, ROW_H - 2f, ClickGUI.clickGUIColor.withAlpha(0.6f).rgba, 4f)
                hov -> NVGRenderer.rect(paneX, ry, paneW, ROW_H - 2f, Colors.gray38.rgba, 4f)
            }
            val countText = "$count blocks"
            val countW = NVGRenderer.textWidth(countText, 12f, font)
            NVGRenderer.text(NVGRenderer.truncate(name, paneW - countW - SMALL_W - 30f, 14f, font), paneX + 8f, ry + 4f, 14f, Colors.WHITE.rgba, font)
            val ax = paneX + paneW - 4f - SMALL_W
            NVGRenderer.text(countText, ax - 8f - countW, ry + 5f, 12f, Colors.MINECRAFT_GRAY.rgba, font)
            val key = "rooms/$name"
            if (hov || confirmDelete == key) {
                val del = confirmDelete == key
                smallButton(if (del) "Sure?" else "Delete", ax, ry + 2f, ROW_H - 6f, mx, my, top, h, danger = true) {
                    if (del) {
                        confirmDelete = null
                        status = "Cleared ${CosmeticRooms.clearRoom(name)} block(s) from $name"
                    } else confirmDelete = key
                }
            }
        }
        NVGRenderer.popScissor()
    }

    /** Saves the preset as a new cb mod config (config/cb/cb) and copies it to the clipboard. */
    private fun exportToCb(scope: String, name: String): String {
        val entries = CosmeticStore.presetEntries(scope, name)
        if (entries.isNullOrEmpty()) return "'$name' has no blocks to export"
        return try {
            val file = CosmeticBackup.exportToCb("${scope}_$name", entries)
            Minecraft.getInstance().keyboardHandler.clipboard = CosmeticBackup.toCbJson(entries).toString()
            "Exported ${entries.size} blocks to config/cb/cb/${file.name} (also copied)"
        } catch (e: Exception) {
            "§cExport failed: ${e.message}"
        }
    }

    private fun createPreset() {
        val scope = (selected as? Side.Scope)?.key ?: return
        val name = CosmeticStore.sanitize(newName)
        if (name.isEmpty()) return
        status = if (CosmeticStore.createPreset(name, scope)) {
            CosmeticStore.setActive(scope, name)
            newName = ""
            "Created '$name' on ${CosmeticStore.scopeLabel(scope)} (now active there)"
        } else "Preset '$name' already exists there"
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    private fun openImport() {
        mode = Mode.IMPORT
        status = ""
        transfer = null
        confirmDelete = null
        sources = listOf(CosmeticBackup.Source("Clipboard", "imported") { Minecraft.getInstance().keyboardHandler.clipboard }) + CosmeticBackup.sources()
        sourceIndex = -1
        data = null
        target = (selected as? Side.Scope)?.key ?: CosmeticStore.islandKey
        paneScroll = 0f
        sideScroll = 0f
    }

    private fun loadSource(i: Int) {
        val src = sources.getOrNull(i) ?: return
        sourceIndex = i
        val text = try { src.read() } catch (e: Exception) { null }
        data = text?.let { CosmeticBackup.detect(it) }
        status = when (val d = data) {
            null -> "§c${src.label} is not something Cosmetic Blocks can import"
            is ImportData.Rooms -> "Found ${d.roomCount} room(s), ${d.blocks} block(s)"
            else -> "Found ${d.description}: ${d.blocks} block(s)"
        }
        importName = CosmeticStore.sanitize(src.defaultName).ifEmpty { "imported" }
        replace = false
        activate = true
    }

    private fun drawImport(mx: Float, my: Float) {
        // Sources
        val top = listTop
        val h = listBottom - top
        sideScroll = sideScroll.coerceIn((h - sources.size * SIDE_ROW).coerceAtMost(0f), 0f)
        NVGRenderer.pushScissor(sideX, top, SIDE_W, h)
        sources.forEachIndexed { i, src ->
            val ry = top + sideScroll + i * SIDE_ROW
            if (ry + SIDE_ROW < top || ry > top + h) return@forEachIndexed
            val hov = my in top..(top + h) && hovered(sideX, ry, SIDE_W, SIDE_ROW - 2f, mx, my)
            when {
                i == sourceIndex -> NVGRenderer.rect(sideX, ry, SIDE_W, SIDE_ROW - 2f, ClickGUI.clickGUIColor.rgba, 4f)
                hov -> NVGRenderer.rect(sideX, ry, SIDE_W, SIDE_ROW - 2f, Colors.gray38.rgba, 4f)
            }
            val color = if (src.label.startsWith("cb:")) Colors.MINECRAFT_AQUA else Colors.WHITE
            NVGRenderer.text(NVGRenderer.truncate(src.label, SIDE_W - 12f, 13f, font), sideX + 6f, ry + 4f, 13f, color.rgba, font)
            clipHit(sideX, ry, SIDE_W, SIDE_ROW - 2f, top, h, left = { loadSource(i) })
        }
        NVGRenderer.popScissor()

        val d = data
        var cy = listTop
        if (d == null) {
            NVGRenderer.text("Pick a source on the left.", paneX, cy, 14f, Colors.WHITE.rgba, font)
            NVGRenderer.text("cb configs are read straight from config/cb (blue).", paneX, cy + 20f, 12f, Colors.MINECRAFT_GRAY.rgba, font)
            NVGRenderer.text("Exports from cosmetic_exports and the clipboard work too.", paneX, cy + 36f, 12f, Colors.MINECRAFT_GRAY.rgba, font)
        } else {
            val info = when (d) {
                is ImportData.Rooms -> "${d.roomCount} dungeon room(s), ${d.blocks} blocks"
                else -> "${d.description}, ${d.blocks} blocks"
            }
            NVGRenderer.text(NVGRenderer.truncate("${sources[sourceIndex].label}: $info", paneW, 14f, font), paneX, cy, 14f, Colors.WHITE.rgba, font)
            cy += 24f
            cy = when (d) {
                is ImportData.Bundle -> drawBundleOptions(cy, mx, my)
                else -> drawTargets(d, cy, mx, my)
            }
        }

        // Footer
        val bw = (WIDTH - 24f - GAP) / 2f
        button("Back", x + 12f, footerY, bw, BUTTON_H, mx, my) { mode = Mode.BROWSE; status = ""; sideScroll = 0f; paneScroll = 0f }
        button("Import", x + 12f + bw + GAP, footerY, bw, BUTTON_H, mx, my, enabled = d != null && d.blocks > 0, action = ::doImport)
    }

    private fun drawBundleOptions(startY: Float, mx: Float, my: Float): Float {
        var cy = startY
        NVGRenderer.text("A whole CedClient config: every preset on every location,", paneX, cy, 12f, Colors.MINECRAFT_GRAY.rgba, font)
        NVGRenderer.text("dungeon rooms, remaps, favourites and settings.", paneX, cy + 15f, 12f, Colors.MINECRAFT_GRAY.rgba, font)
        cy += 40f
        cy = modeToggle(cy, mx, my, "Merge into my config", "Replace my config")
        NVGRenderer.text("Your current config is backed up to cosmetic_exports first.", paneX, cy, 12f, Colors.MINECRAFT_GRAY.rgba, font)
        return cy + 18f
    }

    private fun drawTargets(d: ImportData, startY: Float, mx: Float, my: Float): Float {
        val rooms = d is ImportData.Rooms
        var cy = startY
        NVGRenderer.text("WHERE IT GOES", paneX, cy, 11f, ClickGUI.clickGUIColor.rgba, font)
        cy += 16f

        // Boss floors: F1-F7 on one row, M1-M7 on the next
        val floorW = (paneW - 6 * CHIP_GAP) / 7f
        CosmeticStore.FLOORS.forEachIndexed { i, f ->
            val cx = paneX + (i % 7) * (floorW + CHIP_GAP)
            val ry = cy + (i / 7) * (CHIP_H + CHIP_GAP)
            chip(f.uppercase(), cx, ry, floorW, mx, my, selected = !rooms && target == f, enabled = !rooms) { target = f }
        }
        cy += 2 * (CHIP_H + CHIP_GAP) + 4f

        // Dungeon rooms
        val roomsLabel = if (rooms) "Dungeon Rooms" else "Dungeon Rooms - needs a rooms file (e.g. cb: rooms), not world coordinates"
        chip(roomsLabel, paneX, cy, paneW, mx, my, selected = rooms, enabled = rooms) {}
        cy += CHIP_H + CHIP_GAP + 4f

        // Islands
        val islands = CosmeticStore.islandNames()
        val perRow = 5
        val islandW = (paneW - (perRow - 1) * CHIP_GAP) / perRow
        islands.forEachIndexed { i, isl ->
            val cx = paneX + (i % perRow) * (islandW + CHIP_GAP)
            val ry = cy + (i / perRow) * (CHIP_H + CHIP_GAP)
            chip(CosmeticStore.scopeLabel(isl), cx, ry, islandW, mx, my, selected = !rooms && target == isl, enabled = !rooms, here = isl == CosmeticStore.islandKey) { target = isl }
        }
        cy += ceil(islands.size / perRow.toFloat()) * (CHIP_H + CHIP_GAP) + 8f

        if (rooms) {
            cy = modeToggle(cy, mx, my, "Merge into rooms", "Replace these rooms")
            return cy
        }

        // Preset name + options
        NVGRenderer.text("Preset", paneX, cy + 5f, 13f, Colors.WHITE.rgba, font)
        val fx = paneX + 50f
        val fw = paneW * 0.45f
        NVGRenderer.rect(fx, cy, fw, INPUT_H, Colors.gray38.rgba, 4f)
        importInput.x = fx
        importInput.y = cy + 1f
        importInput.width = fw
        importInput.height = INPUT_H - 2f
        importInput.draw(mx, my)
        val existing = CosmeticStore.presetSize(CosmeticStore.sanitize(importName), target)
        val note = if (existing > 0) "exists ($existing blocks)" else "new preset"
        NVGRenderer.text(note, fx + fw + 8f, cy + 6f, 12f, (if (existing > 0) Colors.MINECRAFT_YELLOW else Colors.MINECRAFT_GRAY).rgba, font)
        cy += INPUT_H + 8f
        cy = modeToggle(cy, mx, my, "Merge", "Replace preset")
        chip(if (activate) "Make active: on" else "Make active: off", paneX, cy, paneW / 2f - CHIP_GAP / 2f, mx, my, selected = activate) { activate = !activate }
        return cy + CHIP_H + 6f
    }

    private fun modeToggle(cy: Float, mx: Float, my: Float, mergeLabel: String, replaceLabel: String): Float {
        val w = paneW / 2f - CHIP_GAP / 2f
        chip(mergeLabel, paneX, cy, w, mx, my, selected = !replace) { replace = false }
        chip(replaceLabel, paneX + w + CHIP_GAP, cy, w, mx, my, selected = replace, danger = true) { replace = true }
        return cy + CHIP_H + 6f
    }

    private fun doImport() {
        val d = data ?: return
        status = try {
            when (d) {
                is ImportData.Bundle -> CosmeticBlocks.importBundle(d.json, replace)
                is ImportData.Rooms -> {
                    val n = CosmeticRooms.importRooms(d.rooms, replace)
                    selected = Side.Rooms
                    "Imported $n block(s) into ${d.roomCount} dungeon room(s)"
                }
                is ImportData.World -> {
                    val name = CosmeticStore.sanitize(importName).ifEmpty { "imported" }
                    val n = CosmeticStore.importPreset(target, name, d.entries, replace, activate)
                    selected = Side.Scope(target)
                    "Imported $n block(s) into ${CosmeticStore.scopeLabel(target)} / '$name'" + if (activate) " (active)" else ""
                }
            }
        } catch (e: Exception) {
            "§cImport failed: ${e.message}"
        }
        mode = Mode.BROWSE
        sideScroll = 0f
        paneScroll = 0f
    }

    // ------------------------------------------------------------------
    // Widgets
    // ------------------------------------------------------------------

    private fun button(
        label: String, bx: Float, by: Float, bw: Float, bh: Float, mx: Float, my: Float,
        enabled: Boolean = true, action: () -> Unit,
    ) {
        val color = when {
            !enabled -> Colors.gray38.withAlpha(0.5f)
            hovered(bx, by, bw, bh, mx, my) -> ClickGUI.clickGUIColor
            else -> Colors.gray38
        }
        NVGRenderer.rect(bx, by, bw, bh, color.rgba, 4f)
        val text = NVGRenderer.truncate(label, bw - 8f, 14f, font)
        val textColor = if (enabled) Colors.WHITE else Colors.MINECRAFT_DARK_GRAY
        NVGRenderer.text(text, bx + bw / 2f - NVGRenderer.textWidth(text, 14f, font) / 2f, by + (bh - 14f) / 2f, 14f, textColor.rgba, font)
        if (enabled) hits += Hit(bx, by, bw, bh, action, null)
    }

    private fun smallButton(
        label: String, bx: Float, by: Float, bh: Float, mx: Float, my: Float, clipTop: Float, clipH: Float,
        danger: Boolean = false, action: () -> Unit,
    ) {
        val hov = hovered(bx, by, SMALL_W, bh, mx, my)
        val color = when {
            hov && danger -> Colors.MINECRAFT_RED.withAlpha(0.8f)
            hov -> ClickGUI.clickGUIColor
            else -> Colors.gray26
        }
        NVGRenderer.rect(bx, by, SMALL_W, bh, color.rgba, 3f)
        NVGRenderer.text(label, bx + SMALL_W / 2f - NVGRenderer.textWidth(label, 12f, font) / 2f, by + (bh - 12f) / 2f, 12f, Colors.WHITE.rgba, font)
        clipHit(bx, by, SMALL_W, bh, clipTop, clipH, left = action)
    }

    private fun chip(
        label: String, cx: Float, cy: Float, w: Float, mx: Float, my: Float,
        selected: Boolean = false, enabled: Boolean = true, danger: Boolean = false, here: Boolean = false, action: () -> Unit,
    ) {
        val hov = enabled && hovered(cx, cy, w, CHIP_H, mx, my)
        val color = when {
            selected && danger -> Colors.MINECRAFT_RED.withAlpha(0.7f)
            selected -> ClickGUI.clickGUIColor
            hov -> Colors.gray38.withAlpha(1f)
            !enabled -> Colors.gray38.withAlpha(0.35f)
            else -> Colors.gray38
        }
        NVGRenderer.rect(cx, cy, w, CHIP_H, color.rgba, 3f)
        if (hov && !selected) NVGRenderer.hollowRect(cx, cy, w, CHIP_H, 1f, ClickGUI.clickGUIColor.rgba, 3f)
        if (here) NVGRenderer.circle(cx + 5f, cy + CHIP_H / 2f, 2f, Colors.MINECRAFT_GREEN.rgba)
        val text = NVGRenderer.truncate(label, w - (if (here) 14f else 6f), 12f, font)
        val textColor = if (enabled) Colors.WHITE else Colors.MINECRAFT_DARK_GRAY
        NVGRenderer.text(text, cx + w / 2f - NVGRenderer.textWidth(text, 12f, font) / 2f + (if (here) 3f else 0f), cy + (CHIP_H - 12f) / 2f, 12f, textColor.rgba, font)
        if (enabled) hits += Hit(cx, cy, w, CHIP_H, action, null)
    }

    /** Registers a hit area clipped to a scrolled list's visible band. */
    private fun clipHit(hx: Float, hy: Float, hw: Float, hh: Float, clipTop: Float, clipH: Float, left: () -> Unit, right: (() -> Unit)? = null) {
        val top = maxOf(hy, clipTop)
        val bottom = minOf(hy + hh, clipTop + clipH)
        if (bottom > top) hits += Hit(hx, top, hw, bottom - top, left, right)
    }

    private fun hovered(ax: Float, ay: Float, aw: Float, ah: Float, mx: Float, my: Float) = mx in ax..(ax + aw) && my in ay..(ay + ah)

    private fun compact(n: Int): String = if (n >= 10_000) "${n / 1000}k" else n.toString()

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    private val activeInput get() = if (mode == Mode.BROWSE) newInput else importInput

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val mx = cedMouseX / clickGuiScale
        val my = cedMouseY / clickGuiScale
        if (activeInput.mouseClicked(mx, my, mouseButtonEvent)) return true
        val button = mouseButtonEvent.button()
        if (button != 0 && button != 1) return true
        val hit = hits.lastOrNull { hovered(it.x, it.y, it.w, it.h, mx, my) }
        if (hit != null) {
            if (button == 0) hit.left() else hit.right?.invoke()
            return true
        }
        if (!hovered(x, y, WIDTH, height, mx, my)) onClose()
        else { transfer = null; confirmDelete = null }
        return true
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        activeInput.mouseReleased()
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val mx = cedMouseX / clickGuiScale
        val delta = (verticalAmount.sign * 18).toFloat()
        if (mx < paneX - 6f) sideScroll += delta else paneScroll += delta
        return true
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        if (keyEvent.key == Keys.ENTER) {
            if (mode == Mode.BROWSE && newName.isNotBlank()) { createPreset(); return true }
            if (mode == Mode.IMPORT && data != null) { doImport(); return true }
        }
        if (activeInput.keyPressed(keyEvent)) return true
        if (keyEvent.key == Keys.ESCAPE) {
            when {
                transfer != null -> { transfer = null; return true }
                mode == Mode.IMPORT -> { mode = Mode.BROWSE; status = ""; sideScroll = 0f; paneScroll = 0f; return true }
            }
        }
        return super.keyPressed(keyEvent)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        if (activeInput.keyTyped(characterEvent)) return true
        val c = characterEvent.codepointAsString()
        val scope = (selected as? Side.Scope)?.key
        if (mode == Mode.BROWSE && scope == CosmeticStore.islandKey && c.length == 1 && c[0] in '1'..'9') {
            val name = (CosmeticStore.listPresets(scope) + CosmeticStore.activePresetOf(scope)).distinct().sorted().getOrNull(c[0] - '1')
            if (name != null && CosmeticStore.usePreset(name)) status = "Now using '$name'"
            return true
        }
        return super.charTyped(characterEvent)
    }

    override fun isPauseScreen(): Boolean = false

    /** No vanilla blur / menu texture behind this screen; the dim above is drawn instead (same as the ClickGUI). */
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {}

    private val openedAt = System.nanoTime()

    private fun easeOutCubic(x: Float): Float {
        val inv = 1f - x.coerceIn(0f, 1f)
        return 1f - inv * inv * inv
    }

    companion object {
        private const val WIDTH = 620f
        private const val HEIGHT = 470f
        private const val SIDE_W = 170f
        private const val SIDE_ROW = 20f
        private const val ROW_H = 24f
        private const val INPUT_H = 22f
        private const val BUTTON_H = 24f
        private const val NEW_W = 60f
        private const val SMALL_W = 46f
        private const val CHIP_H = 20f
        private const val CHIP_GAP = 4f
        private const val GAP = 6f
    }
}
