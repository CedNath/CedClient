package ced.cedclient.features.impl.funqol

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.misc.Theme
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.RenderableSetting
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.state.DungeonRunInfo
import ced.cedclient.state.DungeonScanner
import ced.cedclient.state.DungeonScanner.Spot
import ced.cedclient.state.DungeonState
import ced.cedclient.state.DungeonTeam
import ced.cedclient.state.HotbarDungeonMap
import ced.cedclient.state.HotbarDungeonMap.RoomState
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.SettingsPopup
import ced.cedclient.utils.Color
import com.google.gson.Gson
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.multiplayer.ClientLevel
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dungeon Map HUD: the room grid DungeonScanner reads from the world blocks, drawn as a minimap in the HUD card
 * style. Rooms are coloured by type (colours are settings), same-room tiles are joined, doors are coloured by key type.
 *
 * Only rooms and doors the vanilla map in slot 9 has shown are drawn (HotbarDungeonMap), so it shows nothing more
 * than the real map: rooms it still has grey are grey here too, without a name. Checkmarks (cleared / all secrets /
 * failed) also come from that map; found secrets come from the action bar's "x/y Secrets" while you stand in a room.
 * Players are arrows (class colour, you green) or skin heads: in render distance from the player entity, further away
 * from the map's icons, which follow the party order (your own icon is the frame one).
//#if CHEAT
 * "Show Undiscovered" also draws everything the scanner has loaded (darkened until the map shows it), "Blood Route"
 * the way from you to blood until the blood door opens.
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
    private val secrets = BooleanSetting("Secrets", true, "Found / total secrets under each room's name (found counts once you've been in the room).")
    private val runInfo = BooleanSetting("Run Info", true, "Two lines under the map: estimated score, secrets, crypts, deaths, bat / mimic / prince (also from other mods' chat lines).")
    private val playerMarker = BooleanSetting("Player Marker", true, "Show where you are and which way you face.")
    private val teammates = BooleanSetting("Teammates", true, "Show where your teammates are.")
    private val markerStyle = DropdownSetting("Marker Style", listOf("Arrow", "Head"), "Arrow", "Arrows (teammates in their class colour) or skin heads with a class-coloured border.")
//#if CHEAT
    private val showUndiscovered = BooleanSetting("Show Undiscovered", false, "Also draw rooms and doors the dungeon map hasn't shown yet (everything loaded so far).")
    private val bloodRoute = BooleanSetting("Blood Route", false, "Line from you through the doors to the blood room.")
