package ced.cedclient.features.impl.funqol

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.state.DungeonScanner
import ced.cedclient.state.DungeonScanner.Spot
import ced.cedclient.state.DungeonState
import ced.cedclient.state.DungeonTeam
import ced.cedclient.state.HotbarDungeonMap
import ced.cedclient.state.HotbarDungeonMap.RoomState
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.SettingsPopup
import com.google.gson.Gson
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.world.entity.player.Player
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dungeon Map HUD: the room grid DungeonScanner reads from the world blocks, drawn as a minimap in the HUD card
 * style. Rooms are coloured by type (same colours as the in-game map), same-room tiles are joined, doors are coloured
 * by key type, plus your own position and facing.
 *
 * Only rooms and doors the vanilla map in slot 9 has shown are drawn (HotbarDungeonMap), so it shows nothing more
 * than the real map: rooms it still has grey are grey here too, without a name. Checkmarks (cleared / all secrets /
 * failed) also come from that map. Teammates in render distance get their head, the rest a dot from the map's icons.
//#if CHEAT
 * "Show Undiscovered" draws everything the scanner has loaded instead, "Blood Route" the way from you to blood.
//#endif
 * All options sit in the "Map Options" popup.
 */
object DungeonMap : Module(
    "Dungeon Map",
    Category.Skyblock,
    "Minimap of the dungeon rooms and doors, scanned from the world."
), HudElement {
    private val roomNames = BooleanSetting("Room Names", true, "Write each room's name on the map.")
    private val checkmarks = BooleanSetting("Checkmarks", true, "Cleared (white), all secrets (green) and failed (red) rooms, from the dungeon map.")
    private val playerMarker = BooleanSetting("Player Marker", true, "Show where you are and which way you face.")
    private val teammates = BooleanSetting("Teammate Heads", true, "Teammates' heads where they stand; out of render distance, a dot from the dungeon map.")
//#if CHEAT
    private val showUndiscovered = BooleanSetting("Show Undiscovered", false, "Also draw rooms and doors the dungeon map hasn't shown yet (everything loaded so far).")
    private val bloodRoute = BooleanSetting("Blood Route", false, "Line from you through the doors to the blood room.")
//#endif

    private const val START = -185
    /** Room square size, gap between tiles, tile pitch, door width across the gap, padding: map units (scaled by panelScale). */
    private const val ROOM = 16f
    private const val GAP = 4f
    private const val PITCH = ROOM + GAP
    private const val DOOR = 6f
    private const val PAD = 6f
    private const val UNEXPLORED_COLOR = 0xFF404040.toInt()

    private val mc get() = Minecraft.getInstance()
    private var scanDelay = 0

    init {
//#if CHEAT
        addPopupSettings("Rooms" to listOf(roomNames, checkmarks, showUndiscovered), "Players" to listOf(playerMarker, teammates, bloodRoute))
//#else
//$        addPopupSettings("Rooms" to listOf(roomNames, checkmarks), "Players" to listOf(playerMarker, teammates))
//#endif
        ClientTickEvents.END_CLIENT_TICK.register {
            val level = mc.level ?: return@register
            val player = mc.player ?: return@register
            if (!isEnabled || !DungeonState.inDungeon || DungeonState.inBoss) return@register
            // The map (checkmarks, player icons) every tick; the world scan every 10.
            HotbarDungeonMap.update(level, player)
            if (scanDelay-- > 0) return@register
            scanDelay = 10
            DungeonScanner.scan(level, player.x, player.z)
        }
    }

    /** One "Map Options" button in the panel; [sections] open in a popup from it. */
    private fun addPopupSettings(vararg sections: Pair<String, List<BooleanSetting>>) {
        addSettings(ActionSetting("Map Options", "Room names, checkmarks, player and teammate markers.") {
            (mc.screen as? ClickGUI)?.openPopup(SettingsPopup("Dungeon Map", sections.map { (title, list) -> listOf(SettingsPopup.Section(title, list)) }))
        })
        val all = sections.flatMap { it.second }
        addSettings(*all.toTypedArray())
        all.forEach { it.inPopup = true }
    }

//#if CHEAT
    private val all get() = showUndiscovered.value
//#else
//$    private const val all = false
//#endif

    /** Room on tile ([i], [j]) if it may be drawn. */
    private fun room(i: Int, j: Int) =
        DungeonScanner.room(i, j)?.takeIf { all || (i to j) in HotbarDungeonMap.roomStates }

    /** The map still shows tile ([i], [j]) grey: drawn grey and unnamed unless everything is shown anyway. */
    private fun hidden(i: Int, j: Int) = !all && HotbarDungeonMap.roomStates[i to j] == RoomState.UNEXPLORED

    private fun tileColor(i: Int, j: Int): Int = if (hidden(i, j)) UNEXPLORED_COLOR else roomColor(room(i, j)!!.type)

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

    /** World x / z to map units (tile i's centre, world START + 32 i, sits at i * PITCH + ROOM / 2). */
    private fun toMap(world: Double) = ((world - START) / 32 * PITCH + ROOM / 2).toFloat()

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
        val heads = if (teammates.value && seen.isNotEmpty()) loadedTeammates() else emptyList()
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
            val groups = groups(seen)
            if (roomNames.value || checkmarks.value) drawLabels(groups)
//#if CHEAT
            if (bloodRoute.value) drawBloodRoute()
//#endif
            if (teammates.value) drawFarTeammates(heads)
            if (playerMarker.value) drawPlayer()
        }
        if (heads.isEmpty()) return
        // Heads are vanilla sprites: a stratum above the NanoVG card so they land on top of it.
        g.nextStratum()
        val size = maxOf(4, (7 * s).toInt())
        for ((player, color) in heads) {
            val skin = mc.connection?.getPlayerInfo(player.gameProfile.name)?.skin ?: continue
            val x = (panelX + (PAD + toMap(player.x)) * s).toInt() - size / 2
            val y = (panelY + (PAD + toMap(player.z)) * s).toInt() - size / 2
            g.fill(x - 1, y - 1, x + size + 1, y + size + 1, color)
            PlayerFaceExtractor.extractRenderState(g, skin, x, y, size)
        }
    }

    private fun drawRooms(seen: List<Pair<Int, Int>>) {
        for ((i, j) in seen) NVGRenderer.rect(i * PITCH, j * PITCH, ROOM, ROOM, tileColor(i, j))
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
                if (room(ri, rj) == null) continue
                NVGRenderer.rect(gx, gy, if (alongX) GAP else ROOM, if (alongX) ROOM else GAP, tileColor(ri, rj))
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
                if (room(ci, cj) == null) continue
                NVGRenderer.rect(ci * PITCH + ROOM, cj * PITCH + ROOM, GAP, GAP, tileColor(ci, cj))
            }
        }
    }

    /** Rooms as their tiles: tiles joined by separators belong together. */
    private fun groups(seen: List<Pair<Int, Int>>): List<List<Pair<Int, Int>>> {
        val done = HashSet<Pair<Int, Int>>()
        val out = ArrayList<List<Pair<Int, Int>>>()
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
            out += tiles
        }
        return out
    }

    /** Name and checkmark per room: in the middle of a straight room, else on its most central tile. */
    private fun drawLabels(groups: List<List<Pair<Int, Int>>>) {
        for (tiles in groups) {
            val (si, sj) = tiles.first()
            val mx = tiles.map { it.first }.average().toFloat()
            val my = tiles.map { it.second }.average().toFloat()
            val (ti, tj) = tiles.minBy { (it.first - mx) * (it.first - mx) + (it.second - my) * (it.second - my) }
            // Straight rooms (and 2x2) are labelled at their true middle, L rooms on a tile so the text stays on the room.
            val straight = tiles.size == 4 && tiles.map { it.first }.toSet().size == 2 || tiles.all { it.first == ti } || tiles.all { it.second == tj }
            val cx = (if (straight) mx else ti.toFloat()) * PITCH + ROOM / 2
            val cy = (if (straight) my else tj.toFloat()) * PITCH + ROOM / 2
            val states = tiles.mapNotNull { HotbarDungeonMap.roomStates[it] }
            val state = listOf(RoomState.FAILED, RoomState.GREEN, RoomState.CLEARED).firstOrNull { it in states }
            val named = roomNames.value && !hidden(si, sj)
            val check = checkmarks.value && state != null
            val size = 4.5f
            if (named) {
                val name = room(si, sj)!!.name
                val y = if (check) cy - size else cy - size / 2
                NVGRenderer.textShadow(name, cx - HudCard.width(name, size) / 2, y, size, -1, HudCard.font)
            }
            if (check) drawCheck(cx, if (named) cy + 3f else cy, state!!)
        }
    }

    private fun drawCheck(x: Float, y: Float, state: RoomState) {
        if (state == RoomState.FAILED) {
            val red = 0xFFFF4040.toInt()
            NVGRenderer.line(x - 2.5f, y - 2.5f, x + 2.5f, y + 2.5f, 1.4f, red)
            NVGRenderer.line(x - 2.5f, y + 2.5f, x + 2.5f, y - 2.5f, 1.4f, red)
            return
        }
        val color = if (state == RoomState.GREEN) 0xFF55FF55.toInt() else -1
        NVGRenderer.line(x - 3f, y, x - 1f, y + 2.2f, 1.4f, color)
        NVGRenderer.line(x - 1f, y + 2.2f, x + 3f, y - 2.5f, 1.4f, color)
    }

