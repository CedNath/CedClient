package ced.cedclient.render.cosmetic

import ced.cedclient.config.CedPaths
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.LevelChunk

/** Shape of one entry in assets/cedclient/rooms.json (nullable: Gson skips Kotlin defaults). */
private class RoomJsonEntry {
    var name: String? = null
    var type: String? = null
    var cores: List<Int>? = null
    var secrets: Int? = null
    var shape: String? = null
}

class RoomInfo(val name: String, val type: String, val cores: List<Int>, val secrets: Int, val shape: String)

data class RoomTile(val x: Int, val z: Int)

class ScannedRoom(val data: RoomInfo, var rotation: RoomRotation, var clayPos: BlockPos, val components: Set<RoomTile>)

/**
 * Dungeon room layer, ported from the cb mod. While you are in a Catacombs run with Room Scanning on, the room you
 * stand in is identified from its block "core" (a hash of one column of blocks, matched against the bundled
 * assets/cedclient/rooms.json) and its rotation from the blue-terracotta marker. Edits made inside it are stored
 * room-relative (config/cedclient/cosmetic/rooms.json), so they reappear every run, in any orientation.
 */
object CosmeticRooms {
    private const val START = -185
    private val HORIZONTALS = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)

    // ---- room database ----
    private var coreToRoom: Map<Int, RoomInfo> = emptyMap()
    private var dbLoaded = false

    // ---- scanning ----
    var currentRoom: ScannedRoom? = null
        private set
    private var lastCenter: RoomTile? = null
    private val mutable = BlockPos.MutableBlockPos()

    // ---- stored room blocks: room name -> "x,y,z" (relative, north-facing) -> raw ----
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val roomBlocks = LinkedHashMap<String, MutableMap<String, String>>()
    private var blocksLoaded = false
    private var saveDelay = 0
    /** World positions the current room layer put into the render cache. */
    private val roomKeys = HashSet<Long>()

    private val mc get() = Minecraft.getInstance()

    fun isReady(): Boolean { loadDb(); return coreToRoom.isNotEmpty() }

    private fun loadDb() {
        if (dbLoaded) return
        dbLoaded = true
        try {
            val stream = CosmeticRooms::class.java.getResourceAsStream("/assets/cedclient/rooms.json") ?: return
            val text = stream.bufferedReader().use { it.readText() }
            val entries: List<RoomJsonEntry> = Gson().fromJson(text, object : TypeToken<List<RoomJsonEntry>>() {}.type)
            val map = HashMap<Int, RoomInfo>()
            for (e in entries) {
                val cores = e.cores ?: continue
                val info = RoomInfo(e.name ?: continue, e.type ?: "NORMAL", cores, e.secrets ?: 0, e.shape ?: "1x1")
                for (c in cores) map[c] = info
            }
            coreToRoom = map
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadBlocks() {
        if (blocksLoaded) return
        blocksLoaded = true
        try {
            val f = CedPaths.file("cosmetic/rooms.json")
            if (!f.exists()) return
            val parsed: LinkedHashMap<String, MutableMap<String, String>>? =
                gson.fromJson(f.readText(), object : TypeToken<LinkedHashMap<String, MutableMap<String, String>>>() {}.type)
            parsed?.forEach { (k, v) -> roomBlocks[k] = LinkedHashMap(v) }
        } catch (e: Exception) {
            println("[CosmeticBlocks] failed to load room blocks: ${e.message}")
        }
    }

    fun scheduleSave() { saveDelay = 60 }

    fun flush() {
        if (!blocksLoaded) return
        try {
            val f = CedPaths.file("cosmetic/rooms.json")
            f.parentFile.mkdirs()
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(roomBlocks))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) {
            println("[CosmeticBlocks] failed to save room blocks: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Edit routing
    // ------------------------------------------------------------------

    /** The scanned room an edit at [pos] belongs to, or null (-> world preset). */
    fun roomFor(pos: BlockPos): ScannedRoom? {
        if (!CosmeticConfig.roomScanning || !CosmeticConfig.inDungeon()) return null
        val room = currentRoom ?: return null
        if (room.rotation == RoomRotation.NONE) return null
        return if (room.components.contains(tileOf(pos.x, pos.z))) room else null
    }

    private fun relKey(pos: BlockPos, room: ScannedRoom): String {
        val rel = room.rotation.toNorth(pos.subtract(room.clayPos.atY(0)))
        return "${rel.x},${rel.y},${rel.z}"
    }

    fun rawAtWorld(pos: BlockPos, room: ScannedRoom): String? {
        loadBlocks()
        val stored = roomBlocks[room.data.name]?.get(relKey(pos, room)) ?: return null
        return CosmeticCodec.rotateRaw(stored, room.rotation, toNorth = false)
    }

    /** Sets (or with null removes) a world-oriented raw entry inside [room]. */
    fun setRaw(pos: BlockPos, raw: String?, room: ScannedRoom) {
        loadBlocks()
        val key = relKey(pos, room)
        if (raw == null) {
            roomBlocks[room.data.name]?.let { m -> m.remove(key); if (m.isEmpty()) roomBlocks.remove(room.data.name) }
        } else {
            roomBlocks.getOrPut(room.data.name) { LinkedHashMap() }[key] = CosmeticCodec.rotateRaw(raw, room.rotation, toNorth = true)
        }
        scheduleSave()
        if (room === currentRoom && CosmeticStore.enabled) {
            val world = pos.asLong()
            if (raw == null) {
                roomKeys.remove(world)
                CosmeticStore.clearKey(world)
                CosmeticStore.rawAt(world)?.let { CosmeticStore.applyEntry(world, it) }
            } else {
                roomKeys.add(world)
                CosmeticStore.applyEntry(world, raw)
            }
        }
    }

    fun listRooms(): List<Pair<String, Int>> { loadBlocks(); return roomBlocks.map { it.key to it.value.size } }

    fun clearRoom(name: String): Int {
        loadBlocks()
        val key = roomBlocks.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: return 0
        val n = roomBlocks.remove(key)?.size ?: 0
        scheduleSave()
        if (currentRoom?.data?.name == key) refreshLayer()
        return n
    }

    /** Full-config export: { room name: { "x,y,z": raw } } (room-relative, north-facing). */
    fun exportAll(): JsonObject { loadBlocks(); return gson.toJsonTree(roomBlocks).asJsonObject }

    /** Full-config import. [replace] wipes all rooms first; otherwise same-named rooms are merged. Returns blocks read. */
    fun importAll(obj: JsonObject, replace: Boolean): Int {
        loadBlocks()
        if (replace) roomBlocks.clear()
        var n = 0
        for ((name, el) in obj.entrySet()) {
            if (!el.isJsonObject) continue
            val map = roomBlocks.getOrPut(name) { LinkedHashMap() }
            for ((k, v) in el.asJsonObject.entrySet()) {
                if (!v.isJsonPrimitive || k.split(',').size != 3) continue
                map[k] = v.asString
                n++
            }
            if (map.isEmpty()) roomBlocks.remove(name)
        }
        flush()
        refreshLayer()
        return n
    }

    fun clearAllRooms(): Int {
        loadBlocks()
        val n = roomBlocks.values.sumOf { it.size }
        roomBlocks.clear()
        scheduleSave()
        refreshLayer()
        return n
    }

    // ------------------------------------------------------------------
    // Render-cache layer
    // ------------------------------------------------------------------

    /** Called by CosmeticStore.rebuildCache (after the world preset was applied). */
    fun reapply() {
        roomKeys.clear()
        val room = currentRoom ?: return
        if (!CosmeticConfig.roomScanning || !CosmeticConfig.inDungeon() || room.rotation == RoomRotation.NONE) return
        loadBlocks()
        val map = roomBlocks[room.data.name] ?: return
        for ((k, stored) in map) {
            val parts = k.split(',')
            if (parts.size != 3) continue
            val rel = BlockPos(parts[0].toIntOrNull() ?: continue, parts[1].toIntOrNull() ?: continue, parts[2].toIntOrNull() ?: continue)
            val world = room.rotation.fromNorth(rel).offset(room.clayPos.x, 0, room.clayPos.z)
            val key = world.asLong()
            CosmeticStore.applyEntry(key, CosmeticCodec.rotateRaw(stored, room.rotation, toNorth = false))
            roomKeys.add(key)
        }
    }

    /** Swaps the room layer in place (room changed / cleared) and re-meshes the affected blocks. */
    private fun refreshLayer() {
        val old = roomKeys.toList()
        for (k in old) {
            CosmeticStore.clearKey(k)
            CosmeticStore.rawAt(k)?.let { CosmeticStore.applyEntry(k, it) }
        }
        if (CosmeticStore.enabled) reapply() else roomKeys.clear()
        CosmeticStore.hasEntries = CosmeticStore.cache.isNotEmpty()
        CosmeticStore.queuePositions(old)
        CosmeticStore.queuePositions(roomKeys.toList())
    }

    fun reset() {
        currentRoom = null
        lastCenter = null
        roomKeys.clear()
    }

    // ------------------------------------------------------------------
    // Tick + scanner
    // ------------------------------------------------------------------

    fun tick() {
        if (saveDelay > 0 && --saveDelay == 0) flush()

        val active = CosmeticConfig.roomScanning && CosmeticConfig.inDungeon() && CosmeticStore.enabled
        if (!active) {
            if (currentRoom != null) { currentRoom = null; lastCenter = null; refreshLayer() }
            return
        }
        if (!isReady()) return
        val player = mc.player ?: return
        val level = mc.level ?: return
        val center = tileOf(player.blockPosition().x, player.blockPosition().z)
        val cur = currentRoom
        if (cur != null && cur.components.contains(center)) return
        if (center == lastCenter) return
        lastCenter = center
        val room = scanRoom(center, level) ?: return
        if (room.rotation == RoomRotation.NONE) return
        val changed = cur == null || cur.data.name != room.data.name || !cur.components.containsAll(room.components) ||
                cur.clayPos != room.clayPos
        if (changed) {
            currentRoom = room
            refreshLayer()
        }
    }

    fun tileOf(x: Int, z: Int): RoomTile {
        val rx = (x - START + 16) shr 5
        val rz = (z - START + 16) shr 5
        return RoomTile((rx shl 5) + START, (rz shl 5) + START)
    }

    private fun chunkAt(level: ClientLevel, x: Int, z: Int): LevelChunk? =
        try { level.getChunk(x shr 4, z shr 4) } catch (_: Exception) { null }

    private fun scanRoom(center: RoomTile, level: ClientLevel): ScannedRoom? {
        val chunk = chunkAt(level, center.x, center.z) ?: return null
        val height = topLayer(center.x, center.z, chunk)
        if (height <= 0) return null
        val info = coreToRoom[coreAt(center.x, center.z, height, chunk)] ?: return null
        val tiles = LinkedHashSet<RoomTile>()
        findComponents(center, info.cores, height, level, HashSet(), tiles)
        val room = ScannedRoom(info, RoomRotation.NONE, BlockPos.ZERO, tiles)
        findRotation(room, height, level)
        return room
    }

    private fun topLayer(x: Int, z: Int, chunk: LevelChunk): Int {
        for (y in 140 downTo 60) {
            mutable.set(x, y, z)
            val state = chunk.getBlockState(mutable)
            if (state.isAir) continue
            return if (state.block == Blocks.GOLD_BLOCK) y - 1 else y
        }
        return 0
    }

    /** Same hash as cb / the Odin-style room cores: the column's block names from the top down, with padding. */
    private fun coreAt(x: Int, z: Int, roomHeight: Int, chunk: LevelChunk): Int {
        val sb = StringBuilder(150)
        val clamped = roomHeight.coerceIn(11, 140)
        repeat(140 - clamped) { sb.append('0') }
        var bedrock = 0
        var y = clamped
        while (y > 11) {
            mutable.set(x, y, z)
            val block = chunk.getBlockState(mutable).block
            if (block == Blocks.AIR && bedrock >= 2 && y < 69) {
                repeat(y - 11) { sb.append('0') }
                break
            }
            if (block == Blocks.BEDROCK) {
                bedrock++
            } else {
                bedrock = 0
                if (block == Blocks.OAK_PLANKS || block == Blocks.TRAPPED_CHEST || block == Blocks.CHEST) { y--; continue }
            }
            sb.append(block)
            y--
        }
        return sb.toString().hashCode()
    }

    private fun findComponents(center: RoomTile, cores: List<Int>, height: Int, level: ClientLevel, visited: MutableSet<RoomTile>, tiles: MutableSet<RoomTile>) {
        if (!visited.add(center)) return
        val chunk = chunkAt(level, center.x, center.z) ?: return
        if (!cores.contains(coreAt(center.x, center.z, height, chunk))) return
        tiles += center
        for (dir in HORIZONTALS) {
            val nx = center.x + ((if (dir.axis == Direction.Axis.X) dir.stepX else 0) shl 5)
            val nz = center.z + ((if (dir.axis == Direction.Axis.Z) dir.stepZ else 0) shl 5)
            findComponents(RoomTile(nx, nz), cores, height, level, visited, tiles)
        }
    }

    private fun findRotation(room: ScannedRoom, height: Int, level: ClientLevel) {
        if (room.data.name == "Fairy") {
            room.components.firstOrNull()?.let {
                room.clayPos = BlockPos(it.x - 15, height, it.z - 15)
                room.rotation = RoomRotation.SOUTH
            }
            return
        }
        for (rotation in RoomRotation.entries) {
            if (rotation == RoomRotation.NONE) continue
            for (c in room.components) {
                val test = BlockPos(c.x + rotation.x, height, c.z + rotation.z)
                val state = try { level.getBlockState(test) } catch (_: Exception) { continue }
                if (state.block != Blocks.BLUE_TERRACOTTA) continue
                if (room.components.size != 1 && !validClay(test, level)) continue
                room.clayPos = test
                room.rotation = rotation
                return
            }
        }
    }

    private fun validClay(pos: BlockPos, level: ClientLevel): Boolean {
        for (dir in HORIZONTALS) {
            val block = try { level.getBlockState(pos.relative(dir)).block } catch (_: Exception) { return false }
            if (block != Blocks.AIR && block != Blocks.BLUE_TERRACOTTA) return false
        }
        return true
    }
}