//#endif
    private val background = BooleanSetting("Background", true, "Dark card behind the map.")
    private val backgroundOpacity = NumberSetting("Background Opacity", 90.0, 0.0, 100.0, 5.0, "How see-through the card is (percent).")
    private val border = BooleanSetting("Border", true, "Accent-coloured border around the card.")
    private val normalColor = ColorSetting("Normal Room", Color(107, 58, 17))
    private val puzzleColor = ColorSetting("Puzzle Room", Color(117, 0, 157))
    private val trapColor = ColorSetting("Trap Room", Color(216, 127, 51))
    private val minibossColor = ColorSetting("Miniboss Room", Color(254, 223, 0))
    private val bloodColor = ColorSetting("Blood Room", Color(224, 0, 0))
    private val entranceColor = ColorSetting("Entrance Room", Color(20, 133, 0))
    private val fairyColor = ColorSetting("Fairy Room", Color(224, 0, 255))
    private val unopenedColor = ColorSetting("Unopened Room", Color(64, 64, 64))
    private val witherDoorColor = ColorSetting("Wither Door", Color(10, 10, 10))

    private const val START = -185
    /** Room square size, gap between tiles, tile pitch, door width across the gap: map units (scaled by panelScale). */
    private const val ROOM = 16f
    private const val GAP = 4f
    private const val PITCH = ROOM + GAP
    private const val DOOR = 6f
    /** Card size: the vanilla map's 128 x 128, rooms where that map draws them. */
    private const val CARD = 128f
    private const val CHECK_H = 6f
    /** Height of the two Run Info lines under the map. */
    private const val INFO_H = 20f
    private val CODES = Regex("§.")
    private val SECRETS = Regex("""(\d+)/(\d+) Secrets""")
    /** The world the blood door was opened in (Blood Route stops there). */
    private var bloodOpenLevel: ClientLevel? = null
    /** Room name -> secrets found, from the action bar, for [secretsLevel]. */
    private val secretsFound = HashMap<String, Int>()
    private var secretsLevel: ClientLevel? = null

    private val mc get() = Minecraft.getInstance()
    private var scanDelay = 0

    init {
//#if CHEAT
        addPopupSettings(
            listOf("Rooms" to listOf(roomNames, checkmarks, secrets, runInfo, showUndiscovered), "Players" to listOf(playerMarker, teammates, markerStyle, bloodRoute)),
            listOf("Card" to listOf(background, backgroundOpacity, border), "Colours" to listOf(normalColor, puzzleColor, trapColor, minibossColor, bloodColor, entranceColor, fairyColor, unopenedColor, witherDoorColor)),
        )
//#else
//$        addPopupSettings(
//$            listOf("Rooms" to listOf(roomNames, checkmarks, secrets, runInfo), "Players" to listOf(playerMarker, teammates, markerStyle)),
//$            listOf("Card" to listOf(background, backgroundOpacity, border), "Colours" to listOf(normalColor, puzzleColor, trapColor, minibossColor, bloodColor, entranceColor, fairyColor, unopenedColor, witherDoorColor)),
//$        )
//#endif
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            val plain = message.string.replace(CODES, "").trim()
            if (plain == "The BLOOD DOOR has been opened!") bloodOpenLevel = mc.level
            if (overlay) SECRETS.find(plain)?.let { onSecrets(it.groupValues[1].toInt()) }
        }
        ClientTickEvents.END_CLIENT_TICK.register {
            val level = mc.level ?: return@register
            val player = mc.player ?: return@register
            if (!isEnabled || !DungeonState.inDungeon || DungeonState.inBoss) return@register
            // The map (checkmarks, player icons) every tick; the world scan every 10.
            HotbarDungeonMap.update(level, player)
            DungeonRunInfo.update()
            if (scanDelay-- > 0) return@register
            scanDelay = 10
            DungeonScanner.scan(level, player.x, player.z)
        }
    }

    /** One "Map Options" button in the panel; each column's sections open in a popup from it. */
    private fun addPopupSettings(vararg columns: List<Pair<String, List<RenderableSetting<*>>>>) {
        addSettings(ActionSetting("Map Options", "Rooms, players, card style and colours.") {
            (mc.screen as? ClickGUI)?.openPopup(SettingsPopup("Dungeon Map", columns.map { col -> col.map { (title, list) -> SettingsPopup.Section(title, list) } }, height = 650f))
        })
        val all = columns.flatMap { col -> col.flatMap { it.second } }
        addSettings(*all.toTypedArray())
        all.forEach { it.inPopup = true }
    }

    /** "x/y Secrets" in the action bar: the count for the room you stand in. */
    private fun onSecrets(found: Int) {
        val player = mc.player ?: return
        if (secretsLevel !== mc.level) { secretsLevel = mc.level; secretsFound.clear() }
        val i = Math.floorDiv(player.x.toInt() - START + 16, 32)
        val j = Math.floorDiv(player.z.toInt() - START + 16, 32)
        if (i !in 0..5 || j !in 0..5) return
        val name = DungeonScanner.room(i, j)?.name ?: return
        secretsFound[name] = maxOf(found, secretsFound[name] ?: 0)
    }

//#if CHEAT
    private val all get() = showUndiscovered.value
