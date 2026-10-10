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

    private var lastRejectWarn = 0L

    /**
     * True when an edit at [pos] has no room to go to but sits inside the dungeon's room grid. Rooms are laid out
     * differently every run, so a world-preset (absolute coordinate) block there would just reappear in whatever
     * room lands on that spot next run. Boss rooms are outside the grid and keep using the world preset.
     */
    fun rejectsWorldEdit(pos: BlockPos): Boolean {
        if (!CosmeticConfig.inDungeon() || !inGridArea(pos.x) || !inGridArea(pos.z)) return false
        val now = System.currentTimeMillis()
        if (now - lastRejectWarn > 3000L) {
            lastRejectWarn = now
            CosmeticChat.say(
                if (!CosmeticConfig.roomScanning) "§cNot placed:§f turn on Room Scanning to edit dungeon rooms"
                else "§cNot placed:§f this room isn't identified yet (hallway, or still scanning) - see /cc cosmetic rooms debug"
            )
        }
        return true
    }

    /** Whole 6x6 grid, tile edges included (tile centres START..START+160, each +-16). */
    private fun inGridArea(v: Int): Boolean = v in (START - 16)..(START + 5 * 32 + 15)

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
    //
    // Same detection as Odin (modern) / NoammAddons: each 32x32 tile's "core" is hashed from its own centre column
    // (with that tile's own highest block), matched against rooms.json; a room only counts once ALL its tiles are
    // found; the rotation comes from the blue terracotta marker at one of the room's outer corners.
    // ------------------------------------------------------------------

    /** Why the last scan did not produce a room (for /cc cosmetic rooms debug). */
    var lastScanResult = "not scanned yet"
        private set
    private var retryDelay = 0

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
        // A failed scan is retried (chunks may still be loading), just not every tick.
        if (center == lastCenter && retryDelay-- > 0) return
        lastCenter = center
        retryDelay = 10
        val room = scanRoom(center, level) ?: return
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

    /** Tile centres of the 6x6 dungeon grid. */
    private fun inGrid(t: RoomTile): Boolean {
        val ix = (t.x - START) shr 5
        val iz = (t.z - START) shr 5
        return ix in 0..5 && iz in 0..5 && (t.x - START) % 32 == 0 && (t.z - START) % 32 == 0
    }

    private fun tileCount(shape: String): Int = when (shape) {
        "1x2" -> 2
        "1x3", "L" -> 3
        "1x4", "2x2" -> 4
        else -> 1
    }

    private fun chunkAt(level: ClientLevel, x: Int, z: Int): LevelChunk? =
        try { level.getChunk(x shr 4, z shr 4) } catch (_: Exception) { null }

    private class Core(val hash: Int, val highest: Int)

    private fun scanRoom(center: RoomTile, level: ClientLevel): ScannedRoom? {
        val chunk = chunkAt(level, center.x, center.z) ?: return fail("chunk not loaded")
        val core = coreAt(center.x, center.z, chunk)
        if (core.highest == 0) return fail("tile is empty (chunk still loading?)")
        val info = coreToRoom[core.hash] ?: return fail("unknown room core ${core.hash}")

        val tiles = LinkedHashSet<RoomTile>()
        val heights = LinkedHashSet<Int>()
        findComponents(center, info.cores, level, HashSet(), tiles, heights)
        val needed = tileCount(info.shape)
        if (tiles.size < needed) return fail("${info.name}: found ${tiles.size}/$needed tiles (rest not loaded yet)")

        val room = ScannedRoom(info, RoomRotation.NONE, BlockPos.ZERO, tiles)
        findRotation(room, core.highest, heights, level)
        if (room.rotation == RoomRotation.NONE) return fail("${info.name}: no blue terracotta marker found")
        lastScanResult = "${info.name} (${info.shape}, ${room.rotation.name.lowercase()}, marker ${room.clayPos.x},${room.clayPos.z})"
        return room
    }

    private fun fail(reason: String): ScannedRoom? {
        lastScanResult = reason
        return null
    }

    /**
     * Odin's room core: the column's block ids from y=140 down, '0' for everything above the room's top
     * (air and gold), skipping planks / chests, and padding once it reaches the bedrock floor.
     */
    private fun coreAt(x: Int, z: Int, chunk: LevelChunk): Core {
        val sb = StringBuilder(1024)
        var highest = 0
        var bedrock = 0
        for (y in 140 downTo 12) {
            mutable.set(x, y, z)
            val state = chunk.getBlockState(mutable)
            if (highest == 0) {
                if (!state.isAir && state.block != Blocks.GOLD_BLOCK) highest = y
                else { sb.append('0'); continue }
            }
            val block = state.block
            if (state.isAir && bedrock >= 2 && y < 69) {
                repeat(y - 11) { sb.append('0') }
                break
            }
            if (block == Blocks.BEDROCK) {
                bedrock++
            } else {
                bedrock = 0
                if (block == Blocks.OAK_PLANKS || block == Blocks.TRAPPED_CHEST || block == Blocks.CHEST) continue
            }
            sb.append(block)
        }
        return Core(sb.toString().hashCode(), highest)
    }

    /** Flood-fills the neighbouring tiles whose own core belongs to the same room. */
    private fun findComponents(
        tile: RoomTile, cores: List<Int>, level: ClientLevel,
        visited: MutableSet<RoomTile>, tiles: MutableSet<RoomTile>, heights: MutableSet<Int>,
    ) {
        if (!inGrid(tile) || !visited.add(tile)) return
        val chunk = chunkAt(level, tile.x, tile.z) ?: return
        val core = coreAt(tile.x, tile.z, chunk)
        if (!cores.contains(core.hash)) return
        tiles += tile
        heights += core.highest
        for (dir in HORIZONTALS) {
            findComponents(RoomTile(tile.x + dir.stepX * 32, tile.z + dir.stepZ * 32), cores, level, visited, tiles, heights)
        }
    }

    /**
     * The blue terracotta sits at one outer corner of the room, at roof height. Which corner it is gives the rotation:
     * its offset from the nearest tile centre is (+-15, +-15), the same offsets [RoomRotation] uses for 1x1 rooms.
     * Straight rooms (1xN, 2x2) are checked at their bounding-box corners, L rooms at every tile corner.
     */
    private fun findRotation(room: ScannedRoom, height: Int, heights: Set<Int>, level: ClientLevel) {
        if (room.data.name == "Fairy") {
            room.components.firstOrNull()?.let {
                room.clayPos = BlockPos(it.x - 15, height, it.z - 15)
                room.rotation = RoomRotation.SOUTH
            }
            return
        }
        val ys = listOf(height) + (heights - height)
        val candidates = ArrayList<Pair<RoomRotation, Pair<Int, Int>>>()
        if (room.data.shape == "L") {
            for (c in room.components) for (rot in RoomRotation.entries) {
                if (rot != RoomRotation.NONE) candidates += rot to (c.x + rot.x to c.z + rot.z)
            }
        } else {
            val minX = room.components.minOf { it.x }
            val maxX = room.components.maxOf { it.x }
            val minZ = room.components.minOf { it.z }
            val maxZ = room.components.maxOf { it.z }
            candidates += RoomRotation.SOUTH to (minX - 15 to minZ - 15)
            candidates += RoomRotation.WEST to (maxX + 15 to minZ - 15)
            candidates += RoomRotation.NORTH to (maxX + 15 to maxZ + 15)
            candidates += RoomRotation.EAST to (minX - 15 to maxZ + 15)
        }
        for (y in ys) for ((rot, xz) in candidates) {
            val pos = BlockPos(xz.first, y, xz.second)
            val state = try { level.getBlockState(pos) } catch (_: Exception) { continue }
            if (state.block != Blocks.BLUE_TERRACOTTA) continue
            room.clayPos = pos
            room.rotation = rot
            return
        }
    }

    /** Lines for /cc cosmetic rooms debug. */
    fun debugInfo(): List<String> {
        val player = mc.player
        val tile = player?.let { tileOf(it.blockPosition().x, it.blockPosition().z) }
        val core = tile?.let { t -> mc.level?.let { chunkAt(it, t.x, t.z) }?.let { coreAt(t.x, t.z, it) } }
        return listOf(
            "In dungeon: ${CosmeticConfig.inDungeon()}, Room Scanning: ${CosmeticConfig.roomScanning}, module on: ${CosmeticStore.enabled}",
            "Room database: ${coreToRoom.size} cores loaded",
            "Tile: ${tile?.let { "${it.x},${it.z}" } ?: "-"}, core ${core?.hash ?: "-"} (top y ${core?.highest ?: "-"}) -> ${core?.let { coreToRoom[it.hash]?.name } ?: "no match"}",
            "Current room: ${currentRoom?.let { "${it.data.name} (${it.rotation.name.lowercase()}, ${it.components.size} tile(s))" } ?: "none"}",
            "Last scan: $lastScanResult",
        )
    }
}
