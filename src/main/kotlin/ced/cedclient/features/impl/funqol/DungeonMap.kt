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
import ced.cedclient.render.cosmetic.RoomInfo
import ced.cedclient.state.HotbarDungeonMap
import ced.cedclient.utils.skyblockId
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
//#if CHEAT
import ced.cedclient.render.LineBox
import ced.cedclient.render.pipeline.CustomRenderType
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.AABB
//#endif
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
 * the way from you to blood until the blood door opens. "Mimic Room" / "Mimic ESP" find the mimic chest (F6 / F7).
//#endif
 * All options sit in the "Map Options" popup.
 */
object DungeonMap : Module(
    "Dungeon Map",
    Category.Skyblock,
    "Minimap of the dungeon rooms and doors, scanned from the world."
), HudElement {
    private val labelMode = DropdownSetting("Room Labels", listOf("Name + Secrets", "Name", "Secrets", "Checkmarks Only"), "Name + Secrets",
        "What each room shows besides its checkmark: its name, found / total secrets (found counts once you've been in the room), both, or nothing.")
    private val labelSize = NumberSetting("Label Size", 1.0, 0.5, 2.0, 0.05, "Size of the room text. It still shrinks to stay inside the room box.")
    private val showName get() = labelMode.value.startsWith("Name")
    private val showSecrets get() = labelMode.value.endsWith("Secrets")
    private val checkmarks = BooleanSetting("Checkmarks", true, "Cleared (white), all secrets (green) and failed (red) rooms, from the dungeon map.")
    private val runInfo = BooleanSetting("Run Info", true, "Two lines under the map: estimated score, secrets, crypts, deaths, bat / mimic / prince (also from other mods' chat lines).")
    private val clearSummary = BooleanSetting("Clear Summary", true, "When the run ends, prints in chat who cleared which rooms (solo / stacked, hover for the names) and who died.")
    private val playerMarker = BooleanSetting("Player Marker", true, "Show where you are and which way you face.")
    private val playerNames = DropdownSetting("Player Names", listOf("While Holding Leap", "Always", "Never"), "While Holding Leap", "Names under the player markers.")
    private val teammates = BooleanSetting("Teammates", true, "Show where your teammates are.")
    private val markerStyle = DropdownSetting("Marker Style", listOf("Arrow", "Head"), "Arrow", "Arrows (teammates in their class colour) or skin heads with a class-coloured border.")
//#if CHEAT
    private val showUndiscovered = BooleanSetting("Show Undiscovered", false, "Also draw rooms and doors the dungeon map hasn't shown yet (everything loaded so far).")
    private val bloodRoute = BooleanSetting("Blood Route", false, "Line from you through the doors to the blood room.")
    private val mimicRoom = BooleanSetting("Mimic Room", true, "F6 / F7: the room with the mimic chest is tinted red on the map.")
    private val mimicEsp = BooleanSetting("Mimic ESP", false, "F6 / F7: box around the mimic chest through walls.")
    /** World positions of mimic chests: trapped chests above what their room always has. Cleared once the mimic dies. */
    @Volatile private var mimicChests: List<BlockPos> = emptyList()
    private var mimicDelay = 0
//#endif
    private val background = BooleanSetting("Background", true, "Dark card behind the map.")
    private val backgroundColor = ColorSetting("Background Color", Color(22, 22, 32))
    private val backgroundOpacity =NumberSetting("Background Opacity", 90.0, 0.0, 100.0, 5.0, "How see-through the card is (percent).")
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
    private const val NAME_SIZE = 5f
    /** Height of the two Run Info lines under the map. */
    private const val INFO_H = 20f
    private val CODES = Regex("§.")
    private val SECRETS = Regex("""(\d+)/(\d+) Secrets""")
    /** The world the blood door was opened in (Blood Route stops there). */
    private var bloodOpenLevel: ClientLevel? = null
    /** Room name -> secrets found, from the action bar, for [secretsLevel]. */
    private val secretsFound = HashMap<String, Int>()
    private var secretsLevel: ClientLevel? = null
    private var secretsVersion = 0

    private val mc get() = Minecraft.getInstance()
    private var scanDelay = 0

    init {
//#if CHEAT
        addPopupSettings(
            listOf("Rooms" to listOf(labelMode, labelSize, checkmarks, runInfo, clearSummary, showUndiscovered, mimicRoom, mimicEsp), "Players" to listOf(playerMarker, teammates, playerNames, markerStyle, bloodRoute)),
            listOf("Card" to listOf(background, backgroundColor, backgroundOpacity, border), "Colours" to listOf(normalColor, puzzleColor, trapColor, minibossColor, bloodColor, entranceColor, fairyColor, unopenedColor, witherDoorColor)),
        )
//#else
//$        addPopupSettings(
//$            listOf("Rooms" to listOf(labelMode, labelSize, checkmarks, runInfo, clearSummary), "Players" to listOf(playerMarker, teammates, playerNames, markerStyle)),
//$            listOf("Card" to listOf(background, backgroundColor, backgroundOpacity, border), "Colours" to listOf(normalColor, puzzleColor, trapColor, minibossColor, bloodColor, entranceColor, fairyColor, unopenedColor, witherDoorColor)),
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
            if (!isEnabled || !DungeonState.active || DungeonState.inBoss) return@register
            // The map (checkmarks, player icons) every tick; the world scan every 10.
            HotbarDungeonMap.update(level, player)
            if (clearSummary.value) ced.cedclient.state.DungeonClears.update(level, player)
            DungeonRunInfo.update()
            if (scanDelay-- > 0) return@register
            scanDelay = 10
            DungeonScanner.scan(level, player.x, player.z)
//#if CHEAT
            if (mimicDelay-- <= 0) { mimicDelay = 2; scanMimic(level) }
//#endif
        }
    }

    /** One "Map Options" button in the panel; each column's sections open in a popup from it. */
    private fun addPopupSettings(vararg columns: List<Pair<String, List<RenderableSetting<*>>>>) {
        addSettings(ActionSetting("Map Options", "Rooms, players, card style and colours.") {
            (mc.gui.screen() as? ClickGUI)?.openPopup(SettingsPopup("Dungeon Map", columns.map { col -> col.map { (title, list) -> SettingsPopup.Section(title, list) } }, height = 650f))
        })
        val all = columns.flatMap { col -> col.flatMap { it.second } }
        addSettings(*all.toTypedArray())
        all.forEach { it.inPopup = true }
    }

    /** "x/y Secrets" in the action bar: the count for the room you stand in. */
    private fun onSecrets(found: Int) {
        val player = mc.player ?: return
        if (secretsLevel !== mc.level) { secretsLevel = mc.level; secretsFound.clear(); secretsVersion++ }
        val i = Math.floorDiv(player.x.toInt() - START + 16, 32)
        val j = Math.floorDiv(player.z.toInt() - START + 16, 32)
        if (i !in 0..5 || j !in 0..5) return
        val name = DungeonScanner.room(i, j)?.name ?: return
        val best = maxOf(found, secretsFound[name] ?: 0)
        if (secretsFound.put(name, best) != best) secretsVersion++
    }