//#else
//$    private const val all = false
//#endif

    /** Room on tile ([i], [j]) if it may be drawn. */
    private fun room(i: Int, j: Int) =
        DungeonScanner.room(i, j)?.takeIf { all || (i to j) in HotbarDungeonMap.roomStates }

    /** The dungeon map shows tile ([i], [j]) as an opened room (not grey, not missing). */
    private fun explored(i: Int, j: Int) = HotbarDungeonMap.roomStates[i to j].let { it != null && it != RoomState.UNEXPLORED }

    /** Not opened yet and only known from the map's grey: drawn grey and unnamed (Show Undiscovered: darkened instead). */
    private fun hidden(i: Int, j: Int) = !all && !explored(i, j)

    private fun tileColor(i: Int, j: Int): Int = when {
        explored(i, j) -> roomColor(room(i, j)!!.type)
        all -> darken(roomColor(room(i, j)!!.type))
        else -> unopenedColor.value.rgba
    }

    private fun darken(argb: Int): Int {
        fun ch(shift: Int) = ((argb ushr shift and 0xFF) * 0.35f).toInt() shl shift
        return (argb and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
    }

    /**
     * Door / separator on grid spot ([i], [j]) if it may be drawn. A "separator" between two different rooms is a
     * door whose roof isn't at a door height (the entrance's): drawn as a door, green when it leads out of the entrance.
     */
    private fun spot(i: Int, j: Int): Spot? {
        var spot = DungeonScanner.spot(i, j) ?: return null
        if (spot == Spot.SEPARATOR) {
            val (a, b) = if (i % 2 == 1) DungeonScanner.room((i - 1) / 2, j / 2) to DungeonScanner.room((i + 1) / 2, j / 2)
                else DungeonScanner.room(i / 2, (j - 1) / 2) to DungeonScanner.room(i / 2, (j + 1) / 2)
            if (a != null && b != null && a.name != b.name) spot = if (a.type == "ENTRANCE" || b.type == "ENTRANCE") Spot.ENTRANCE else Spot.NORMAL
        }
        if (all || spot == Spot.SEPARATOR || (START + 16 * i to START + 16 * j) in HotbarDungeonMap.discoveredDoors) return spot
        return null
    }

    private fun roomColor(type: String): Int = when (type) {
        "PUZZLE" -> puzzleColor
        "TRAP" -> trapColor
        "CHAMPION" -> minibossColor
        "BLOOD" -> bloodColor
        "ENTRANCE" -> entranceColor
        "FAIRY" -> fairyColor
        else -> normalColor
    }.value.rgba

    private fun doorColor(spot: Spot): Int = when (spot) {
        Spot.WITHER -> witherDoorColor.value.rgba
        Spot.BLOOD -> bloodColor.value.rgba
        Spot.ENTRANCE -> entranceColor.value.rgba
        else -> normalColor.value.rgba
    }

    /** World x / z to map units (tile i's centre, world START + 32 i, sits at i * PITCH + ROOM / 2). */
    private fun toMap(world: Double) = ((world - START) / 32 * PITCH + ROOM / 2).toFloat()

    fun render(g: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
        if (!isEnabled || mc.options.hideGui || !DungeonState.inDungeon || DungeonState.inBoss) return
        renderInternal(g)
    }

    /** A player to draw: world position, yaw, name (for the skin), marker colour. */
    private class Mark(val x: Double, val z: Double, val yaw: Float, val name: String, val color: Int)

    override fun renderInternal(g: GuiGraphicsExtractor) {
        val seen = (0..5).flatMap { i -> (0..5).map { j -> i to j } }.filter { (i, j) -> room(i, j) != null }
        val infoH = if (runInfo.value) INFO_H else 0f
        lastWidth = CARD.toInt()
        lastHeight = (CARD + infoH).toInt()
        val s = panelScale
        // Fixed card like the vanilla map: once it lines up, rooms go where it draws them (its corner, 16 or 18 px
        // rooms); before that the scanned grid is centred.
        val k: Float
        val ox: Float
        val oy: Float
        if (HotbarDungeonMap.calibrated) {
            k = (HotbarDungeonMap.roomSize + GAP) / PITCH
            ox = HotbarDungeonMap.cornerX.toFloat()
            oy = HotbarDungeonMap.cornerZ.toFloat()
        } else {
            val scanned = (0..5).flatMap { i -> (0..5).map { j -> i to j } }.filter { (i, j) -> DungeonScanner.room(i, j) != null }
            val w = ((scanned.maxOfOrNull { it.first } ?: 5) + 1) * PITCH - GAP
            val h = ((scanned.maxOfOrNull { it.second } ?: 5) + 1) * PITCH - GAP
            k = minOf(1f, (CARD - 8f) / maxOf(w, h))
            ox = (CARD - w * k) / 2
            oy = (CARD - h * k) / 2
        }
        val marks = if (seen.isEmpty()) emptyList() else marks()
        val heads = markerStyle.value == "Head"
        HudCard.draw(g, panelX, panelY) {
            NVGRenderer.scale(s, s)
            if (background.value) {
                val a = (backgroundOpacity.value / 100 * 255).toInt().coerceIn(0, 255)
                NVGRenderer.rect(0f, 0f, CARD, CARD + infoH, (a shl 24) or (HudCard.BACKGROUND and 0xFFFFFF), 8f)
            }
            if (border.value) NVGRenderer.hollowRect(0f, 0f, CARD, CARD + infoH, 1f, Theme.hudBorderRgba, 8f)
            if (runInfo.value) drawRunInfo()
            if (seen.isEmpty()) {
                val text = "Dungeon Map"
                HudCard.text(text, (CARD - HudCard.width(text, 8f)) / 2, (CARD - 8f) / 2, 8f, 0xFFBBBBBB.toInt())
                return@draw
            }
            NVGRenderer.translate(ox, oy)
            NVGRenderer.scale(k, k)
            drawRooms(seen)
            if (roomNames.value || checkmarks.value || secrets.value) drawLabels(groups(seen))
//#if CHEAT
            if (bloodRoute.value && bloodOpenLevel !== mc.level) drawBloodRoute()
//#endif
            if (!heads) for (m in marks) drawArrow(toMap(m.x), toMap(m.z), m.yaw, m.color)
        }
        if (!heads || marks.isEmpty()) return
        // Heads are vanilla sprites: a stratum above the NanoVG card so they land on top of it.
        g.nextStratum()
        val size = maxOf(4, (7 * s * k).toInt())
        for (m in marks) {
            val skin = mc.connection?.getPlayerInfo(m.name)?.skin ?: continue
            // Turned with the player: the top of the head points where they face (yaw 0 = down the map).
            g.pose().pushMatrix()
            g.pose().translate(panelX + (ox + toMap(m.x) * k) * s, panelY + (oy + toMap(m.z) * k) * s)
            g.pose().rotate(Math.toRadians(m.yaw + 180.0).toFloat())
            g.fill(-size / 2 - 1, -size / 2 - 1, size / 2 + 1 + size % 2, size / 2 + 1 + size % 2, m.color)
            PlayerFaceExtractor.extractRenderState(g, skin, -size / 2, -size / 2, size)
            g.pose().popMatrix()
        }
    }

    /**
     * Teammates first (so you draw on top), then you. A teammate in render distance uses their entity; otherwise the
     * map icon at their place in the party order (living teammates, tab order, your own frame icon left out).
     */
    private fun marks(): List<Mark> {
        val self = mc.player ?: return emptyList()
        val out = ArrayList<Mark>()
        if (teammates.value) {
            val icons = HotbarDungeonMap.markers.filter { !it.self }
            DungeonTeam.members().filter { !it.dead }.forEachIndexed { i, m ->
                val color = DungeonTeam.classColor(m.cls)
                val entity = mc.level?.players()?.firstOrNull { it !== self && it.gameProfile.name.equals(m.name, ignoreCase = true) }
                if (entity != null) out += Mark(entity.x, entity.z, entity.yRot, m.name, color)
                else icons.getOrNull(i)?.let { out += Mark(it.x, it.z, it.yaw, m.name, color) }
            }
        }
        if (playerMarker.value) out += Mark(self.x, self.z, self.yRot, self.gameProfile.name, 0xFF55FF55.toInt())
        return out
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
                val known = (START + 16 * si to START + 16 * sj) in HotbarDungeonMap.discoveredDoors
                val color = if (known || spot == Spot.ENTRANCE) doorColor(spot) else darken(doorColor(spot))
                NVGRenderer.rect(if (alongX) gx else gx + off, if (alongX) gy + off else gy, if (alongX) GAP else DOOR, if (alongX) DOOR else GAP, color)
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

    /** Name, secrets and checkmark per room: in the middle of a straight room, else on its most central tile. */
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
            // Room the text may use: the whole room for straight ones, one tile for L rooms.
            val spanW = if (straight) (tiles.maxOf { it.first } - tiles.minOf { it.first } + 1) * PITCH - GAP else ROOM
            val spanH = if (straight) (tiles.maxOf { it.second } - tiles.minOf { it.second } + 1) * PITCH - GAP else ROOM
            val states = tiles.mapNotNull { HotbarDungeonMap.roomStates[it] }
            val state = listOf(RoomState.FAILED, RoomState.GREEN, RoomState.CLEARED).firstOrNull { it in states }
            val info = room(si, sj)!!
            val shown = !hidden(si, sj)
            val check = checkmarks.value && state != null
            val secretLine = if (secrets.value && shown && info.secrets > 0) "${secretsFound[info.name]?.toString() ?: "?"}/${info.secrets}" else null
            val nameLines = if (roomNames.value && shown) info.name else null
            val secretH = if (secretLine != null) 4f else 0f
            val (lines, size) = if (nameLines != null) fit(nameLines, spanW - 2f, spanH - 2f - secretH - if (check) CHECK_H else 0f) else emptyList<String>() to 0f
            val lineH = size * 1.1f
            val blockH = lines.size * lineH + secretH + if (check) CHECK_H else 0f
            var y = cy - blockH / 2
            val color = if (explored(si, sj)) -1 else 0xFF9A9A9A.toInt()
            for (line in lines) {
                HudCard.text(line, cx - HudCard.width(line, size) / 2, y, size, color)
                y += lineH
            }
            if (secretLine != null) {
                val done = (secretsFound[info.name] ?: 0) >= info.secrets || state == RoomState.GREEN
                HudCard.text(secretLine, cx - HudCard.width(secretLine, 3.5f) / 2, y, 3.5f, if (done) 0xFF55FF55.toInt() else 0xFFCCCCCC.toInt())
                y += secretH
            }
            if (check) drawCheck(cx, y + CHECK_H / 2, state!!)
        }
    }

    /** [name] word-wrapped into [maxW] x [maxH]: the biggest size from 4.5 down that fits, as lines + size. */
    private fun fit(name: String, maxW: Float, maxH: Float): Pair<List<String>, Float> {
        val words = name.split(' ')
        var size = 4.5f
        while (true) {
            val lines = ArrayList<String>()
            for (word in words) {
                val joined = lines.lastOrNull()?.let { "$it $word" }
                if (joined != null && HudCard.width(joined, size) <= maxW) lines[lines.size - 1] = joined else lines += word
            }
            val fits = lines.all { HudCard.width(it, size) <= maxW } && lines.size * size * 1.1f <= maxH
            if (fits || size <= 2.5f) return lines to size
            size -= 0.25f
        }
    }

    /** One stroke with round joins, so the tick has no seam. */
    private fun drawCheck(x: Float, y: Float, state: RoomState) {
        if (state == RoomState.FAILED) {
            val red = 0xFFFF4040.toInt()
            NVGRenderer.strokePath(floatArrayOf(x - 2.2f, y - 2.2f, x + 2.2f, y + 2.2f), 1.3f, red)
            NVGRenderer.strokePath(floatArrayOf(x - 2.2f, y + 2.2f, x + 2.2f, y - 2.2f), 1.3f, red)
            return
        }
        val color = if (state == RoomState.GREEN) 0xFF55FF55.toInt() else -1
        NVGRenderer.strokePath(floatArrayOf(x - 2.6f, y + 0.1f, x - 0.8f, y + 1.9f, x + 2.6f, y - 1.9f), 1.3f, color)
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
        NVGRenderer.strokePath(points.flatMap { listOf(it.first, it.second) }.toFloatArray(), 1.2f, 0xFFFF3030.toInt())
    }
