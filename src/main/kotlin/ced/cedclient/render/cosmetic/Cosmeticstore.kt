package ced.cedclient.render.cosmetic

import ced.cedclient.config.CedPaths
import ced.cedclient.state.IslandState
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-position cosmetic replacements, organised per SkyBlock island and per named preset, plus a dungeon-room layer
 * (see [CosmeticRooms]) that is merged into the same render cache.
 *
 *   config/cedclient/cosmetic/index.json                 -> which preset is active on each island
 *   config/cedclient/cosmetic/<island>/<preset>.json     -> { "replacements": { "x,y,z": "minecraft:stone[...]" } }
 *
 * Raw entry format: [!]block[props][|shape=fence|wall|pane]   ("!" = phantom, also shown where the real block is air)
 *
 * Edits go to the active preset of the island you are on, or -- in a scanned dungeon room with Room Scanning on --
 * to that room (room-relative, so they show up in every run that has the room, whatever its rotation).
 */
object CosmeticStore {
    const val DEFAULT_PRESET = "default"
    const val OTHER = "other"
    private const val MAX_UNDO = 50

    class Preset {
        val entries = LinkedHashMap<Long, String>()
    }

    /** One undoable change. [room] != null means the change was made in that dungeon room. */
    private class Op(val key: Long, val previous: String?, val room: ScannedRoom?)

    // ---- data (main thread) ----
    private val presets = HashMap<String, MutableMap<String, Preset>>()
    private val activeNames = HashMap<String, String>()
    private val loaded = HashSet<String>()
    private val undoStack = ArrayDeque<List<Op>>()
    private var batch: MutableList<Op>? = null

    var enabled = false
        private set
    var islandKey = OTHER
        private set
    val activePresetName: String get() = activeNames[islandKey] ?: DEFAULT_PRESET

    // ---- render cache (read from chunk compile threads) ----
    @JvmField @Volatile var hasEntries = false
    /** What the mesher sees at each position (for |shape= entries: the fence/wall/pane geometry). */
    @JvmField val cache = ConcurrentHashMap<Long, BlockState>()
    @JvmField val phantom: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    /** Texture to paint onto the geometry at a position (|shape= entries, and Keep Block Size retextures). */
    @JvmField val retexture = ConcurrentHashMap<Long, BlockState>()
    /** Keep Block Size: the original stair/slab whose geometry a non-full replacement should be drawn with. */
    @JvmField val originalShape = ConcurrentHashMap<Long, BlockState>()
    /** Positions whose entry has an explicit |shape= (Keep Block Size leaves those alone). */
    @JvmField val shaped: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    // ---- saving ----
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val dirtyPresets = HashSet<Pair<String, String>>()
    private var indexDirty = false
    private var saveDelay = 0

    // ---- re-meshing ----
    private val pending = ArrayDeque<Long>()
    private val pendingSet = HashSet<Long>()

    private val mc get() = Minecraft.getInstance()

    init {
        loadIndex()
        ClientLifecycleEvents.CLIENT_STOPPING.register { flush(); CosmeticRooms.flush() }
    }

    fun islandNames(): List<String> = IslandState.Island.entries.map { it.name.lowercase() } + OTHER

    private fun islandKeyNow(): String = IslandState.island?.name?.lowercase() ?: OTHER

    // ------------------------------------------------------------------
    // Presets
    // ------------------------------------------------------------------

    fun sanitize(name: String) = name.lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(32)

    private fun presetsFor(island: String): MutableMap<String, Preset> {
        if (loaded.add(island)) {
            val map = presets.getOrPut(island) { HashMap() }
            val dir = CedPaths.file("cosmetic/$island")
            dir.listFiles { f -> f.extension == "json" }?.forEach { f -> readPreset(f)?.let { map[f.nameWithoutExtension] = it } }
        }
        return presets.getOrPut(island) { HashMap() }
    }

    private fun current(): Preset = presetsFor(islandKey).getOrPut(activePresetName) { Preset() }

    fun listPresets(island: String = islandKey): List<String> {
        val names = presetsFor(island).keys.toMutableSet()
        if (island == islandKey) names += activePresetName
        return names.sorted()
    }

    fun entryCount(): Int = current().entries.size

    /** Block count of a preset on the current island (0 for one that does not exist yet). */
    fun presetSize(name: String): Int = presetsFor(islandKey)[name]?.entries?.size ?: 0

