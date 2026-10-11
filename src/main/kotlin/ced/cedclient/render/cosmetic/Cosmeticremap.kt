package ced.cedclient.render.cosmetic

import ced.cedclient.config.CedPaths
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import net.minecraft.client.Minecraft
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * Global remaps: every block of a source type renders as a (weighted, per-position deterministic) target block,
 * e.g. stone -> 3x deepslate + 1x tuff. Stored in config/cedclient/cosmetic/remaps.json.
 */
object CosmeticRemap {
    class Target(var id: String, var weight: Int)

    private class Resolved(val states: Array<BlockState>, val cum: IntArray, val total: Int)

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val remaps = LinkedHashMap<String, MutableList<Target>>()
    @Volatile private var resolved: Map<Block, Resolved> = emptyMap()
    private var loaded = false

    fun hasRemaps(): Boolean = resolved.isNotEmpty()

    fun remapFor(block: Block, x: Int, y: Int, z: Int): BlockState? {
        if (!CosmeticConfig.enabled || !CosmeticConfig.remapsEnabled || !CosmeticConfig.gateOpen()) return null
        val r = resolved[block] ?: return null
        if (r.total <= 0) return null
        if (r.states.size == 1) return r.states[0]
        val h = Math.floorMod(x * 73856093 xor y * 19349663 xor z * 83492791, r.total)
        var idx = 0
        while (idx < r.cum.size && h >= r.cum[idx]) idx++
        return r.states[idx.coerceIn(0, r.states.size - 1)]
    }

    fun load() {
        if (loaded) return
        loaded = true
        try {
            val f = CedPaths.file("cosmetic/remaps.json")
            if (f.exists()) {
                val parsed: LinkedHashMap<String, MutableList<Target>>? =
                    gson.fromJson(f.readText(), object : TypeToken<LinkedHashMap<String, MutableList<Target>>>() {}.type)
                parsed?.let { remaps.putAll(it) }
            }
        } catch (e: Exception) {
            println("[CosmeticBlocks] failed to load remaps: ${e.message}")
        }
        rebuild(false)
    }

    private fun save() {
        try {
            val f = CedPaths.file("cosmetic/remaps.json")
            f.parentFile.mkdirs()
            f.writeText(gson.toJson(remaps))
        } catch (e: Exception) {
            println("[CosmeticBlocks] failed to save remaps: ${e.message}")
        }
    }

    private fun rebuild(refreshWorld: Boolean = true) {
        val map = HashMap<Block, Resolved>()
        for ((sourceId, targets) in remaps) {
            val source = CosmeticCodec.resolve(CosmeticCodec.normalizeId(sourceId))?.block ?: continue
            val states = ArrayList<BlockState>()
            val cum = ArrayList<Int>()
            var running = 0
            for (t in targets) {
                if (t.weight <= 0) continue
                val s = CosmeticCodec.resolve(CosmeticCodec.normalizeId(t.id)) ?: continue
                states += s
                running += t.weight
                cum += running
            }
            if (states.isEmpty()) continue
            map[source] = Resolved(states.toTypedArray(), cum.toIntArray(), running)
        }
        resolved = map
        if (refreshWorld) {
            val mc = Minecraft.getInstance()
            if (mc.level != null) mc.levelExtractor.allChanged()
        }
    }

    fun sources(): List<String> { load(); return remaps.keys.toList() }
    fun targetsOf(source: String): List<Target> { load(); return remaps[CosmeticCodec.normalizeId(source)]?.toList() ?: emptyList() }

    fun addTarget(source: String, target: String, weight: Int = 1) {
        load()
        val src = CosmeticCodec.normalizeId(source)
        val tgt = CosmeticCodec.normalizeId(target)
        val list = remaps.getOrPut(src) { ArrayList() }
        val existing = list.firstOrNull { it.id == tgt }
        if (existing != null) existing.weight += weight else list += Target(tgt, weight.coerceAtLeast(1))
        rebuild(); save()
    }

    fun setWeight(source: String, index: Int, weight: Int) {
        load()
        val list = remaps[CosmeticCodec.normalizeId(source)] ?: return
        if (index !in list.indices) return
        list[index].weight = weight.coerceIn(1, 999)
        rebuild(); save()
    }

    fun removeTarget(source: String, index: Int) {
        load()
        val key = CosmeticCodec.normalizeId(source)
        val list = remaps[key] ?: return
        if (index !in list.indices) return
        list.removeAt(index)
        if (list.isEmpty()) remaps.remove(key)
        rebuild(); save()
    }

    fun removeSource(source: String): Boolean {
        load()
        val removed = remaps.remove(CosmeticCodec.normalizeId(source)) != null
        if (removed) { rebuild(); save() }
        return removed
    }

    fun clear() { load(); remaps.clear(); rebuild(); save() }

    fun exportJson(): String { load(); return gson.toJson(remaps) }

    fun exportTree(): JsonElement { load(); return gson.toJsonTree(remaps) }

    fun importJson(json: String, replace: Boolean = true): Boolean =
        try { importTree(JsonParser.parseString(json), replace) } catch (_: Exception) { false }

    /** [replace] drops every existing remap first; otherwise only the imported sources are overwritten. */
    fun importTree(json: JsonElement, replace: Boolean = true): Boolean {
        return try {
            val parsed: LinkedHashMap<String, MutableList<Target>> =
                gson.fromJson(json, object : TypeToken<LinkedHashMap<String, MutableList<Target>>>() {}.type) ?: return false
            load()
            if (replace) remaps.clear()
            for ((src, targets) in parsed) {
                val clean = targets.filter { it.id.isNotBlank() && it.weight > 0 }.toMutableList()
                if (clean.isNotEmpty()) remaps[CosmeticCodec.normalizeId(src)] = clean
            }
            rebuild(); save()
            true
        } catch (_: Exception) {
            false
        }
    }
}