//#if CHEAT
    private val all get() = showUndiscovered.value
//#else
//$    private const val all = false
//#endif

    /** Room on tile ([i], [j]) if it may be drawn. */
    private fun room(i: Int, j: Int) =
        DungeonScanner.room(i, j)?.takeIf { all || (i to j) in HotbarDungeonMap.roomStates || preRunEntrance(it) }

    /** Before the run starts the hotbar map isn't lined up yet: the scanned entrance room stands in for it. */
    private fun preRunEntrance(room: RoomInfo) = !HotbarDungeonMap.calibrated && room.type == "ENTRANCE"

    /** The dungeon map shows tile ([i], [j]) as an opened room (not grey, not missing). */
    private fun explored(i: Int, j: Int) =
        HotbarDungeonMap.roomStates[i to j].let { it != null && it != RoomState.UNEXPLORED } ||
            DungeonScanner.room(i, j)?.let { preRunEntrance(it) } == true

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
        if (!isEnabled || mc.gui.hud.isHidden() || !DungeonState.active || DungeonState.inBoss) return
        renderInternal(g)
    }

    /** A player to draw: world position, yaw, name (for the skin), marker colour. */
    private class Mark(val x: Double, val z: Double, val yaw: Float, val name: String, val color: Int) {
        fun withColor(c: Int) = Mark(x, z, yaw, name, c)
    }

    /**
     * Everything that only depends on the scan, the vanilla map, found secrets and settings (rooms, doors, labels) is
     * built into draw ops once and replayed each frame; [layoutKey] says when something it read has changed.
     */
    private class Layout(val key: Long, val seen: List<Pair<Int, Int>>, val scannedW: Float, val scannedH: Float, val rooms: List<() -> Unit>, val labels: List<() -> Unit>)
    private var layout: Layout? = null
    private var sink = ArrayList<() -> Unit>()

    private fun layoutKey(): Long {
        var h = 17L
        for (v in intArrayOf(
            HotbarDungeonMap.version, DungeonScanner.version, secretsVersion,
            (if (showName) 1 else 0) or (if (checkmarks.value) 2 else 0) or (if (showSecrets) 4 else 0) or (if (all) 8 else 0),
            labelSize.value.toFloat().toRawBits(),
            normalColor.value.rgba, puzzleColor.value.rgba, trapColor.value.rgba, minibossColor.value.rgba, bloodColor.value.rgba,
            entranceColor.value.rgba, fairyColor.value.rgba, unopenedColor.value.rgba, witherDoorColor.value.rgba,
        )) h = h * 31 + v
        return h
    }

    private fun layout(): Layout {
        val key = layoutKey()
        layout?.takeIf { it.key == key }?.let { return it }
        val seen = (0..5).flatMap { i -> (0..5).map { j -> i to j } }.filter { (i, j) -> room(i, j) != null }
        val scanned = (0..5).flatMap { i -> (0..5).map { j -> i to j } }.filter { (i, j) -> DungeonScanner.room(i, j) != null }
        val w = ((scanned.maxOfOrNull { it.first } ?: 5) + 1) * PITCH - GAP
        val h = ((scanned.maxOfOrNull { it.second } ?: 5) + 1) * PITCH - GAP
        sink = ArrayList()
        if (seen.isNotEmpty()) drawRooms(seen)
        val rooms = sink
        sink = ArrayList()
        if (seen.isNotEmpty() && (showName || checkmarks.value || showSecrets)) drawLabels(groups(seen))
        return Layout(key, seen, w, h, rooms, sink).also { layout = it }
    }

    private fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) { sink += { NVGRenderer.rect(x, y, w, h, color) } }
    private fun text(text: String, x: Float, y: Float, size: Float, color: Int) { sink += { HudCard.text(text, x, y, size, color) } }

    override fun renderInternal(g: GuiGraphicsExtractor) {
        val lay = layout()
        val seen = lay.seen
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
            val w = lay.scannedW
            val h = lay.scannedH
            k = minOf(1f, (CARD - 8f) / maxOf(w, h))
            ox = (CARD - w * k) / 2
            oy = (CARD - h * k) / 2
        }
        val marks = if (seen.isEmpty()) emptyList() else marks()
        val heads = markerStyle.value == "Head"
        HudCard.draw(g, panelX, panelY, behindLeap = true) {
            NVGRenderer.scale(s, s)
            if (background.value) {
                val a = (backgroundOpacity.value / 100 * 255).toInt().coerceIn(0, 255)
                NVGRenderer.rect(0f, 0f, CARD, CARD + infoH, (a shl 24) or (backgroundColor.value.rgba and 0xFFFFFF), 8f)
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
            lay.rooms.forEach { it() }
//#if CHEAT
            if (mimicRoom.value) drawMimicRoom()
//#endif
            lay.labels.forEach { it() }
//#if CHEAT
            if (bloodRoute.value && bloodOpenLevel !== mc.level) drawBloodRoute()
//#endif
            if (!heads) for (m in marks) drawArrow(toMap(m.x), toMap(m.z), m.yaw, m.color)
            if (showNames()) for (m in marks) {
                val w = HudCard.width(m.name, NAME_SIZE)
                HudCard.text(m.name, toMap(m.x) - w / 2, toMap(m.z) + NAME_SIZE, NAME_SIZE, 0xFFFFFFFF.toInt())
            }
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
    private fun showNames() = when (playerNames.value) {
        "Always" -> true
        "Never" -> false
        else -> mc.player?.mainHandItem?.skyblockId in LeapMenu.LEAP_ITEMS
    }

    /** name -> eased x, z, yaw: teammates glide toward their latest position instead of stepping with the tick / map packets. */
    private val eased = HashMap<String, DoubleArray>()
    private var easedAt = 0L
    private var easedDt = 0.0

    private fun ease(name: String, x: Double, z: Double, yaw: Float): Mark {
        val e = eased.getOrPut(name.lowercase()) { doubleArrayOf(x, z, yaw.toDouble()) }
        val dx = x - e[0]
        val dz = z - e[1]
        if (dx * dx + dz * dz > 400.0) { e[0] = x; e[1] = z; e[2] = yaw.toDouble() } // a leap: jump, don't glide across the map
        else {
            val a = 1 - Math.exp(-easedDt * 20)
            e[0] += dx * a
            e[1] += dz * a
            e[2] += ((((yaw - e[2]) % 360) + 540) % 360 - 180) * a
        }
        return Mark(e[0], e[1], e[2].toFloat(), name, 0)
    }

    private fun marks(): List<Mark> {
        val now = System.nanoTime()
        easedDt = ((now - easedAt) / 1e9).coerceIn(0.0, 0.25)
        easedAt = now
        val self = mc.player ?: return emptyList()
        val out = ArrayList<Mark>()
        if (teammates.value) {
            val icons = HotbarDungeonMap.markers.filter { !it.self }
            DungeonTeam.members().filter { !it.dead }.forEachIndexed { i, m ->
                val color = DungeonTeam.classColor(m.cls)
                val entity = mc.level?.players()?.firstOrNull { it !== self && it.gameProfile.name.equals(m.name, ignoreCase = true) }
                if (entity != null) out += ease(m.name, entity.x, entity.z, entity.yRot).withColor(color)
                else icons.getOrNull(i)?.let { out += ease(m.name, it.x, it.z, it.yaw).withColor(color) }
            }
        }
        if (playerMarker.value) out += Mark(self.x, self.z, self.yRot, self.gameProfile.name, 0xFF55FF55.toInt())
        return out
    }

    private fun drawRooms(seen: List<Pair<Int, Int>>) {
        for ((i, j) in seen) rect(i * PITCH, j * PITCH, ROOM, ROOM, tileColor(i, j))
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
                rect(gx, gy, if (alongX) GAP else ROOM, if (alongX) ROOM else GAP, tileColor(ri, rj))
            } else {
                val off = (ROOM - DOOR) / 2
                val known = (START + 16 * si to START + 16 * sj) in HotbarDungeonMap.discoveredDoors
                val color = if (known || spot == Spot.ENTRANCE) doorColor(spot) else darken(doorColor(spot))
                rect(if (alongX) gx else gx + off, if (alongX) gy + off else gy, if (alongX) GAP else DOOR, if (alongX) DOOR else GAP, color)
            }
        }
        // Middle of a 2x2 room: the four gaps around it are all separators.
        for (ci in 0 until 5) for (cj in 0 until 5) {
            val i = ci * 2 + 1
            val j = cj * 2 + 1
            if (listOf(i - 1 to j, i + 1 to j, i to j - 1, i to j + 1).all { (a, b) -> spot(a, b) == Spot.SEPARATOR }) {
                if (room(ci, cj) == null) continue
                rect(ci * PITCH + ROOM, cj * PITCH + ROOM, GAP, GAP, tileColor(ci, cj))
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
            val scale = labelSize.value.toFloat()
            val secretSize = (3.5f * scale).coerceAtMost(spanH * 0.3f)
            val secretLine = if (showSecrets && shown && info.secrets > 0) "${(if (state == RoomState.GREEN) info.secrets else secretsFound[info.name])?.toString() ?: "?"}/${info.secrets}" else null
            val nameLines = if (showName && shown) info.name else null
            val secretH = if (secretLine != null) secretSize * 1.15f else 0f
            val (lines, size) = if (nameLines != null) fit(nameLines, spanW - 2f, spanH - 2f - secretH - if (check) CHECK_H else 0f) else emptyList<String>() to 0f
            val lineH = size * 1.1f
            val blockH = lines.size * lineH + secretH + if (check) CHECK_H else 0f
            var y = cy - blockH / 2
            // Text flips to dark on light room colors so custom colors stay readable.
            val bg = tileColor(si, sj)
            val light = (0.299f * (bg shr 16 and 0xFF) + 0.587f * (bg shr 8 and 0xFF) + 0.114f * (bg and 0xFF)) > 150f
            val color = when {
                light -> if (explored(si, sj)) 0xFF111111.toInt() else 0xFF444444.toInt()
                explored(si, sj) -> -1
                else -> 0xFF9A9A9A.toInt()
            }
            for (line in lines) {
                text(line, cx - HudCard.width(line, size) / 2, y, size, color)
                y += lineH
            }
            if (secretLine != null) {
                val done = (secretsFound[info.name] ?: 0) >= info.secrets || state == RoomState.GREEN
                text(secretLine, cx - HudCard.width(secretLine, secretSize) / 2, y, secretSize, if (done) (if (light) 0xFF007700.toInt() else 0xFF55FF55.toInt()) else (if (light) 0xFF222222.toInt() else 0xFFCCCCCC.toInt()))
                y += secretH
            }
            if (check) { val checkY = y + CHECK_H / 2; val checkState = state!!; sink += { drawCheck(cx, checkY, checkState) } }
        }
    }

    /** [name] word-wrapped into [maxW] x [maxH]: the biggest size from 4.5 down that fits, as lines + size. */
    private fun fit(name: String, maxW: Float, maxH: Float): Pair<List<String>, Float> {
        val words = name.split(' ')
        val start = 4.5f * labelSize.value.toFloat()
        var size = start
        while (true) {
            val lines = ArrayList<String>()
            for (word in words) {
                val joined = lines.lastOrNull()?.let { "$it $word" }
                if (joined != null && HudCard.width(joined, size) <= maxW) lines[lines.size - 1] = joined else lines += word
            }
            val fits = lines.all { HudCard.width(it, size) <= maxW } && lines.size * size * 1.1f <= maxH
            if (fits || size <= minOf(2.5f, start)) return lines to size
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

//#if CHEAT
    /**
     * Mimic: F6 / F7 only. Every trapped chest in the loaded grid is counted per room; a room with more than its
     * rooms.json trappedChests holds the mimic. The chest block goes when the mimic is found, so the box follows.
     */
    private fun scanMimic(level: ClientLevel) {
        val floor = DungeonState.floor?.drop(1)?.toIntOrNull()
        if ((!mimicRoom.value && !mimicEsp.value) || (floor != 6 && floor != 7) || DungeonRunInfo.mimic) {
            mimicChests = emptyList()
            return
        }
        val byRoom = HashMap<String, MutableList<BlockPos>>()
        val chunks = ((START - 16) shr 4)..((START + 5 * 32 + 15) shr 4)
        for (cx in chunks) for (cz in chunks) {
            val chunk = level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) ?: continue
            for ((pos, be) in chunk.blockEntities) {
                if (be.type != net.minecraft.world.level.block.entity.BlockEntityTypes.TRAPPED_CHEST) continue
                val i = Math.floorDiv(pos.x - START + 16, 32)
                val j = Math.floorDiv(pos.z - START + 16, 32)
                if (i !in 0..5 || j !in 0..5) continue
                val room = DungeonScanner.room(i, j) ?: continue
                byRoom.getOrPut(room.name) { ArrayList() } += pos
            }
        }
        mimicChests = byRoom.filter { (name, chests) ->
            val expected = (0..5).flatMap { i -> (0..5).map { j -> DungeonScanner.room(i, j) } }.firstOrNull { it?.name == name }?.trappedChests ?: 0
            chests.size > expected
        }.values.flatten()
    }

    /** Red tint over every tile (and the joins between them) of the room the mimic chest is in. */
    private fun drawMimicRoom() {
        val tiles = mimicChests.map { Math.floorDiv(it.x - START + 16, 32) to Math.floorDiv(it.z - START + 16, 32) }.toSet()
        val names = tiles.mapNotNull { (i, j) -> DungeonScanner.room(i, j)?.name }.toSet()
        if (names.isEmpty()) return
        val red = 0x88FF2020.toInt()
        for (i in 0..5) for (j in 0..5) {
            if (DungeonScanner.room(i, j)?.name !in names) continue
            NVGRenderer.rect(i * PITCH, j * PITCH, ROOM, ROOM, red)
            if (i < 5 && DungeonScanner.room(i + 1, j)?.name in names && spot(i * 2 + 1, j * 2) == Spot.SEPARATOR) NVGRenderer.rect(i * PITCH + ROOM, j * PITCH, GAP, ROOM, red)
            if (j < 5 && DungeonScanner.room(i, j + 1)?.name in names && spot(i * 2, j * 2 + 1) == Spot.SEPARATOR) NVGRenderer.rect(i * PITCH, j * PITCH + ROOM, ROOM, GAP, red)
        }
    }

    init {
        // 26.2: no BufferSource, boxes go through submitCustomGeometry in COLLECT_SUBMITS (see DoorESP).
        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            val chests = mimicChests
            if (!isEnabled || !mimicEsp.value || chests.isEmpty()) return@register
            val cam = context.gameRenderer().mainCamera().position()
            context.submitNodeCollector().submitCustomGeometry(context.poseStack(), CustomRenderType.LINES_ESP) { pose, buffer ->
                for (pos in chests) LineBox.draw(pose, buffer, AABB(pos).move(-cam.x, -cam.y, -cam.z), 1f, 0.15f, 0.15f, 1f)
            }
        }
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
        line(CARD + 1f, listOf("Score ~" to grey, "$score" to scoreColor, "   Secrets " to grey, "${r.secretsFound}" to secretsColor, "/${r.totalSecrets.takeIf { it > 0 } ?: "?"}" to grey))
        line(CARD + 10f, listOf("Crypts " to grey, "${r.crypts}" to if (r.crypts >= 5) green else -1, "  Deaths " to grey, "${r.deaths}" to if (r.deaths == 0) green else red,
            "  Bats " to grey, "${r.batCount}" to if (r.bat) green else red, "  Mimic" to if (r.mimic) green else red, "  Prince" to if (r.prince) green else red))
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