    /** Switches to the preset after the active one on this island (wraps). Returns its name, or null if there is only one. */
    fun cyclePreset(): String? {
        val names = listPresets()
        if (names.size < 2) return null
        val next = names[(names.indexOf(activePresetName) + 1) % names.size]
        return next.takeIf { usePreset(it) }
    }

    /** Raw entry of the active preset at [key] (world layer only). */
    fun rawAt(key: Long): String? = current().entries[key]

    fun createPreset(name: String, island: String = islandKey, copyFrom: Preset? = null): Boolean {
        val n = sanitize(name)
        val map = presetsFor(island)
        if (n.isEmpty() || map.containsKey(n)) return false
        val p = Preset()
        copyFrom?.let { p.entries.putAll(it.entries) }
        map[n] = p
        dirtyPresets += island to n
        saveDelay = 40
        return true
    }

    /** Copies a preset (possibly from another island) into the current island under a new name. */
    fun copyPreset(srcIsland: String, srcName: String, newName: String): Boolean {
        val src = presetsFor(srcIsland)[srcName] ?: return false
        return createPreset(newName, islandKey, src)
    }

    fun usePreset(name: String): Boolean {
        if (!presetsFor(islandKey).containsKey(name) && name != DEFAULT_PRESET) return false
        activeNames[islandKey] = name
        indexDirty = true
        saveDelay = 40
        undoStack.clear()
        rebuildCache()
        return true
    }

    fun deletePreset(name: String): Boolean {
        val map = presetsFor(islandKey)
        if (map.remove(name) == null) return false
        CedPaths.file("cosmetic/$islandKey/$name.json").delete()
        dirtyPresets.remove(islandKey to name)
        if (name == activePresetName) usePreset(DEFAULT_PRESET)
        return true
    }

    /** Exports the active preset as compact JSON (for sharing via clipboard). */
    fun exportCurrent(): String {
        val root = JsonObject()
        root.add("replacements", entriesJson(current()))
        return root.toString()
    }

    /** Merges exported JSON into the active preset (one undo step). Returns the number of blocks imported, or -1. */
    fun importIntoCurrent(json: String): Int {
        return try {
            val obj = JsonParser.parseString(json).asJsonObject.getAsJsonObject("replacements") ?: return -1
            val changes = ArrayList<Pair<BlockPos, String?>>()
            for ((k, v) in obj.entrySet()) {
                val parts = k.split(',')
                if (parts.size != 3) continue
                changes += BlockPos(parts[0].trim().toInt(), parts[1].trim().toInt(), parts[2].trim().toInt()) to v.asString
            }
            applyWorldChanges(changes)
            changes.size
        } catch (_: Exception) {
            -1
        }
    }

    /** Every island folder that exists on disk, plus the known islands. */
    private fun allIslands(): Set<String> {
        val onDisk = CedPaths.file("cosmetic").listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
        return (islandNames() + onDisk + presets.keys).toSortedSet()
    }

    /** Full-config export: { "presets": { island: { preset: { "x,y,z": raw } } }, "active": { island: preset } }. */
    fun exportAll(): JsonObject {
        val islands = JsonObject()
        for (island in allIslands()) {
            val map = presetsFor(island)
            if (map.isEmpty()) continue
            val obj = JsonObject()
            for (name in map.keys.sorted()) obj.add(name, entriesJson(map.getValue(name)))
            islands.add(island, obj)
        }
        val active = JsonObject()
        for ((island, name) in activeNames) active.addProperty(island, name)
        val root = JsonObject()
        root.add("presets", islands)
        root.add("active", active)
        return root
    }

    /**
     * Full-config import (see [exportAll]). [replace] deletes every existing preset first; otherwise imported presets
     * are merged into same-named ones (imported blocks win). Not undoable. Returns the number of blocks imported.
     */
    fun importAll(root: JsonObject, replace: Boolean): Int {
        if (replace) {
            for (island in allIslands()) {
                val map = presetsFor(island)
                for (name in map.keys) CedPaths.file("cosmetic/$island/$name.json").delete()
                map.clear()
            }
            dirtyPresets.clear()
            activeNames.clear()
        }
        var count = 0
        root.getAsJsonObject("presets")?.entrySet()?.forEach { (islandRaw, islandEl) ->
            val island = sanitize(islandRaw)
            if (island.isEmpty() || !islandEl.isJsonObject) return@forEach
            val map = presetsFor(island)
            for ((nameRaw, presetEl) in islandEl.asJsonObject.entrySet()) {
                val name = sanitize(nameRaw)
                if (name.isEmpty() || !presetEl.isJsonObject) continue
                count += readEntries(presetEl.asJsonObject, map.getOrPut(name) { Preset() })
                dirtyPresets += island to name
            }
        }
        root.getAsJsonObject("active")?.entrySet()?.forEach { (island, name) ->
            if (name.isJsonPrimitive) activeNames[sanitize(island)] = sanitize(name.asString)
        }
        indexDirty = true
        undoStack.clear()
        flush()
        rebuildCache()
        return count
    }