//#if CHEAT
    private fun drawBloodRoute() {
        val player = mc.player ?: return
        val blood = (0..5).flatMap { i -> (0..5).map { j -> i to j } }.firstOrNull { (i, j) -> DungeonScanner.room(i, j)?.type == "BLOOD" } ?: return
        val path = DungeonScanner.bloodPath
        if (path.isEmpty()) return
        val points = listOf(toMap(player.x) to toMap(player.z)) +
            path.map { toMap(it.x.toDouble()) to toMap(it.z.toDouble()) } +
            (blood.first * PITCH + ROOM / 2 to blood.second * PITCH + ROOM / 2)
        points.zipWithNext { (x1, y1), (x2, y2) -> NVGRenderer.line(x1, y1, x2, y2, 1.2f, 0xFFFF3030.toInt()) }
    }
//#endif

    /** Teammates in render distance, with their class colour. */
    private fun loadedTeammates(): List<Pair<Player, Int>> {
        val level = mc.level ?: return emptyList()
        val team = DungeonTeam.members().associateBy { it.name.lowercase() }
        return level.players().mapNotNull { p ->
            val member = team[p.gameProfile.name.lowercase()] ?: return@mapNotNull null
            if (p === mc.player || member.dead) null else p to DungeonTeam.classColor(member.cls)
        }
    }

    /** Dungeon map icons that are neither you nor a teammate drawn with a head: teammates out of render distance. */
    private fun drawFarTeammates(heads: List<Pair<Player, Int>>) {
        val self = mc.player ?: return
        val near = heads.map { it.first.x to it.first.z } + (self.x to self.z)
        for ((x, z) in HotbarDungeonMap.markers) {
            if (near.any { (nx, nz) -> (nx - x) * (nx - x) + (nz - z) * (nz - z) < 6 * 6 }) continue
            NVGRenderer.circle(toMap(x), toMap(z), 2f, 0xFFDDDDDD.toInt())
        }
    }

    private fun drawPlayer() {
        val player = mc.player ?: return
        val x = toMap(player.x)
        val y = toMap(player.z)
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