//#endif

    /**
     * Arrow pointing along [yaw] (0 = +z, down on the map). A plain triangle: NanoVG fills concave shapes through
     * the stencil buffer, which the HUD canvas doesn't have, so a notched arrow came out as its bounding box.
     */
    private fun drawArrow(x: Float, y: Float, yaw: Float, color: Int) {
        val r = Math.toRadians(yaw.toDouble())
        val fx = -sin(r).toFloat()
        val fy = cos(r).toFloat()
        val tipX = x + fx * 4f; val tipY = y + fy * 4f
        val lX = x - fx * 3f - fy * 3f; val lY = y - fy * 3f + fx * 3f
        val nX = x - fx * 1.5f; val nY = y - fy * 1.5f
        val rX = x - fx * 3f + fy * 3f; val rY = y - fy * 3f - fx * 3f
        // The notched arrow as two convex halves (no stencil needed), the seam painted over, then one outline.
        NVGRenderer.polygon(floatArrayOf(tipX, tipY, lX, lY, nX, nY), color)
        NVGRenderer.polygon(floatArrayOf(tipX, tipY, nX, nY, rX, rY), color)
        NVGRenderer.line(tipX, tipY, nX, nY, 0.6f, color)
        NVGRenderer.strokePath(floatArrayOf(tipX, tipY, lX, lY, nX, nY, rX, rY, tipX, tipY), 0.8f, 0xFF0A0A0A.toInt())
    }

    /** Score estimate and secrets on the first line, crypts / deaths / bat / mimic / prince on the second. */
    private fun drawRunInfo() {
        val r = DungeonRunInfo
        val size = 6f
        val green = 0xFF55FF55.toInt()
        val red = 0xFFFF5555.toInt()
        val grey = 0xFFAAAAAA.toInt()
        fun line(y: Float, parts: List<Pair<String, Int>>) {
            var x = (CARD - parts.sumOf { HudCard.width(it.first, size).toDouble() }.toFloat()) / 2
            for ((text, color) in parts) { HudCard.text(text, x, y, size, color); x += HudCard.width(text, size) }
        }
        val score = r.score
        val scoreColor = if (score >= 300) green else if (score >= 270) 0xFFFFFF55.toInt() else red
        val secretsColor = if (r.secretsPct >= r.requiredSecrets) green else -1
        line(CARD + 1f, listOf("Score ~" to grey, "$score" to scoreColor, "   Secrets " to grey, "%.0f%%".format(r.secretsPct) to secretsColor, "/${r.requiredSecrets}%" to grey))
        line(CARD + 10f, listOf("Crypts " to grey, "${r.crypts}" to if (r.crypts >= 5) green else -1, "  Deaths " to grey, "${r.deaths}" to if (r.deaths == 0) green else red,
            "  Bat" to if (r.bat) green else red, "  Mimic" to if (r.mimic) green else red, "  Prince" to if (r.prince) green else red))
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
