package ced.cedclient.features.impl.funqol

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.state.DungeonScanner
import ced.cedclient.state.DungeonScanner.Spot
import ced.cedclient.state.DungeonState
import ced.cedclient.state.HotbarDungeonMap
import com.google.gson.Gson
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dungeon Map HUD: the room grid DungeonScanner reads from the world blocks, drawn as a minimap in the HUD card
 * style. Rooms are coloured by type (same colours as the in-game map), same-room tiles are joined, doors are coloured
 * by key type, plus your own position and facing.
 *
 * Only rooms and doors the vanilla map in slot 9 has shown are drawn (HotbarDungeonMap), so it shows nothing more
 * than the real map.
//#if CHEAT
 * "Show Undiscovered" draws everything the scanner has loaded instead.
//#endif
 */
object DungeonMap : Module(
    "Dungeon Map",
    Category.Skyblock,
    "Minimap of the dungeon rooms and doors, scanned from the world."
), HudElement {
    private val roomNames = BooleanSetting("Room Names", true, "Write each room's name on the map.")
    private val playerMarker = BooleanSetting("Player Marker", true, "Show where you are and which way you face.")
//#if CHEAT
    private val showUndiscovered = BooleanSetting("Show Undiscovered", false, "Also draw rooms and doors the dungeon map hasn't shown yet (everything loaded so far).")
//#endif

    private const val START = -185
    /** Room square size, gap between tiles, tile pitch, door width across the gap, padding: map units (scaled by panelScale). */
    private const val ROOM = 16f
    private const val GAP = 4f
    private const val PITCH = ROOM + GAP
    private const val DOOR = 6f
    private const val PAD = 6f

    private val mc get() = Minecraft.getInstance()
    private var scanDelay = 0

    init {
//#if CHEAT
        addSettings(roomNames, playerMarker, showUndiscovered)
//#else
//$        addSettings(roomNames, playerMarker)
//#endif
        ClientTickEvents.END_CLIENT_TICK.register {
            val level = mc.level ?: return@register
            val player = mc.player ?: return@register
            if (!isEnabled || !DungeonState.inDungeon || DungeonState.inBoss || scanDelay-- > 0) return@register
            scanDelay = 10
            DungeonScanner.scan(level, player.x, player.z)
            HotbarDungeonMap.update(level, player)
        }
    }

//#if CHEAT
    private val all get() = showUndiscovered.value
//#else
//$    private const val all = false
//#endif

    /** Room on tile ([i], [j]) if it may be drawn. */
    private fun room(i: Int, j: Int) =
        DungeonScanner.room(i, j)?.takeIf { all || (i to j) in HotbarDungeonMap.discoveredRooms }

    /** Door / separator on grid spot ([i], [j]) if it may be drawn. */
    private fun spot(i: Int, j: Int) = DungeonScanner.spot(i, j)?.takeIf {
        all || if (it == Spot.SEPARATOR) true else (START + 16 * i to START + 16 * j) in HotbarDungeonMap.discoveredDoors
    }

    private fun roomColor(type: String): Int = when (type) {
        "PUZZLE" -> 0xFF75009D.toInt()
        "TRAP" -> 0xFFD87F33.toInt()
        "CHAMPION" -> 0xFFFEDF00.toInt()
        "BLOOD" -> 0xFFE00000.toInt()
        "ENTRANCE" -> 0xFF148500.toInt()
        "FAIRY" -> 0xFFE000FF.toInt()
        else -> 0xFF6B3A11.toInt()
    }

    private fun doorColor(spot: Spot): Int = when (spot) {
        Spot.WITHER -> 0xFF0A0A0A.toInt()
        Spot.BLOOD -> 0xFFE00000.toInt()
        Spot.ENTRANCE -> 0xFF148500.toInt()
        else -> 0xFF6B3A11.toInt()
    }

    fun render(g: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
        if (!isEnabled || mc.options.hideGui || !DungeonState.inDungeon || DungeonState.inBoss) return
        renderInternal(g)
    }

    override fun renderInternal(g: GuiGraphicsExtractor) {
        val seen = (0..5).flatMap { i -> (0..5).map { j -> i to j } }.filter { (i, j) -> room(i, j) != null }
        val cols = (seen.maxOfOrNull { it.first } ?: 5) + 1
        val rows = (seen.maxOfOrNull { it.second } ?: 5) + 1
        val width = cols * PITCH - GAP + PAD * 2
        val height = rows * PITCH - GAP + PAD * 2
        lastWidth = width.toInt()
        lastHeight = height.toInt()
        val s = panelScale
        HudCard.draw(g, panelX, panelY) {
            NVGRenderer.scale(s, s)
            HudCard.card(width, height, 1f)
            NVGRenderer.translate(PAD, PAD)
            if (seen.isEmpty()) {
                val text = "Dungeon Map"
                HudCard.text(text, (width - PAD * 2 - HudCard.width(text, 8f)) / 2, (height - PAD * 2 - 8f) / 2, 8f, 0xFFBBBBBB.toInt())
                return@draw
            }
            drawRooms(seen)
            if (roomNames.value) drawNames(seen)
            if (playerMarker.value) drawPlayer()
        }
    }

    private fun drawRooms(seen: List<Pair<Int, Int>>) {
        for ((i, j) in seen) NVGRenderer.rect(i * PITCH, j * PITCH, ROOM, ROOM, roomColor(room(i, j)!!.type))
        for (si in 0..10) for (sj in 0..10) {
            if ((si % 2 == 1) == (sj % 2 == 1)) continue
            val spot = spot(si, sj) ?: continue
            // Room on the low side of the gap (si odd: gap along x).
            val alongX = si % 2 == 1
            val (ri, rj) = if (alongX) (si - 1) / 2 to sj / 2 else si / 2 to (sj - 1) / 2
            val gx = if (alongX) ri * PITCH + ROOM else ri * PITCH
            val gy = if (alongX) rj * PITCH else rj * PITCH + ROOM
            if (spot == Spot.SEPARATOR) {
                // Same room on both sides: fill the whole gap in the room's colour.
                val room = room(ri, rj) ?: continue
                NVGRenderer.rect(gx, gy, if (alongX) GAP else ROOM, if (alongX) ROOM else GAP, roomColor(room.type))
            } else {
                val off = (ROOM - DOOR) / 2
                NVGRenderer.rect(if (alongX) gx else gx + off, if (alongX) gy + off else gy, if (alongX) GAP else DOOR, if (alongX) DOOR else GAP, doorColor(spot))
            }
        }
        // Middle of a 2x2 room: the four gaps around it are all separators.
        for (ci in 0 until 5) for (cj in 0 until 5) {
            val i = ci * 2 + 1
            val j = cj * 2 + 1
            if (listOf(i - 1 to j, i + 1 to j, i to j - 1, i to j + 1).all { (a, b) -> spot(a, b) == Spot.SEPARATOR }) {
                val room = room(ci, cj) ?: continue
                NVGRenderer.rect(ci * PITCH + ROOM, cj * PITCH + ROOM, GAP, GAP, roomColor(room.type))
            }
        }
    }

    /** One name per room: the tiles joined by separators, labelled in the middle of a straight room, else on its most central tile. */
    private fun drawNames(seen: List<Pair<Int, Int>>) {
        val done = HashSet<Pair<Int, Int>>()
        for (start in seen) {
            if (start in done) continue
            val tiles = ArrayList<Pair<Int, Int>>()
            val queue = ArrayDeque(listOf(start))
            done += start
            while (queue.isNotEmpty()) {
                val (i, j) = queue.removeFirst()
                tiles += i to j
                for ((di, dj) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val n = i + di to j + dj
                    if (n in done || n !in seen || spot(i * 2 + di, j * 2 + dj) != Spot.SEPARATOR) continue
                    done += n
                    queue.addLast(n)
                }
            }
            val name = room(start.first, start.second)!!.name
            val mx = tiles.map { it.first }.average().toFloat()
            val my = tiles.map { it.second }.average().toFloat()
            val (ti, tj) = tiles.minBy { (it.first - mx) * (it.first - mx) + (it.second - my) * (it.second - my) }
            // Straight rooms (and 2x2) are labelled at their true middle, L rooms on a tile so the text stays on the room.
            val straight = tiles.size == 4 && tiles.map { it.first }.toSet().size == 2 || tiles.all { it.first == ti } || tiles.all { it.second == tj }
            val cx = (if (straight) mx else ti.toFloat()) * PITCH + ROOM / 2
            val cy = (if (straight) my else tj.toFloat()) * PITCH + ROOM / 2
            val size = 4.5f
            NVGRenderer.textShadow(name, cx - HudCard.width(name, size) / 2, cy - size / 2, size, -1, HudCard.font)
        }
    }

    private fun drawPlayer() {
        val player = mc.player ?: return
        // Tile i's centre (world START + 32 i) sits at map i * PITCH + ROOM / 2.
        val x = ((player.x - START) / 32 * PITCH + ROOM / 2).toFloat()
        val y = ((player.z - START) / 32 * PITCH + ROOM / 2).toFloat()
        // Yaw 0 faces +z (down on the map), 90 faces -x.
        val yaw = Math.toRadians(player.yRot.toDouble())
        NVGRenderer.line(x, y, x - sin(yaw).toFloat() * 5f, y + cos(yaw).toFloat() * 5f, 1.2f, -1)
        NVGRenderer.circle(x, y, 2.2f, 0xFF00FF00.toInt())
    }

    override val label = "Dungeon Map"
    private const val DEFAULT_X = 1140
    private const val DEFAULT_Y = 10
    override var panelX = DEFAULT_X
    override var panelY = DEFAULT_Y
    override var panelScale = 1f
    override var lastWidth = 0
        private set
    override var lastHeight = 0
        private set

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val gson = Gson()
    private val hudFile: File by lazy { CedPaths.file("dungeon_map_hud.json") }

    init {
        runCatching { gson.fromJson(hudFile.readText(), HudPosition::class.java) }.getOrNull()?.let {
            panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.4f, 3f)
        }
    }

    override fun save() {
        runCatching { hudFile.parentFile?.mkdirs(); hudFile.writeText(gson.toJson(HudPosition(panelX, panelY, panelScale))) }
    }

    override fun resetPosition() { panelX = DEFAULT_X; panelY = DEFAULT_Y; panelScale = 1f }
    override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.4f, 3f) }
}
