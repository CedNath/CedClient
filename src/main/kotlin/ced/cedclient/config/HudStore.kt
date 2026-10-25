package ced.cedclient.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where a HUD keeps its position / scale: one entry in hud/huds.json (name -> that HUD's json) instead of a file each.
 * Looks like a file to the HUD (readText / writeText / exists), so a module only swaps `CedPaths.file("x_hud.json")`
 * for `CedPaths.hud("x")`.
 */
class HudFile(private val key: String) {
    /** There is no folder per HUD to create. */
    val parentFile: File? get() = null

    fun exists() = HudStore.get(key) != null
    fun readText(): String = HudStore.get(key) ?: throw FileNotFoundException("No saved HUD '$key'")
    fun writeText(text: String) = HudStore.put(key, text)
}

object HudStore {
    private val file = File(CedPaths.dir, "hud/huds.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val huds = JsonObject()

    init {
        runCatching { if (file.exists()) JsonParser.parseString(file.readText()).asJsonObject.entrySet().forEach { huds.add(it.key, it.value) } }
        importLegacy()
    }

    @Synchronized
    fun get(key: String): String? = huds.get(key)?.let { if (it is JsonPrimitive && it.isString) it.asString else it.toString() }

    @Synchronized
    fun put(key: String, text: String) {
        val value = runCatching { JsonParser.parseString(text) }.getOrNull() ?: JsonPrimitive(text)
        if (huds.get(key) == value) return
        huds.add(key, value)
        save()
    }

    private fun save() {
        runCatching {
            file.parentFile.mkdirs()
            val tmp = File(file.parentFile, "huds.json.tmp")
            tmp.writeText(gson.toJson(huds))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** One-time: the old one-file-per-HUD layout (a "_hud.json" file per HUD, plus hud-layout.json and hud-styles.json). */
    private fun importLegacy() {
        val old = ArrayList<Pair<String, File>>()
        for (dir in listOf(CedPaths.dir, File(CedPaths.dir, "hud"))) {
            dir.listFiles()?.forEach { f ->
                val key = when {
                    f.name.endsWith("_hud.json") -> f.name.removeSuffix("_hud.json")
                    f.name == "hud-layout.json" -> "layout"
                    f.name == "hud-styles.json" -> "styles"
                    else -> null
                }
                if (key != null && f.isFile) old += key to f
            }
        }
        if (old.isEmpty()) return
        for ((key, f) in old) {
            if (huds.has(key)) continue
            runCatching { huds.add(key, JsonParser.parseString(f.readText())) }
        }
        save()
        if (file.exists()) old.forEach { it.second.delete() }
    }
}
