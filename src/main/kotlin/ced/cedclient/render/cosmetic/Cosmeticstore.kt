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
 * Per-position cosmetic replacements, organised per SkyBlock island and per named preset.
 *
 *   config/cedclient/cosmetic/index.json                 -> which preset is active on each island
 *   config/cedclient/cosmetic/<island>/<preset>.json     -> { "replacements": { "x,y,z": "minecraft:stone[...]" } }
 *
 * Edits always go to the active preset of the island you are currently on. Switching island (or preset)
 * swaps the render cache and re-meshes only the affected sections, a few per tick.
 * Island = IslandState.island ("hub", "crystal_hollows", ...); anything else (singleplayer, other servers) = "other".
 */
object CosmeticStore {
    const val DEFAULT_PRESET = "default"
    const val OTHER = "other"
    private const val MAX_UNDO = 50

    class Preset {
        val entries = LinkedHashMap<Long, String>()
    }

    // ---- data (main thread) ----
    private val presets = HashMap<String, MutableMap<String, Preset>>()
    private val activeNames = HashMap<String, String>()
    private val loaded = HashSet<String>()
    private val undoStack = ArrayDeque<List<Pair<Long, String?>>>()
    private var batch: MutableList<Pair<Long, String?>>? = null

    var enabled = false
        private set
    var islandKey = OTHER
        private set
    val activePresetName: String get() = activeNames[islandKey] ?: DEFAULT_PRESET

    // ---- render cache (read from chunk compile threads) ----
    @JvmField @Volatile var hasEntries = false
    @JvmField val cache = ConcurrentHashMap<Long, BlockState>()
    @JvmField val phantom: MutableSet<Long> = ConcurrentHashMap.newKeySet()

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
        ClientLifecycleEvents.CLIENT_STOPPING.register { flush() }
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

    // ------------------------------------------------------------------
    // Edits (all act on the current island's active preset)
    // ------------------------------------------------------------------

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        rebuildCache()
    }

    fun set(pos: BlockPos, raw: String, phantomFlag: Boolean) {
        val key = pos.asLong()
        val base = if (raw.startsWith("!")) raw.substring(1) else raw
        val stored = if (phantomFlag) "!$base" else base
        val p = current()
        record(key, p.entries[key])
        p.entries[key] = stored
        touched()
        if (enabled) {
            applyEntry(key, stored)
            hasEntries = cache.isNotEmpty()
            markImmediate(pos.x, pos.y, pos.z)
        }
    }

    fun setState(pos: BlockPos, state: BlockState, phantomFlag: Boolean) =
        set(pos, CosmeticCodec.serialize(state), phantomFlag)

    fun remove(pos: BlockPos): Boolean {
        val key = pos.asLong()
        val old = current().entries.remove(key) ?: return false
        record(key, old)
        touched()
        cache.remove(key)
        phantom.remove(key)
        hasEntries = cache.isNotEmpty()
        if (enabled) markImmediate(pos.x, pos.y, pos.z)
        return true
    }

    /** Groups every edit made inside [block] into a single undo step (used by bulk operations). */
    fun batch(block: () -> Unit) {
        if (batch != null) {
            block()
            return
        }
        val list = ArrayList<Pair<Long, String?>>()
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
        pushUndo(p.entries.map { (k, v) -> k to (v as String?) })
        p.entries.clear()
        touched()
        rebuildCache()
        return n
    }

    fun undo(): Int {
        val ops = undoStack.removeLastOrNull() ?: return 0
        val p = current()
        for ((key, previous) in ops.asReversed()) {
            if (previous == null) p.entries.remove(key) else p.entries[key] = previous
        }
        touched()
        rebuildCache()
        return ops.size
    }

    private fun record(key: Long, previous: String?) {
        val b = batch
        if (b != null) b += key to previous else pushUndo(listOf(key to previous))
    }

    private fun pushUndo(ops: List<Pair<Long, String?>>) {
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

    private fun applyEntry(key: Long, raw: String) {
        val state = CosmeticCodec.resolve(raw)
        if (state == null) {
            cache.remove(key)
            phantom.remove(key)
            return
        }
        cache[key] = state
        if (raw.startsWith("!")) phantom.add(key) else phantom.remove(key)
    }

    private fun rebuildCache() {
        val old = cache.keys.toList()
        cache.clear()
        phantom.clear()
        if (enabled) current().entries.forEach { (k, raw) -> applyEntry(k, raw) }
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
    private fun markImmediate(x: Int, y: Int, z: Int) {
        if (mc.level == null) return
        val renderer = mc.levelRenderer
        val sections = HashSet<Long>()
        sectionsAround(x, y, z, sections)
        try {
            for (s in sections) renderer.setSectionDirty(BlockPos.getX(s), BlockPos.getY(s), BlockPos.getZ(s))
        } catch (_: Exception) {
        }
    }

    /** Bulk changes (preset / island switch): queue the sections and re-mesh a few per tick to avoid a hitch. */
    private fun queuePositions(positions: Collection<Long>) {
        if (mc.level == null) return
        val sections = HashSet<Long>()
        for (p in positions) sectionsAround(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), sections)
        for (s in sections) if (pendingSet.add(s)) pending.addLast(s)
    }

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
            while (n < 8) {
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
        val replacements = JsonObject()
        for ((k, v) in p.entries) replacements.addProperty("${BlockPos.getX(k)},${BlockPos.getY(k)},${BlockPos.getZ(k)}", v)
        val root = JsonObject()
        root.add("replacements", replacements)
        val file = CedPaths.file("cosmetic/$island/$name.json")
        file.parentFile.mkdirs()
        file.writeText(gson.toJson(root))
    }

    private fun readPreset(file: java.io.File): Preset? {
        return try {
            val p = Preset()
            val obj = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("replacements") ?: return p
            for ((k, v) in obj.entrySet()) {
                val parts = k.split(',')
                if (parts.size != 3) continue
                p.entries[BlockPos.asLong(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())] = v.asString
            }
            p
        } catch (_: Exception) {
            null
        }
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