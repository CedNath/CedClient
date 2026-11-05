package ced.cedclient.features.impl.funqol

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.render.FilledBox
import ced.cedclient.render.LineBox
import ced.cedclient.render.WorldText
import ced.cedclient.render.cosmetic.CosmeticRooms
import ced.cedclient.render.cosmetic.RoomRotation
import ced.cedclient.render.cosmetic.RoomTile
import ced.cedclient.render.geometry
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.Color
import ced.cedclient.utils.Debug
import ced.cedclient.utils.Keys
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Your own waypoints per dungeon room (Odin-style). With Edit Mode on, right-click a block to put a waypoint on it, right-click
 * a waypoint to remove it. They are saved the way Odin saves them: relative to the room's blue terracotta marker and turned
 * to face north (the room scanner's [RoomRotation.toNorth]), so Odin's codes and presets line up block for block.
 * Types: None, Normal, Secret, Etherwarp. Unique: Secret waypoints hide once you click that block and Etherwarp ones once you
 * land on them (Hide Done), and a type's colour is a setting, so recolouring changes every waypoint of that type at once.
 */
object DungeonWaypoints : Module(
    "Dungeon Waypoints",
    Category.Dungeons,
    "Your own waypoints per dungeon room, saved relative to the room. Edit Mode: right-click a block to add one, right-click a waypoint to remove it."
) {
    private val TYPES = listOf("None", "Normal", "Secret", "Etherwarp")

    private val edit = BooleanSetting("Edit Mode", false, "Right-click a block to place a waypoint on it, right-click a waypoint to remove it. Those clicks are cancelled.")
    private val editKey = KeybindSetting("Edit Mode Key", Keys.UNKNOWN, "Toggles Edit Mode.")
    private val type = DropdownSetting("Next Type", TYPES, "Secret", "The type of the next waypoint you place.")
    private val cycleKey = KeybindSetting("Cycle Type Key", Keys.UNKNOWN, "Switches to the next waypoint type.")
    private val noneColor = ColorSetting("None Color", Color(85, 255, 85), "Colour of None waypoints.")
    private val normalColor = ColorSetting("Normal Color", Color(255, 85, 85), "Colour of Normal waypoints.")
    private val secretColor = ColorSetting("Secret Color", Color(85, 85, 255), "Colour of Secret waypoints.")
    private val etherColor = ColorSetting("Etherwarp Color", Color(255, 170, 0), "Colour of Etherwarp waypoints.")
    private val boxMode = DropdownSetting("Box Mode", listOf("Outline", "Filled", "Both"), "Both", "What the waypoint boxes look like.")
    private val throughWalls = BooleanSetting("Through Walls", true, "Draw waypoints through walls.")
    private val labels = BooleanSetting("Type Labels", false, "Write the type above each waypoint.")
    private val hideDone = BooleanSetting("Hide Done", true, "Secret waypoints hide once you click that block, Etherwarp ones once you land on them (until you leave the world).")
    private val resetRoom = ActionSetting("Reset Current Room", "Removes every waypoint of the room you stand in.") {
        val room = cur
        if (room == null) say("§cNo room found")
        else { data.remove(room.name); persist(); say("§aRemoved the waypoints of §f${room.name}") }
    }
    private val importMode = DropdownSetting("Import Mode", listOf("Merge", "Replace"), "Merge", "Merge adds the imported waypoints to yours; Replace swaps your whole set for them.")
    private val exportAction = ActionSetting("Export Waypoints", "Copies all your waypoints as one CedClient code.") { clip(encodeCed(), "CedClient code") }
    private val exportOdinAction = ActionSetting("Export As Odin", "Copies all your waypoints in Odin's format (Odin's import takes it as it is).") { clip(encodeOdin(), "Odin code") }
    private val importAction = ActionSetting("Import Waypoints", "Reads a code from the clipboard: CedClient's, or Odin's (the base64 code or its raw JSON).") { importText(mc.keyboardHandler.clipboard, "the clipboard") }
    private val presetAction = ActionSetting("Load Default Preset", "Loads the waypoint set that comes with CedClient (uses Import Mode).") { loadDefault(false) }
    private val presetsPopup = ActionSetting("Presets", "Switch between waypoint sets: the default, ones you saved, or codes other people made (paste one, give it a name).") {
        (mc.gui.screen() as? ced.cedclient.ui.clickgui.ClickGUI)?.openPopup(ced.cedclient.ui.clickgui.WaypointPresetsPopup())
    }

    /**
     * One waypoint, room-relative. The optional parts come from an Odin import (its own colour, filled / depth choice, title and
     * the box inside the block, as offsets 0..1 [minX, minY, minZ, maxX, maxY, maxZ]); without them the type's settings apply.
     */
    private class Wp(
        val x: Int, val y: Int, val z: Int, val type: String,
        val color: Int? = null, val filled: Boolean? = null, val depth: Boolean? = null, val title: String? = null, val box: DoubleArray? = null,
    ) {
        @Transient var done = false
    }
    /** Room-relative waypoints by room name. */
    private var data = HashMap<String, MutableList<Wp>>()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    // v2: the first version kept positions in another convention, so its file is not read.
    private val saveFile by lazy { CedPaths.file("dungeon_waypoints2.json") }

    private class Cur(val name: String, val clay: BlockPos, val rotation: RoomRotation, val tiles: Set<RoomTile>) {
        fun rel(p: BlockPos): BlockPos = rotation.toNorth(p.subtract(clay.atY(0)))
        fun real(w: Wp): BlockPos = rotation.fromNorth(BlockPos(w.x, w.y, w.z)).offset(clay.x, 0, clay.z)

        /** Odin's getRealAABB: the box inside the block is turned about the block's middle, then put on the real block. */
        fun realBox(w: Wp): AABB {
            val p = real(w)
            val b = w.box ?: return AABB(p)
            fun turn(x: Double, z: Double): Pair<Double, Double> = when (rotation) { RoomRotation.NORTH -> -x to -z; RoomRotation.WEST -> -z to x; RoomRotation.EAST -> z to -x; else -> x to z }
            val (ax, az) = turn(b[0] - 0.5, b[2] - 0.5)
            val (bx, bz) = turn(b[3] - 0.5, b[5] - 0.5)
            return AABB(p.x + minOf(ax, bx) + 0.5, p.y + b[1], p.z + minOf(az, bz) + 0.5, p.x + maxOf(ax, bx) + 0.5, p.y + b[4], p.z + maxOf(az, bz) + 0.5)
        }
    }
    private var cur: Cur? = null
    private var lastTile: RoomTile? = null
    private var retry = 0
    private var lastLevel: ClientLevel? = null
    private var lastPos: Vec3? = null
    private var editWas = false
    private var cycleWas = false

    private val mc get() = Minecraft.getInstance()
    private fun say(text: String) { mc.player?.sendSystemMessage(ChatPrefix.msgFor("Dungeon Waypoints", text)) }
    private fun colorOf(t: String) = when (t) { "Normal" -> normalColor; "Secret" -> secretColor; "Etherwarp" -> etherColor; else -> noneColor }.value

    // ---- import / export: CedClient's own code ("CDW1:" + base64(gzip(json))) and Odin's ({room: [{blockPos, color, filled, depth, aabb, title, type}]}) ----

    private fun gz(text: String): String {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun ungz(b64: String): String =
        java.util.zip.GZIPInputStream(java.util.Base64.getDecoder().decode(b64.filterNot { it.isWhitespace() }).inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)

    private fun encodeCed() = "CDW1:" + gz(gson.toJson(data))

    private fun hex(c: Color) = "#%02X%02X%02X%02X".format(c.red, c.green, c.blue, Math.round(c.alphaFloat * 255))

    /** Odin wants every field (it reads color, filled, depth and aabb without checking), the type as NONE / NORMAL / SECRET / ETHERWARP. */
    private fun encodeOdin(): String {
        val root = JsonObject()
        for ((room, list) in data) {
            val arr = JsonArray()
            for (w in list) {
                val o = JsonObject()
                o.add("blockPos", JsonObject().apply { addProperty("x", w.x); addProperty("y", w.y); addProperty("z", w.z) })
                o.addProperty("color", hex(w.color?.let { Color(it) } ?: colorOf(w.type)))
                o.addProperty("filled", w.filled ?: (boxMode.value != "Outline"))
                o.addProperty("depth", w.depth ?: !throughWalls.value)
                // Odin's box is local to the block (offsets 0..1), not world coordinates.
                val b = w.box ?: doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
                o.add("aabb", JsonObject().apply {
                    addProperty("minX", b[0]); addProperty("minY", b[1]); addProperty("minZ", b[2])
                    addProperty("maxX", b[3]); addProperty("maxY", b[4]); addProperty("maxZ", b[5])
                })
                w.title?.let { o.addProperty("title", it) }
                o.addProperty("type", w.type.uppercase())
                arr.add(o)
            }
            root.add(room, arr)
        }
        return gz(root.toString())
    }

    /** CedClient code, Odin's base64 code, or raw JSON -> the JSON text; null when it is none of them. */
    private fun decode(text: String): String? {
        val t = text.trim()
        return runCatching {
            when {
                t.startsWith("CDW1:") -> ungz(t.removePrefix("CDW1:"))
                t.startsWith("{") -> t
                else -> ungz(t)
            }
        }.getOrNull()
    }

    private fun parseWaypoints(json: String): HashMap<String, MutableList<Wp>>? = runCatching {
        val out = HashMap<String, MutableList<Wp>>()
        for ((room, el) in JsonParser.parseString(json).asJsonObject.entrySet()) {
            val list = ArrayList<Wp>()
            for (w in el.asJsonArray) {
                val o = w.asJsonObject
                val pos = if (o.has("blockPos")) o.getAsJsonObject("blockPos") else o // old Odin exports had x, y, z on the waypoint itself
                val kind = o.get("type")?.takeIf { !it.isJsonNull }?.asString?.uppercase()
                val type = TYPES.firstOrNull { it.uppercase() == kind } ?: if (o.get("secret")?.asBoolean == true) "Secret" else "None"
                // Odin's per-waypoint look: "#RRGGBBAA" colour, filled / depth flags, a title and the box inside the block.
                val color = o.get("color")?.takeIf { it.isJsonPrimitive }?.asString?.let { s ->
                    runCatching {
                        val h = s.removePrefix("#")
                        val a = if (h.length >= 8) h.substring(6, 8).toInt(16) else 255
                        (a shl 24) or (h.substring(0, 2).toInt(16) shl 16) or (h.substring(2, 4).toInt(16) shl 8) or h.substring(4, 6).toInt(16)
                    }.getOrNull()
                }
                val filled = o.get("filled")?.takeIf { it.isJsonPrimitive }?.asBoolean
                val depth = o.get("depth")?.takeIf { it.isJsonPrimitive }?.asBoolean
                val title = o.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.ifBlank { null }
                val box = o.get("aabb")?.takeIf { it.isJsonObject && it.asJsonObject.has("minX") }?.asJsonObject?.let { a ->
                    doubleArrayOf(a.get("minX").asDouble, a.get("minY").asDouble, a.get("minZ").asDouble, a.get("maxX").asDouble, a.get("maxY").asDouble, a.get("maxZ").asDouble)
                }
                list += Wp(pos.get("x").asInt, pos.get("y").asInt, pos.get("z").asInt, type, color, filled, depth, title, box)
            }
            out[room] = list
        }
        out
    }.getOrNull()

    private fun clip(code: String, what: String) {
        mc.keyboardHandler.clipboard = code
        Debug.line(name, "exported ${data.values.sumOf { it.size }} waypoints, ${code.length} chars")
        say("§aCopied your waypoints as a $what §7(${data.values.sumOf { it.size }} waypoints, ${code.length} characters).")
    }

    private fun importText(text: String, from: String, replace: Boolean = importMode.value == "Replace") {
        val parsed = decode(text)?.let(::parseWaypoints)
        if (parsed == null) { say("§cThat is not a waypoint code (CedClient's or Odin's)."); return }
        if (replace) data = parsed
        else for ((room, list) in parsed) {
            val mine = data.getOrPut(room) { ArrayList() }
            for (w in list) if (mine.none { it.x == w.x && it.y == w.y && it.z == w.z }) mine += w
        }
        persist()
        val count = parsed.values.sumOf { it.size }
        val how = if (replace) "Replace" else "Merge"
        Debug.line(name, "imported $count waypoints in ${parsed.size} rooms from $from ($how)")
        say("§aImported §f$count §awaypoints in §f${parsed.size} §arooms from $from §7($how).")
    }

    /** The waypoint set bundled in the jar (assets/cedclient/dungeon_waypoints_default.txt, any code [importText] reads). */
    private fun loadDefault(silent: Boolean) {
        val text = DungeonWaypoints::class.java.getResourceAsStream("/assets/cedclient/dungeon_waypoints_default.txt")?.bufferedReader()?.readText()
        if (text == null) { if (!silent) say("§7No default preset is bundled yet."); return }
        importText(text, "the default preset")
    }

    // ---- presets: named codes (CedClient's or Odin's) kept in config/cedclient/waypoint_presets, switched from the Presets popup ----

    private val presetDir get() = java.io.File(CedPaths.dir, "waypoint_presets")
    private fun cleanName(n: String) = n.filter { it.isLetterOrDigit() || it in " _-." }.trim().take(32)

    internal fun presetNames(): List<String> =
        listOf("Default") + (presetDir.listFiles { f -> f.isFile && f.extension == "txt" }?.map { it.nameWithoutExtension }?.sorted().orEmpty())

    private fun presetText(name: String): String? =
        if (name == "Default") DungeonWaypoints::class.java.getResourceAsStream("/assets/cedclient/dungeon_waypoints_default.txt")?.bufferedReader()?.readText()
        else java.io.File(presetDir, "$name.txt").takeIf { it.isFile }?.readText()

    internal fun presetCount(name: String): Int = presetText(name)?.let(::decode)?.let(::parseWaypoints)?.values?.sumOf { it.size } ?: 0

    /** Switching: your waypoints are replaced by the preset's. */
    internal fun applyPreset(name: String) {
        val text = presetText(name)
        if (text == null) say("§cNo preset called $name") else importText(text, "the preset $name", replace = true)
    }

    /** Keeps [code] (or, null, your current waypoints) as a preset called [rawName]; the saved name, or null when it was refused. */
    internal fun savePreset(rawName: String, code: String?): String? {
        val n = cleanName(rawName).ifEmpty { "Preset ${presetNames().size}" }
        if (n == "Default") { say("§cThat name is taken."); return null }
        if (code != null && decode(code)?.let(::parseWaypoints) == null) { say("§cThat is not a waypoint code (CedClient's or Odin's)."); return null }
        presetDir.mkdirs()
        java.io.File(presetDir, "$n.txt").writeText(code?.trim() ?: encodeCed())
        say("§aSaved the preset §f$n")
        return n
    }

    internal fun deletePreset(name: String) {
        if (name != "Default" && java.io.File(presetDir, "$name.txt").delete()) say("§7Deleted the preset §f$name")
    }

    internal fun clipboardText(): String = mc.keyboardHandler.clipboard

    private fun persist() {
        runCatching { saveFile.parentFile?.mkdirs(); saveFile.writeText(gson.toJson(data)) }
            .onFailure { println("[DungeonWaypoints] Failed to save: ${it.message}") }
    }

    init {
        addSettings(edit, editKey, type, cycleKey, noneColor, normalColor, secretColor, etherColor, boxMode, throughWalls, labels, hideDone, resetRoom, presetsPopup, importMode, exportAction, exportOdinAction, importAction, presetAction)
        runCatching {
            if (saveFile.exists()) gson.fromJson<HashMap<String, MutableList<Wp>>>(saveFile.readText(), object : TypeToken<HashMap<String, MutableList<Wp>>>() {}.type)?.let { data = it }
        }.onFailure { println("[DungeonWaypoints] Failed to load: ${it.message}") }
        // A first launch starts from the bundled preset, if there is one.
        if (!saveFile.exists()) loadDefault(true)

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // Polled always, so a key held while the module was off does not count as a fresh press.
            val editDown = client.gui.screen() == null && editKey.value >= 0 && Keys.isDown(editKey.value)
            val cycleDown = client.gui.screen() == null && cycleKey.value >= 0 && Keys.isDown(cycleKey.value)
            if (isEnabled && editDown && !editWas) { edit.value = !edit.value; say("Edit mode ${if (edit.value) "§aon" else "§coff"}") }
            if (isEnabled && cycleDown && !cycleWas) { type.value = TYPES[(TYPES.indexOf(type.value) + 1) % TYPES.size]; say("Next waypoint: §f${type.value}") }
            editWas = editDown; cycleWas = cycleDown

            val level = client.level
            val player = client.player
            if (level !== lastLevel) { lastLevel = level; cur = null; lastTile = null; lastPos = null; data.values.forEach { l -> l.forEach { it.done = false } } }
            if (!isEnabled || level == null || player == null || !DungeonState.inDungeon || DungeonState.inBoss || !CosmeticRooms.isReady()) {
                cur = null
                return@register
            }
            val tile = CosmeticRooms.tileOf(player.blockPosition().x, player.blockPosition().z)
            if (cur?.tiles?.contains(tile) != true) {
                cur = null
                if (tile != lastTile || retry-- <= 0) {
                    lastTile = tile; retry = 10
                    CosmeticRooms.scanRoom(tile, level)?.let { room ->
                        cur = Cur(room.data.name, room.clayPos, room.rotation, room.components)
                        Debug.line(name, "room ${room.data.name}, ${data[room.data.name]?.size ?: 0} waypoints")
                    }
                }
            }
            // An etherwarp is a jump of several blocks in one tick: the Etherwarp waypoint you land on is done.
            val pos = player.position()
            val prev = lastPos
            lastPos = pos
            val room = cur
            if (room != null && prev != null && pos.distanceToSqr(prev) > 36.0) {
                data[room.name]?.firstOrNull { it.type == "Etherwarp" && !it.done && Vec3.atCenterOf(room.real(it)).distanceToSqr(pos) < 9.0 }?.let {
                    it.done = true
                    Debug.line(name, "etherwarp waypoint reached")
                }
            }
        }

        UseBlockCallback.EVENT.register(UseBlockCallback { _, level, hand, hit ->
            val room = cur
            if (!level.isClientSide || !isEnabled || room == null || hand != InteractionHand.MAIN_HAND) return@UseBlockCallback InteractionResult.PASS
            val rel = room.rel(hit.blockPos)
            val list = data.getOrPut(room.name) { ArrayList() }
            val here = list.firstOrNull { it.x == rel.x && it.y == rel.y && it.z == rel.z }
            if (edit.value) {
                if (here != null) list.remove(here) else list += Wp(rel.x, rel.y, rel.z, type.value)
                persist()
                Debug.line(name, "${if (here != null) "removed" else "added ${type.value}"} waypoint at $rel in ${room.name}")
                return@UseBlockCallback InteractionResult.FAIL
            }
            if (here?.type == "Secret") here.done = true
            InteractionResult.PASS
        })

        LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { context ->
            val room = cur ?: return@CollectSubmits
            if (!isEnabled) return@CollectSubmits
            val shown = data[room.name]?.filter { !(hideDone.value && it.done) }.orEmpty()
            if (shown.isEmpty()) return@CollectSubmits
            val cam = context.gameRenderer().mainCamera().position()
            // Each waypoint keeps what an import gave it (colour, filled / outline, depth); else the type's colour and the module's settings.
            class Item(val box: AABB, val c: Color, val fill: Boolean, val line: Boolean, val phase: Boolean)
            val items = shown.map { w ->
                val mode = when (w.filled) { true -> "Filled"; false -> "Outline"; null -> boxMode.value }
                Item(room.realBox(w).move(-cam.x, -cam.y, -cam.z), w.color?.let { Color(it) } ?: colorOf(w.type), mode != "Outline", mode != "Filled", w.depth?.let { !it } ?: throughWalls.value)
            }
            for (phase in listOf(true, false)) {
                val fills = items.filter { it.fill && it.phase == phase }
                if (fills.isNotEmpty()) context.geometry(if (phase) CustomRenderType.FILLED_ESP else CustomRenderType.FILLED) { pose, buffer ->
                    for (i in fills) FilledBox.draw(pose, buffer, i.box, i.c.redFloat, i.c.greenFloat, i.c.blueFloat, 0.35f)
                }
                val lines = items.filter { it.line && it.phase == phase }
                if (lines.isNotEmpty()) context.geometry(if (phase) CustomRenderType.LINES_ESP else CustomRenderType.LINES_DEPTH) { pose, buffer ->
                    for (i in lines) LineBox.draw(pose, buffer, i.box, i.c.redFloat, i.c.greenFloat, i.c.blueFloat, i.c.alphaFloat)
                }
            }
            for (w in shown) {
                val text = w.title ?: if (labels.value) w.type else continue
                val box = room.realBox(w)
                val c = w.color?.let { Color(it) } ?: colorOf(w.type)
                WorldText.draw(context, Vec3((box.minX + box.maxX) / 2, box.maxY + 0.4, (box.minZ + box.maxZ) / 2), Component.literal(text).withColor((c.red shl 16) or (c.green shl 8) or c.blue))
            }
        })
    }

    override fun onDisable() { cur = null; lastTile = null }
}
