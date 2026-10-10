package ced.cedclient.render.cosmetic

import ced.cedclient.config.CedPaths
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
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

    /** Parses [text] as a bundle (or a cb mod export, converted into one), or null when it is neither. */
    fun parse(text: String): JsonObject? = try {
        val obj = JsonParser.parseString(text).asJsonObject
        if (obj.get("format")?.asString == FORMAT) obj else fromCbExport(obj)
    } catch (_: Exception) {
        null
    }

    /** Preset that cb mod exports are imported into (on the island you are on). */
    const val CB_PRESET = "cb"

    /**
     * cb mod export -> { "x,y,z": raw }, every dimension merged (on Hypixel they are all minecraft:overworld).
     * The cb format is { "minecraft:overworld": { "x,y,z": "[!]minecraft:block[props]" } }, same raw entries as ours.
     * Null when [obj] is not one.
     */
    fun cbEntries(obj: JsonObject): JsonObject? {
        if (obj.size() == 0) return null
        val entries = JsonObject()
        for ((dim, el) in obj.entrySet()) {
            if (':' !in dim || !el.isJsonObject) return null
            for ((k, v) in el.asJsonObject.entrySet()) {
                if (k.count { it == ',' } == 2 && v.isJsonPrimitive) entries.add(k, v)
            }
        }
        return entries
    }

    // ------------------------------------------------------------------
    // Import screen: sources + what a file contains
    // ------------------------------------------------------------------

    /** What an import source holds. */
    sealed class ImportData(val blocks: Int, val description: String) {
        /** A whole CedClient config (cosmetic_exports / Copy All). */
        class Bundle(val json: JsonObject, blocks: Int) : ImportData(blocks, "CedClient config")
        /** World-coordinate blocks: a cb mod config, or a single exported preset. */
        class World(val entries: Map<Long, String>, description: String) : ImportData(entries.size, description)
        /** Room-relative blocks: { room name: { "x,y,z": raw } } (cb_rooms.json, or our rooms.json). */
        class Rooms(val rooms: JsonObject, val roomCount: Int, blocks: Int) : ImportData(blocks, "dungeon rooms")
    }

    /** An importable file. [defaultName] is the suggested preset name. */
    class Source(val label: String, val defaultName: String, val read: () -> String?)

    private val cbDir: File get() = FabricLoader.getInstance().configDir.resolve("cb").toFile()

    /** The cb mod's configs (every .json in config/cb/cb), its rooms (config/cb/cb_rooms.json) and our cosmetic_exports. */
    fun sources(): List<Source> {
        val out = ArrayList<Source>()
        File(cbDir, "cb").listFiles { f -> f.isFile && f.extension == "json" }?.sortedBy { it.name }?.forEach { f ->
            out += Source("cb: ${f.nameWithoutExtension}", "cb_${f.nameWithoutExtension}") { f.readText() }
        }
        File(cbDir, "cb_rooms.json").takeIf { it.isFile }?.let { f -> out += Source("cb: rooms", "cb_rooms") { f.readText() } }
        for (name in listExports()) out += Source(name, name) { readFile(name) }
        return out
    }

    /** Works out what [text] is, or null when it is not something Cosmetic Blocks can import. */
    fun detect(text: String): ImportData? = try {
        val obj = JsonParser.parseString(text).asJsonObject
        when {
            obj.get("format")?.asString == FORMAT -> {
                val blocks = obj.getAsJsonObject("presets")?.entrySet()?.sumOf { (_, island) ->
                    if (island.isJsonObject) island.asJsonObject.entrySet().sumOf { (_, p) -> if (p.isJsonObject) p.asJsonObject.size() else 0 } else 0
                } ?: 0
                ImportData.Bundle(obj, blocks + (obj.getAsJsonObject("rooms")?.entrySet()?.sumOf { if (it.value.isJsonObject) it.value.asJsonObject.size() else 0 } ?: 0))
            }
            obj.get("replacements")?.isJsonObject == true -> ImportData.World(toEntries(obj.getAsJsonObject("replacements")), "exported preset")
            else -> cbEntries(obj)?.let { ImportData.World(toEntries(it), "cb config") } ?: roomsData(obj)
        }
    } catch (_: Exception) {
        null
    }

    private fun roomsData(obj: JsonObject): ImportData.Rooms? {
        if (obj.size() == 0) return null
        var blocks = 0
        for ((_, el) in obj.entrySet()) {
            if (!el.isJsonObject) return null
            for ((k, v) in el.asJsonObject.entrySet()) {
                if (k.count { it == ',' } != 2 || !v.isJsonPrimitive) return null
                blocks++
            }
        }
        return ImportData.Rooms(obj, obj.size(), blocks)
    }

    /** { "x,y,z": raw } -> packed positions, malformed entries skipped. */
    private fun toEntries(obj: JsonObject): Map<Long, String> {
        val out = LinkedHashMap<Long, String>()
        for ((k, v) in obj.entrySet()) {
            if (!v.isJsonPrimitive) continue
            val parts = k.split(',')
            if (parts.size != 3) continue
            val x = parts[0].trim().toIntOrNull() ?: continue
            val y = parts[1].trim().toIntOrNull() ?: continue
            val z = parts[2].trim().toIntOrNull() ?: continue
            out[BlockPos.asLong(x, y, z)] = v.asString
        }
        return out
    }

    /** Wraps a cb mod export as a bundle holding one preset ([CB_PRESET], made active) on the current island. */
    private fun fromCbExport(obj: JsonObject): JsonObject? {
        val entries = cbEntries(obj) ?: return null
        val island = CosmeticStore.islandKey
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        root.addProperty("version", VERSION)
        root.add("presets", JsonObject().apply { add(island, JsonObject().apply { add(CB_PRESET, entries) }) })
        root.add("active", JsonObject().apply { addProperty(island, CB_PRESET) })
        return root
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