    // ------------------------------------------------------------------
    // Edits
    // ------------------------------------------------------------------

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        CosmeticConfig.enabled = value
        rebuildCache()
    }

    /** Sets a replacement. [raw] may carry "!" and "|shape="; [phantomFlag] decides the "!" either way. */
    fun set(pos: BlockPos, raw: String, phantomFlag: Boolean) {
        val parsed = CosmeticCodec.parseRaw(raw)
        val stored = CosmeticCodec.buildRaw(parsed.base, phantomFlag, parsed.shape)
        val room = CosmeticRooms.roomFor(pos)
        if (room != null) {
            record(pos.asLong(), CosmeticRooms.rawAtWorld(pos, room), room)
            CosmeticRooms.setRaw(pos, stored, room)
        } else {
            if (CosmeticRooms.rejectsWorldEdit(pos)) return
            val key = pos.asLong()
            val p = current()
            record(key, p.entries[key], null)
            p.entries[key] = stored
            touched()
            if (enabled) applyEntry(key, stored)
        }
        hasEntries = cache.isNotEmpty()
        if (enabled) markImmediate(pos.x, pos.y, pos.z)
    }

    fun setState(pos: BlockPos, state: BlockState, phantomFlag: Boolean, shape: String? = null) =
        set(pos, CosmeticCodec.buildRaw(CosmeticCodec.serialize(state), false, shape), phantomFlag)

    fun remove(pos: BlockPos): Boolean {
        val key = pos.asLong()
        val room = CosmeticRooms.roomFor(pos)
        if (room != null) {
            val old = CosmeticRooms.rawAtWorld(pos, room)
            if (old != null) {
                record(key, old, room)
                CosmeticRooms.setRaw(pos, null, room)
                hasEntries = cache.isNotEmpty()
                if (enabled) markImmediate(pos.x, pos.y, pos.z)
                return true
            }
        }
        val old = current().entries.remove(key) ?: return false
        record(key, old, null)
        touched()
        clearKey(key)
        hasEntries = cache.isNotEmpty()
        if (enabled) markImmediate(pos.x, pos.y, pos.z)
        return true
    }

    /** Whether [pos] currently has any replacement (world preset or room layer). */
    fun has(pos: BlockPos): Boolean = cache.containsKey(pos.asLong())

    /**
     * Bulk change (fill / replace / flood / ruler / import): each pair sets (raw) or removes (null) one block.
     * Recorded as a single undo step and re-meshed without a per-block hitch.
     */
    fun applyChanges(changes: List<Pair<BlockPos, String?>>) {
        if (changes.isEmpty()) return
        batch {
            for ((pos, raw) in changes) {
                if (raw == null) removeQuiet(pos) else setQuiet(pos, raw)
            }
        }
        hasEntries = cache.isNotEmpty()
        if (enabled) {
            if (changes.size <= 64) changes.forEach { (p, _) -> markImmediate(p.x, p.y, p.z) }
            else queuePositions(changes.map { it.first.asLong() })
        }
    }

    /** Same as [applyChanges] but always targets the world preset, even inside a scanned room. */
    private fun applyWorldChanges(changes: List<Pair<BlockPos, String?>>) {
        batch {
            val p = current()
            for ((pos, raw) in changes) {
                val key = pos.asLong()
                record(key, p.entries[key], null)
                if (raw == null) { p.entries.remove(key); clearKey(key) } else { p.entries[key] = raw; if (enabled) applyEntry(key, raw) }
            }
            touched()
        }
        hasEntries = cache.isNotEmpty()
        if (enabled) queuePositions(changes.map { it.first.asLong() })
    }

    private fun setQuiet(pos: BlockPos, raw: String) {
        val room = CosmeticRooms.roomFor(pos)
        if (room != null) {
            record(pos.asLong(), CosmeticRooms.rawAtWorld(pos, room), room)
            CosmeticRooms.setRaw(pos, raw, room)
            return
        }
        if (CosmeticRooms.rejectsWorldEdit(pos)) return
        val key = pos.asLong()
        val p = current()
        record(key, p.entries[key], null)
        p.entries[key] = raw
        touched()
        if (enabled) applyEntry(key, raw)
    }

    private fun removeQuiet(pos: BlockPos) {
        val key = pos.asLong()
        val room = CosmeticRooms.roomFor(pos)
        if (room != null) {
            val old = CosmeticRooms.rawAtWorld(pos, room)
            if (old != null) {
                record(key, old, room)
                CosmeticRooms.setRaw(pos, null, room)
                return
            }
        }
        val old = current().entries.remove(key) ?: return
        record(key, old, null)
        touched()
        clearKey(key)
    }

    /** Groups every edit made inside [block] into a single undo step. */
    fun batch(block: () -> Unit) {
        if (batch != null) {
            block()
            return
        }
        val list = ArrayList<Op>()
        batch = list
        try {
            block()
        } finally {
            batch = null
            if (list.isNotEmpty()) pushUndo(list)
        }
    }

    fun clearCurrent(): Int {
        val p = current()
        val n = p.entries.size
        if (n == 0) return 0
        pushUndo(p.entries.map { (k, v) -> Op(k, v, null) })
        p.entries.clear()
        touched()
        rebuildCache()
        return n
    }

    fun undo(): Int {
        val ops = undoStack.removeLastOrNull() ?: return 0
        val p = current()
        var roomTouched = false
        for (op in ops.asReversed()) {
            if (op.room != null) {
                CosmeticRooms.setRaw(BlockPos.of(op.key), op.previous, op.room)
                roomTouched = true
            } else {
                if (op.previous == null) p.entries.remove(op.key) else p.entries[op.key] = op.previous
            }
        }
        touched()
        if (roomTouched) CosmeticRooms.scheduleSave()
        rebuildCache()
        return ops.size
    }

    private fun record(key: Long, previous: String?, room: ScannedRoom?) {
        val op = Op(key, previous, room)
        val b = batch
        if (b != null) b += op else pushUndo(listOf(op))
    }

    private fun pushUndo(ops: List<Op>) {
        undoStack.addLast(ops)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
    }

    private fun touched() {
        dirtyPresets += islandKey to activePresetName
        saveDelay = 60
    }

    // ------------------------------------------------------------------
    // Cache
    // ------------------------------------------------------------------

    /** Puts one raw entry into the render cache (used by the world preset and by the room layer). */
    fun applyEntry(key: Long, raw: String) {
        val parsed = CosmeticCodec.parseRaw(raw)
        originalShape.remove(key)
        if (parsed.shape != null) {
            val shapeState = CosmeticCodec.shapeState(parsed.shape)
            val texture = CosmeticCodec.resolve(parsed.base)
            if (shapeState != null && texture != null) {
                cache[key] = shapeState
                retexture[key] = texture
                shaped.add(key)
                if (parsed.phantom) phantom.add(key) else phantom.remove(key)
                return
            }
        } else {
            val state = CosmeticCodec.resolve(parsed.base)
            if (state != null) {
                cache[key] = state
                retexture.remove(key)
                shaped.remove(key)
                if (parsed.phantom) phantom.add(key) else phantom.remove(key)
                return
            }
        }
        clearKey(key)
    }

    fun clearKey(key: Long) {
        cache.remove(key)
        phantom.remove(key)
        retexture.remove(key)
        originalShape.remove(key)
        shaped.remove(key)
    }

    /** Drops the Keep Block Size bookkeeping so it is recomputed on the next re-mesh. */
    fun clearKeepSizeCaches() {
        for (k in retexture.keys) if (!shaped.contains(k)) retexture.remove(k)
        originalShape.clear()
    }

    fun rebuildCache() {
        val old = cache.keys.toList()
        cache.clear()
        phantom.clear()
        retexture.clear()
        originalShape.clear()
        shaped.clear()
        if (enabled) {
            current().entries.forEach { (k, raw) -> applyEntry(k, raw) }
            CosmeticRooms.reapply()
        }
        hasEntries = cache.isNotEmpty()
        queuePositions(old)
        queuePositions(cache.keys)
    }

    // ------------------------------------------------------------------
    // Tick: island switching, gradual re-meshing, debounced saving
    // ------------------------------------------------------------------

    fun tick() {
        val key = islandKeyNow()
        if (key != islandKey) {
            flush()
            islandKey = key
            undoStack.clear()
            CosmeticRooms.reset()
            rebuildCache()
        }
        drain()
        if (saveDelay > 0 && --saveDelay == 0) flush()
    }

    // ------------------------------------------------------------------
    // Re-meshing
    // ------------------------------------------------------------------

    private fun sectionsAround(x: Int, y: Int, z: Int, out: MutableSet<Long>) {
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            out += BlockPos.asLong((x + dx) shr 4, (y + dy) shr 4, (z + dz) shr 4)
        }
    }

    /** Dirty the sections touching one edited block right now (an edit should feel instant). */
    fun markImmediate(x: Int, y: Int, z: Int) {
        if (mc.level == null) return
        val renderer = mc.levelRenderer
        val sections = HashSet<Long>()
        sectionsAround(x, y, z, sections)
        try {
            for (s in sections) renderer.setSectionDirty(BlockPos.getX(s), BlockPos.getY(s), BlockPos.getZ(s))
        } catch (_: Exception) {
        }
    }

    /** Bulk changes: queue the sections and re-mesh a few per tick to avoid a hitch. */
    fun queuePositions(positions: Collection<Long>) {
        if (mc.level == null) return
        val sections = HashSet<Long>()
        for (p in positions) sectionsAround(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), sections)
        for (s in sections) if (pendingSet.add(s)) pending.addLast(s)
    }

    /** Re-mesh everything that has (or had) a replacement -- used when a render setting changes. */
    fun requeueAll() = queuePositions(cache.keys.toList())

    private fun drain() {
        if (pending.isEmpty()) return
        if (mc.level == null) {
            pending.clear()
            pendingSet.clear()
            return
        }
        val renderer = mc.levelRenderer
        try {
            var n = 0
            while (n < 12) {
                val s = pending.removeFirstOrNull() ?: break
                pendingSet.remove(s)
                renderer.setSectionDirty(BlockPos.getX(s), BlockPos.getY(s), BlockPos.getZ(s))
                n++
            }
        } catch (_: Exception) {
            pending.clear()
            pendingSet.clear()
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    fun flush() {
        try {
            for ((island, name) in dirtyPresets) writePreset(island, name)
            dirtyPresets.clear()
            if (indexDirty) writeIndex()
        } catch (t: Throwable) {
            println("[CosmeticBlocks] save failed: ${t.message}")
        }
    }

    private fun writePreset(island: String, name: String) {
        val p = presets[island]?.get(name) ?: return
        val root = JsonObject()
        root.add("replacements", entriesJson(p))
        val file = CedPaths.file("cosmetic/$island/$name.json")
        file.parentFile.mkdirs()
        val tmp = java.io.File(file.parentFile, file.name + ".tmp")
        tmp.writeText(gson.toJson(root))
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    private fun readPreset(file: java.io.File): Preset? {
        return try {
            val p = Preset()
            val obj = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("replacements") ?: return p
            readEntries(obj, p)
            p
        } catch (_: Exception) {
            null
        }
    }

    private fun entriesJson(p: Preset): JsonObject {
        val obj = JsonObject()
        for ((k, v) in p.entries) obj.addProperty("${BlockPos.getX(k)},${BlockPos.getY(k)},${BlockPos.getZ(k)}", v)
        return obj
    }

    /** Reads { "x,y,z": raw } into [p], skipping malformed entries. Returns how many were read. */
    private fun readEntries(obj: JsonObject, p: Preset): Int {
        var n = 0
        for ((k, v) in obj.entrySet()) {
            val parts = k.split(',')
            if (parts.size != 3 || !v.isJsonPrimitive) continue
            val x = parts[0].trim().toIntOrNull() ?: continue
            val y = parts[1].trim().toIntOrNull() ?: continue
            val z = parts[2].trim().toIntOrNull() ?: continue
            p.entries[BlockPos.asLong(x, y, z)] = v.asString
            n++
        }
        return n
    }

    private fun writeIndex() {
        val active = JsonObject()
        for ((island, name) in activeNames) active.addProperty(island, name)
        val root = JsonObject()
        root.add("active", active)
        val file = CedPaths.file("cosmetic/index.json")
        file.parentFile.mkdirs()
        file.writeText(gson.toJson(root))
        indexDirty = false
    }

    private fun loadIndex() {
        try {
            val file = CedPaths.file("cosmetic/index.json")
            if (!file.exists()) return
            val active = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("active") ?: return
            for ((island, name) in active.entrySet()) activeNames[island] = name.asString
        } catch (_: Exception) {
        }
    }
}
