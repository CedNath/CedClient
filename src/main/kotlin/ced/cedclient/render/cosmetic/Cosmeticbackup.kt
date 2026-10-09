package ced.cedclient.render.cosmetic

import ced.cedclient.config.CedPaths
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Whole Cosmetic Blocks configs as one JSON bundle -- every preset on every island, the active preset per island,
 * dungeon room blocks, remaps, favourites and the module settings -- shared as a file or via the clipboard.
 *
 *   config/cedclient/cosmetic_exports/<name>.json
 *
 * Every import first writes the current config to a "backup-<time>" export, so nothing is lost by importing.
 */
object CosmeticBackup {
    private const val FORMAT = "cedclient-cosmetic"
    private const val VERSION = 1

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    class Result(val blocks: Int, val roomBlocks: Int, val remaps: Int, val favorites: Int, val settings: Boolean) {
        override fun toString() =
            "$blocks block(s), $roomBlocks room block(s), $remaps remap(s), $favorites favourite(s)" +
                    if (settings) ", settings" else ""
    }

    val dir: File get() = CedPaths.file("cosmetic_exports")

    fun listExports(): List<String> =
        dir.listFiles { f -> f.isFile && f.extension == "json" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9_.-]"), "_").trim('.').take(48)

    /** Builds the bundle. [settings] is the module's settings object (or null to leave them out). */
    fun build(settings: JsonObject?): JsonObject {
        CosmeticStore.flush()
        CosmeticRooms.flush()
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        root.addProperty("version", VERSION)
        val store = CosmeticStore.exportAll()
        root.add("presets", store.get("presets"))
        root.add("active", store.get("active"))
        root.add("rooms", CosmeticRooms.exportAll())
        root.add("remaps", CosmeticRemap.exportTree())
        root.add("favorites", JsonArray().apply { CosmeticFavorites.all().forEach { add(it) } })
        settings?.let { root.add("settings", it) }
        return root
    }

    fun toText(bundle: JsonObject): String = gson.toJson(bundle)

    /** Writes the bundle to cosmetic_exports/<name>.json and returns the file. */
    fun exportToFile(name: String, settings: JsonObject?): File {
        val file = File(dir, "${sanitize(name).ifEmpty { "export" }}.json")
        dir.mkdirs()
        file.writeText(toText(build(settings)))
        return file
    }

    fun readFile(name: String): String? = File(dir, "${sanitize(name)}.json").takeIf { it.isFile }?.readText()

    /** Parses [text] as a bundle, or null when it is not one. */
    fun parse(text: String): JsonObject? = try {
        val obj = JsonParser.parseString(text).asJsonObject
        if (obj.get("format")?.asString == FORMAT) obj else null
    } catch (_: Exception) {
        null
    }

    /**
     * Applies a parsed bundle. [replace] wipes the current data first; otherwise everything is merged in.
     * [applySettings] receives the bundle's settings object, if it has one. [currentSettings] goes into the backup.
     */
    fun import(bundle: JsonObject, replace: Boolean, currentSettings: JsonObject?, applySettings: (JsonObject) -> Unit): Result {
        exportToFile("backup-${LocalDateTime.now().format(stamp)}", currentSettings)

        val presetPart = JsonObject()
        bundle.getAsJsonObject("presets")?.let { presetPart.add("presets", it) }
        bundle.getAsJsonObject("active")?.let { presetPart.add("active", it) }
        val blocks = CosmeticStore.importAll(presetPart, replace)

        val rooms = bundle.getAsJsonObject("rooms")?.let { CosmeticRooms.importAll(it, replace) } ?: 0

        var remaps = 0
        bundle.get("remaps")?.takeIf { it.isJsonObject }?.let {
            if (CosmeticRemap.importTree(it, replace)) remaps = it.asJsonObject.size()
        }

        val favs = bundle.getAsJsonArray("favorites")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString } ?: emptyList()
        if (favs.isNotEmpty() || replace) CosmeticFavorites.importAll(favs, replace)

        val settings = bundle.getAsJsonObject("settings")
        settings?.let(applySettings)

        return Result(blocks, rooms, remaps, favs.size, settings != null)
    }
}
