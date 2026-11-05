//#if CHEAT
package ced.cedclient.pathing

import ced.cedclient.config.CedPaths
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Teleports that did nothing, remembered between walks (config/cedclient/path_failures.json): the same few hops failed
 * run after run because the plan only avoided them for the walk it was in. Keys are packed block positions of the landing
 * (or the etherwarp target). Entries are forgotten after [KEEP_MS], and `/cedclient path forget` clears them.
 */
object PathFailures {
    private class Entry(val key: Long, var at: Long, var count: Int = 2)

    private const val KEEP_MS = 14L * 24 * 60 * 60 * 1000
    private val gson = Gson()
    private val file by lazy { CedPaths.file("path_failures.json") }
    private val entries: MutableList<Entry> by lazy { load() }

    private fun load(): MutableList<Entry> = try {
        if (file.exists()) {
            val now = System.currentTimeMillis()
            (gson.fromJson<List<Entry>>(file.readText(), object : TypeToken<List<Entry>>() {}.type) ?: emptyList())
                .filter { now - it.at < KEEP_MS }.onEach { if (it.count == 0) it.count = 2 }.toMutableList()
        } else mutableListOf()
    } catch (e: Exception) {
        mutableListOf()
    }

    /** Packed block positions to leave out of every plan: the ones that failed twice (one miss can be lag). */
    fun keys(): Set<Long> = entries.filter { it.count >= 2 }.mapTo(HashSet()) { it.key }

    /** Records a miss; true once it has missed twice and is left out of plans from now on. */
    fun strike(key: Long): Boolean {
        val e = entries.firstOrNull { it.key == key } ?: Entry(key, 0L, 0).also { entries += it }
        e.count++
        e.at = System.currentTimeMillis()
        save()
        return e.count >= 2
    }

    fun clear(): Int {
        val n = entries.size
        entries.clear()
        save()
        return n
    }

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(entries))
        } catch (e: Exception) {
            println("[PathFailures] could not save: ${e.message}")
        }
    }
}
//#endif
