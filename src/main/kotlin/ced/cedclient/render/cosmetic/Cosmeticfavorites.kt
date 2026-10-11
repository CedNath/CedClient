package ced.cedclient.render.cosmetic

import ced.cedclient.config.CedPaths
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

/** Favourite block ids (config/cedclient/cosmetic/favorites.json), cycled with the "Cycle Favorite" key. */
object CosmeticFavorites {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val list = ArrayList<String>()
    private var loaded = false

    private fun ensure() {
        if (loaded) return
        loaded = true
        try {
            val f = CedPaths.file("cosmetic/favorites.json")
            if (!f.exists()) return
            val parsed: List<String>? = gson.fromJson(f.readText(), object : TypeToken<List<String>>() {}.type)
            parsed?.let { list.addAll(it) }
        } catch (_: Exception) {
        }
    }

    private fun save() {
        try {
            val f = CedPaths.file("cosmetic/favorites.json")
            f.parentFile.mkdirs()
            f.writeText(gson.toJson(list))
        } catch (_: Exception) {
        }
    }

    fun all(): List<String> { ensure(); return list.toList() }
    fun contains(id: String): Boolean { ensure(); return list.contains(id) }

    fun add(id: String): Boolean {
        ensure()
        if (list.contains(id)) return false
        list += id
        save()
        return true
    }

    fun remove(id: String): Boolean {
        ensure()
        val removed = list.remove(id)
        if (removed) save()
        return removed
    }

    /** Full-config import: [replace] drops the current list, otherwise new ids are appended. */
    fun importAll(ids: List<String>, replace: Boolean) {
        ensure()
        if (replace) list.clear()
        for (id in ids) if (id.isNotBlank() && !list.contains(id)) list += id
        save()
    }

    /** The favourite after [current] in the list (wraps), or null when there are none. */
    fun next(current: String): String? {
        ensure()
        if (list.isEmpty()) return null
        val i = list.indexOf(current)
        return list[(i + 1) % list.size]
    }
}
