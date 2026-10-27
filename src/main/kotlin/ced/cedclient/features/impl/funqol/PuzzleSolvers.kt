//#if CHEAT
package ced.cedclient.features.impl.funqol

import com.google.gson.Gson
import ced.cedclient.render.bufferSource
import ced.cedclient.render.draw
import ced.cedclient.render.polyline
import com.google.gson.reflect.TypeToken
import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.Box3D
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.HeaderSetting
import ced.cedclient.features.settings.Setting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.render.LineBox
import ced.cedclient.render.WorldText
import ced.cedclient.render.cosmetic.CosmeticRooms
import ced.cedclient.render.cosmetic.RoomRotation
import ced.cedclient.render.cosmetic.RoomTile
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.Color
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.BlockTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.monster.Silverfish
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.WallSignBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt

/*
 * The Catacombs puzzle solvers, ported from NoammAddons. Each puzzle is its own module (like Blaze Solver). The shared
 * part is [PuzzleRoom]: it finds the room you stand in (CosmeticRooms' scanner, so it needs no cosmetic setting) and
 * hands each enabled solver its room's name, centre and rotation in NoammAddons' convention, so the room-relative
 * coordinates of the puzzle data carry over unchanged.
 */

private fun BlockPos.rotated(degree: Int): BlockPos = when (((degree % 360) + 360) % 360) {
    90 -> BlockPos(z, y, -x)
    180 -> BlockPos(-x, y, -z)
    270 -> BlockPos(-z, y, x)
    else -> this
}

/** The room you stand in: [rotation] is 0/90/180/270 like NoammAddons ([center] = tile centre of its top-left tile). */
internal class PuzzleRoomInfo(val name: String, val center: BlockPos, val rotation: Int, val tiles: Set<RoomTile>) {
    /** Room-relative to world: rotate by 360 - rotation (+ [extra]), then move to the room centre. */
    fun real(x: Int, y: Int, z: Int, extra: Int = 0): BlockPos {
        val r = BlockPos(x, y, z).rotated(360 - rotation + extra)
        return BlockPos(r.x + center.x, r.y, r.z + center.z)
    }
}

internal object PuzzleRoom {
    val modules = ArrayList<PuzzleModule>()
    var current: PuzzleRoomInfo? = null
        private set

    private var lastTile: RoomTile? = null
    private var retry = 0
    private var lastLevel: ClientLevel? = null

    private fun degrees(r: RoomRotation) = when (r) { RoomRotation.WEST -> 90; RoomRotation.NORTH -> 180; RoomRotation.EAST -> 270; else -> 0 }

    init {
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            val level = mc.level
            val player = mc.player
            if (level !== lastLevel) { lastLevel = level; current = null; lastTile = null; modules.forEach { it.sync(null); it.reset() } }
            if (level == null || player == null || !DungeonState.inDungeon || DungeonState.inBoss || modules.none { it.isEnabled } || !CosmeticRooms.isReady()) {
                current = null
                modules.forEach { it.sync(null) }
                return@register
            }
            val tile = CosmeticRooms.tileOf(player.blockPosition().x, player.blockPosition().z)
            if (current?.tiles?.contains(tile) != true) {
                current = null
                if (tile != lastTile || retry-- <= 0) {
                    lastTile = tile; retry = 10
                    CosmeticRooms.scanRoom(tile, level)?.let { room ->
                        val main = room.components.minWith(compareBy({ it.x }, { it.z }))
                        current = PuzzleRoomInfo(room.data.name, BlockPos(main.x, 0, main.z), degrees(room.rotation), room.components)
                    }
                }
            }
            for (m in modules) { m.sync(current); m.room?.let { m.tick(level, it) } }
        }
        LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { context ->
            for (m in modules) { val r = m.room ?: continue; m.render(context, r) }
        })
        UseBlockCallback.EVENT.register(UseBlockCallback { _, level, hand, hit ->
            if (level.isClientSide && hand == InteractionHand.MAIN_HAND) for (m in modules) {
                val r = m.room ?: continue
                if (m.interact(level as ClientLevel, hit.blockPos, r)) return@UseBlockCallback InteractionResult.FAIL
            }
            InteractionResult.PASS
        })
    }
}

/**
 * The one "Puzzle Solvers" module: every puzzle below is a section of it (a header, an on / off toggle, then its own
 * settings), so the Dungeons panel gets one entry instead of nine.
 */
object PuzzleSolvers : Module("Puzzle Solvers", Category.Dungeons, "Solvers for the Catacombs puzzles: Boulder, Creeper Beams, Quiz, Teleport Maze, Three Weirdos, Tic Tac Toe, Water Board, Ice Fill, Ice Path.") {
    init {
        for (p in listOf(BoulderSolver, CreeperBeamSolver, QuizSolver, TeleportMazeSolver, ThreeWeirdosSolver, TicTacToeSolver, WaterBoardSolver, IceFillSolver, IcePathSolver)) {
            addSettings(*p.allSettings().toTypedArray())
        }
    }
}

/** A solver that is active while you are inside the room called [roomName] and its toggle in [PuzzleSolvers] is on. */
internal abstract class PuzzleModule(val name: String, description: String, private val roomName: String) {
    private val toggle = BooleanSetting(name, false, description)
    private val own = ArrayList<Setting<*>>()

    protected fun addSettings(vararg settings: Setting<*>) { own += settings }
    fun allSettings(): List<Setting<*>> = listOf(HeaderSetting(roomName), toggle) + own
    val isEnabled: Boolean get() = PuzzleSolvers.isEnabled && toggle.value

    /** The room while this solver is active in it, else null. */
    var room: PuzzleRoomInfo? = null
        private set

    open fun enter(level: ClientLevel, room: PuzzleRoomInfo) {}
    open fun leave() {}
    open fun tick(level: ClientLevel, room: PuzzleRoomInfo) {}
    open fun render(context: LevelRenderContext, room: PuzzleRoomInfo) {}
    /** A right click on [pos]; true cancels it. */
    open fun interact(level: ClientLevel, pos: BlockPos, room: PuzzleRoomInfo): Boolean = false
    /** The world changed: forget everything. */
    open fun reset() {}

    init { PuzzleRoom.modules += this }

    internal fun sync(current: PuzzleRoomInfo?) {
        val want = if (isEnabled && current?.name == roomName) current else null
        if (want === room) return
        if (room != null) leave()
        room = want
        val level = mc.level
        if (want != null && level != null) enter(level, want)
    }
}

internal object PuzzleDraw {
    fun block(pos: BlockPos, color: Color): Pair<AABB, Color> = AABB(pos) to color

    fun line(context: LevelRenderContext, points: List<Vec3>, color: Color, phase: Boolean = true) {
        if (points.size < 2) return
        val cam = context.gameRenderer().mainCamera().position()
        val buffer = context.bufferSource().getBuffer(if (phase) CustomRenderType.LINES_ESP else CustomRenderType.LINES_DEPTH)
        LineBox.polyline(context.poseStack(), buffer, points.map { it.subtract(cam) }, color.redFloat, color.greenFloat, color.blueFloat, color.alphaFloat)
        context.bufferSource().endBatch()
    }

    /** A line from your crosshair to [to]. */
    fun tracer(context: LevelRenderContext, to: Vec3, color: Color) {
        val player = mc.player ?: return
        val cam = context.gameRenderer().mainCamera().position()
        line(context, listOf(cam.add(player.getViewVector(1f).scale(0.5)), to), color)
    }

    fun text(context: LevelRenderContext, at: Vec3, text: String) = WorldText.draw(context, at, Component.literal(text))
}

private val GSON = Gson()

private fun <T> puzzleData(file: String, type: TypeToken<T>): T =
    PuzzleRoom::class.java.getResourceAsStream("/assets/cedclient/puzzles/$file")!!.bufferedReader().use { GSON.fromJson(it, type.type) }

// ------------------------------------------------------------------------------------------------ Boulder

/** Boulder: the 6x7 grid of boulders is read as a bit pattern and looked up; boxes the boulder to push and the block to click. */
internal object BoulderSolver : PuzzleModule("Boulder Solver", "Boulder puzzle: boxes the boulder to push and the block to click, move by move.", "Boulder") {
    private val showAll = BooleanSetting("Show All Moves", false, "Show every move, not only the next one.")
    private val boxColor = ColorSetting("Boulder Box Color", Color(0, 0, 255), "The boulder to push.")
    private val clickColor = ColorSetting("Boulder Click Color", Color(255, 0, 0), "The button / lever to click.")

    private class Move(val box: BlockPos, val click: BlockPos)

    private val solutions by lazy { puzzleData("boulderSolutions.json", object : TypeToken<Map<String, List<List<Double>>>>() {}) }
    private val moves = ArrayList<Move>()
    private var done = false
    private var wait = 0

    init { addSettings(showAll, boxColor, clickColor) }

    private fun solve(level: ClientLevel, room: PuzzleRoomInfo) {
        val pattern = StringBuilder()
        for (z in 0..5) for (x in 0..6) pattern.append(if (level.getBlockState(room.real(-9 + x * 3, 65, -9 + z * 3, 180)).isAir) '0' else '1')
        val found = solutions[pattern.toString()] ?: return
        moves.clear()
        for (s in found) moves += Move(room.real(s[0].toInt(), 65, s[1].toInt(), 180), room.real(s[2].toInt(), 65, s[3].toInt(), 180))
        ced.cedclient.utils.Debug.line("Boulder Solver", "pattern $pattern: ${moves.size} moves")
    }

    override fun enter(level: ClientLevel, room: PuzzleRoomInfo) { moves.clear(); done = false; wait = 0; solve(level, room) }
    override fun leave() { moves.clear() }

    // The boulders may not all be loaded on entering, so look again until a pattern matches.
    override fun tick(level: ClientLevel, room: PuzzleRoomInfo) {
        if (done || moves.isNotEmpty() || ++wait % 20 != 0) return
        solve(level, room)
    }

    override fun interact(level: ClientLevel, pos: BlockPos, room: PuzzleRoomInfo): Boolean {
        when (level.getBlockState(pos).block) {
            is ButtonBlock, is LeverBlock, is WallSignBlock -> moves.removeIf { it.click == pos }
            is ChestBlock -> { moves.clear(); done = true }
            else -> {}
        }
        return false
    }

    override fun render(context: LevelRenderContext, room: PuzzleRoomInfo) {
        if (moves.isEmpty()) return
        val shown = if (showAll.value) moves.toList() else listOf(moves.first())
        val boxes = ArrayList<Pair<AABB, Color>>()
        for (m in shown) {
            boxes += AABB(m.box.x - 1.0, m.box.y - 1.0, m.box.z - 1.0, m.box.x + 2.0, m.box.y + 2.0, m.box.z + 2.0) to boxColor.value
            boxes += PuzzleDraw.block(m.click, clickColor.value)
        }
        Box3D.draw(context, boxes)
    }

    override fun reset() { moves.clear(); done = false }
}

// ------------------------------------------------------------------------------------------------ Creeper Beams

/** Creeper Beams: pairs of prismarine / sea lanterns to hit together, each pair in its own colour. */
internal object CreeperBeamSolver : PuzzleModule("Creeper Beam Solver", "Creeper Beams puzzle: marks the pairs of blocks to hit and links them.", "Creeper Beams") {
    private val lines = BooleanSetting("Render Lines", true, "A line between the two blocks of a pair.")
    private val phase = BooleanSetting("Phase Through Walls", true, "Draw the lines through walls.")

    private class Beam(val start: BlockPos, val end: BlockPos, val color: Color)

    private val data by lazy { puzzleData("creeperBeamSolutions.json", object : TypeToken<List<List<List<Int>>>>() {}) }
    private val beams = ArrayList<Beam>()
    private val palette = listOf(Color(255, 0, 0), Color(0, 255, 0), Color(0, 0, 255), Color(255, 255, 0), Color(0, 255, 255), Color(255, 200, 0), Color(255, 255, 255), Color(255, 0, 255))

    init { addSettings(lines, phase) }

    private fun isBeamBlock(b: Block) = b == Blocks.PRISMARINE || b == Blocks.SEA_LANTERN

    override fun enter(level: ClientLevel, room: PuzzleRoomInfo) {
        beams.clear()
        var i = 0
        for (pair in data) {
            val start = room.real(pair[0][0], pair[0][1], pair[0][2], 180)
            val end = room.real(pair[1][0], pair[1][1], pair[1][2], 180)
            if (!isBeamBlock(level.getBlockState(start).block) || !isBeamBlock(level.getBlockState(end).block)) continue
            beams += Beam(start, end, palette[i++ % palette.size])
        }
        ced.cedclient.utils.Debug.line("Creeper Beam Solver", "${beams.size} beam pairs")
    }

    override fun leave() { beams.clear() }

    override fun render(context: LevelRenderContext, room: PuzzleRoomInfo) {
        val level = mc.level ?: return
        val boxes = ArrayList<Pair<AABB, Color>>()
        for (b in beams) {
            // A pair stops showing once its lanterns have been hit (no sea lantern left at either end).
            if (level.getBlockState(b.start).block != Blocks.SEA_LANTERN && level.getBlockState(b.end).block != Blocks.SEA_LANTERN) continue
            boxes += PuzzleDraw.block(b.start, b.color)
            boxes += PuzzleDraw.block(b.end, b.color)
            if (lines.value) PuzzleDraw.line(context, listOf(Vec3.atCenterOf(b.start), Vec3.atCenterOf(b.end)), b.color, phase.value)
        }
        Box3D.draw(context, boxes)
    }

    override fun reset() { beams.clear() }
}

// ------------------------------------------------------------------------------------------------ Quiz

/** Quiz: reads the question from chat, looks up the answer and boxes the right a / b / c block. Can block clicks on wrong ones. */
internal object QuizSolver : PuzzleModule("Quiz Solver", "Quiz puzzle: boxes the correct answer and can block wrong clicks.", "Quiz") {
    private val answerColor = ColorSetting("Answer Color", Color(0, 255, 255), "The correct answer.")
    private val blockWrong = BooleanSetting("Block Wrong Clicks", true, "Cancels a click on a wrong answer. Sneak to click anyway.")

    private class Answer(var pos: BlockPos, var correct: Boolean)

    private val solutions by lazy { puzzleData("quizSolutions.json", object : TypeToken<Map<String, List<String>>>() {}) }
    private val options = List(3) { Answer(BlockPos.ZERO, false) }
    private var answers: List<String>? = null

    init {
        addSettings(answerColor, blockWrong)
        on<ChatMessageEvent> { onChat(it.unformattedText) }
    }

    private fun clear() { options.forEach { it.correct = false }; answers = null }

    private fun onChat(message: String) {
        if (!isEnabled || !DungeonState.inDungeon) return
        val trimmed = message.trim()
        when {
            message.contains("I am Oruo the Omniscient. I have lived many lives.") -> { clear(); return }
            message.contains("2 questions left... Then you will have proven your worth to me!") || message.contains("One more question!") -> {
                options.forEach { it.correct = false }
                return
            }
            message.startsWith("[STATUE] Oruo the Omniscient: ") && message.contains("answered the final question") && message.endsWith("correctly!") -> { clear(); return }
        }
        if (trimmed.isNotEmpty() && trimmed[0] in "ⓐⓑⓒ") {
            val matched = answers?.firstOrNull { message.endsWith(it) } ?: return
            options["ⓐⓑⓒ".indexOf(trimmed[0])].correct = true
            say("§dQuiz §7> §aCorrect answer is §b${trimmed[0]} $matched")
            return
        }
        answers = if (trimmed == "What SkyBlock year is it?") listOf("Year ${(((System.currentTimeMillis() / 1000) - 1560276000) / 446400).toInt() + 1}")
        else solutions.entries.find { message.contains(it.key) }?.value ?: answers
    }

    override fun enter(level: ClientLevel, room: PuzzleRoomInfo) {
        options[0].pos = room.real(5, 70, -9)
        options[1].pos = room.real(0, 70, -6)
        options[2].pos = room.real(-5, 70, -9)
    }

    override fun interact(level: ClientLevel, pos: BlockPos, room: PuzzleRoomInfo): Boolean {
        if (!blockWrong.value || mc.player?.isCrouching == true) return false
        val clicked = options.firstOrNull { it.pos.distManhattan(pos) <= 1 } ?: return false
        return options.any { it.correct } && !clicked.correct
    }

    override fun render(context: LevelRenderContext, room: PuzzleRoomInfo) {
        if (answers == null) return
        Box3D.draw(context, options.filter { it.correct }.map { PuzzleDraw.block(it.pos, answerColor.value) })
    }

    override fun reset() = clear()
}

// ------------------------------------------------------------------------------------------------ Teleport Maze

/**
 * Teleport Maze: every pad you step on and the pad it sends you to are wrong. After each teleport the pads are ranked by
 * how far off your new look direction they are, summed over all teleports, so the best pad is boxed green.
 * shortcut: a teleport is spotted as a jump of the player position on a pad centre (NoammAddons reads the position packet).
 */
internal object TeleportMazeSolver : PuzzleModule("Teleport Maze Solver", "Teleport Maze puzzle: boxes the correct pad and the wrong ones.", "Teleport Maze") {
    private val correctColor = ColorSetting("Correct Pad Color", Color(0, 255, 0), "The pad to use.")
    private val wrongColor = ColorSetting("Wrong Pad Color", Color(255, 0, 0), "Pads known to be wrong.")

    private class Pad(val pos: BlockPos, var totalAngle: Double = 0.0, var blacklisted: Boolean = false)
    private class Cell { val pads = ArrayList<Pad>() }

    private var minX = 0
    private var minZ = 0
    private var cells: List<Cell>? = null
    private var ordered: List<Pad>? = null
    private var last: Vec3? = null

    init { addSettings(correctColor, wrongColor) }

    override fun enter(level: ClientLevel, room: PuzzleRoomInfo) {
        if (level.getBlockState(room.real(0, 69, -3)).block != Blocks.END_PORTAL_FRAME) return
        val pads = ArrayList<Pad>()
        for (dx in 0..31) for (dz in 0..31) {
            val pos = BlockPos(room.center.x + dx - 16, 69, room.center.z + dz - 16)
            if (level.getBlockState(pos).block == Blocks.END_PORTAL_FRAME) pads += Pad(pos)
        }
        if (pads.isEmpty()) return
        minX = pads.minOf { it.pos.x }
        minZ = pads.minOf { it.pos.z }
        val grid = List(9) { Cell() }
        for (p in pads) grid[(((p.pos.x - minX) / 8) * 3 + (p.pos.z - minZ) / 8).coerceIn(0, 8)].pads += p
        cells = grid
        ordered = null
    }

    override fun leave() { cells = null; ordered = null; last = null }

    override fun tick(level: ClientLevel, room: PuzzleRoomInfo) {
        val player = mc.player ?: return
        val now = player.position()
        val before = last
        last = now
        if (cells == null || before == null) return
        if (now.x % 0.5 != 0.0 || now.y != 69.5 || now.z % 0.5 != 0.0) return
        if (abs(now.x - before.x) + abs(now.z - before.z) < 2.0) return
        val oldPad = padNear(before.x, before.z) ?: return
        val newPad = padNear(now.x, now.z) ?: return
        if (inStartOrEnd(newPad)) return
        newPad.blacklisted = true
        oldPad.blacklisted = true
        rank(now.x, now.z, player.yRot)
    }

    private fun rank(px: Double, pz: Double, yaw: Float) {
        val grid = cells ?: return
        val current = ArrayList<Pad>()
        for (cell in grid) for (pad in cell.pads) {
            if (inStartOrEnd(pad) || pad.blacklisted) continue
            pad.totalAngle += angle(pad.pos.x + 0.5 - px, pad.pos.z + 0.5 - pz, yaw)
            current += pad
        }
        current.sortBy { it.totalAngle }
        ordered = current
    }

    private fun angle(dx: Double, dz: Double, yaw: Float): Double {
        val rad = Math.toRadians(yaw.toDouble())
        val dist = sqrt(dx * dx + dz * dz)
        val dot = (-sin(rad) * dx / dist + cos(rad) * dz / dist).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dot))
    }

    private fun padNear(x: Double, z: Double): Pad? {
        val xi = x.toInt()
        val zi = z.toInt()
        if (xi < minX || xi > minX + 23 || zi < minZ || zi > minZ + 23) return null
        val cell = cells?.getOrNull(((xi - minX) / 8) * 3 + (zi - minZ) / 8) ?: return null
        return cell.pads.find { abs(x - (it.pos.x + 0.5)) + abs(z - (it.pos.z + 0.5)) <= 3.0 }
    }

    private fun inStartOrEnd(pad: Pad): Boolean {
        val grid = cells ?: return false
        if (grid.getOrNull(4)?.pads?.contains(pad) == true) return true
        return grid.any { it !== grid[4] && it.pads.size == 1 && pad in it.pads }
    }

    override fun render(context: LevelRenderContext, room: PuzzleRoomInfo) {
        val grid = cells ?: return
        val boxes = ArrayList<Pair<AABB, Color>>()
        val top = ordered?.takeIf { it.size >= 2 }?.take(2)
        if (top != null && top[0].totalAngle != top[1].totalAngle) boxes += PuzzleDraw.block(top[0].pos, correctColor.value)
        for (cell in grid) for (pad in cell.pads) if (pad.blacklisted) boxes += PuzzleDraw.block(pad.pos, wrongColor.value)
        Box3D.draw(context, boxes)
    }

    override fun reset() = leave()
}

// ------------------------------------------------------------------------------------------------ Three Weirdos

/** Three Weirdos: the NPC lines are checked against the known true / false statements; the right chest is boxed, wrong ones hidden. */
internal object ThreeWeirdosSolver : PuzzleModule("Three Weirdos Solver", "Three Weirdos puzzle: marks the right chest and hides the wrong ones.", "Three Weirdos") {
    private val hideWrong = BooleanSetting("Hide Wrong Chests", true, "Removes the wrong chests (only on your screen) instead of boxing them.")
    private val correctColor = ColorSetting("Correct Chest Color", Color(0, 255, 0), "The chest with the reward.")
    private val wrongColor = ColorSetting("Wrong Chest Color", Color(255, 0, 0), "Wrong chests, when not hidden.")

    private val npc = Regex("\\[NPC] (\\w+): (.+)")
    private var correct: BlockPos? = null
    private val wrong = HashMap<BlockPos, BlockState>()

    private val solutions = listOf(
        Regex("The reward is not in my chest!"),
        Regex("At least one of them is lying, and the reward is not in .+'s chest.?"),
        Regex("My chest doesn't have the reward. We are all telling the truth.?"),
        Regex("My chest has the reward and I'm telling the truth!"),
        Regex("The reward isn't in any of our chests.?"),
        Regex("Both of them are telling the truth. Also, .+ has the reward in their chest.?"),
    )
    private val lies = listOf(
        Regex("One of us is telling the truth!"),
        Regex("They are both telling the truth. The reward isn't in .+'s chest."),
        Regex("We are all telling the truth!"),
        Regex(".+ is telling the truth and the reward is in his chest."),
        Regex("My chest doesn't have the reward. At least one of the others is telling the truth!"),
        Regex("One of the others is lying."),
        Regex("They are both telling the truth, the reward is in .+'s chest."),
        Regex("They are both lying, the reward is in my chest!"),
        Regex("The reward is in my chest."),
        Regex("The reward is not in my chest. They are both lying."),
        Regex(".+ is telling the truth."),
        Regex("My chest has the reward.")
    )

    init {
        addSettings(hideWrong, correctColor, wrongColor)
        on<ChatMessageEvent> { onChat(it.unformattedText) }
    }

    private fun onChat(message: String) {
        val room = room ?: return
        val level = mc.level ?: return
        val (name, text) = npc.find(message)?.destructured ?: return
        val isSolution = solutions.any { it.matches(text) }
        if (!isSolution && lies.none { it.matches(text) }) return
        val stand = level.entitiesForRendering().filterIsInstance<ArmorStand>().find { it.name.string.contains(name) } ?: return
        val chest = BlockPos(floor(stand.x).toInt(), 69, floor(stand.z).toInt()).offset(BlockPos(1, 0, 0).rotated(360 - room.rotation))
        if (isSolution) {
            mc.player?.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 2f, 1f)
            correct = chest
        } else if (chest !in wrong) {
            wrong[chest] = level.getBlockState(chest)
            if (hideWrong.value) level.setBlock(chest, Blocks.AIR.defaultBlockState(), 3)
        }
        ced.cedclient.utils.Debug.line("Three Weirdos Solver", "$name: ${if (isSolution) "solution" else "wrong"} at $chest")
    }

    private fun restore() {
        val level = mc.level
        if (level != null && hideWrong.value) for ((pos, state) in wrong) if (level.getBlockState(pos).isAir) level.setBlock(pos, state, 3)
        correct = null
        wrong.clear()
    }

    override fun leave() = restore()

    override fun interact(level: ClientLevel, pos: BlockPos, room: PuzzleRoomInfo): Boolean {
        if (pos == correct) restore()
        return false
    }

    override fun render(context: LevelRenderContext, room: PuzzleRoomInfo) {
        val boxes = ArrayList<Pair<AABB, Color>>()
        correct?.let { boxes += PuzzleDraw.block(it, correctColor.value) }
        if (!hideWrong.value) for (pos in wrong.keys) boxes += PuzzleDraw.block(pos, wrongColor.value)
        Box3D.draw(context, boxes)
    }

    override fun reset() { correct = null; wrong.clear() }
}

// ------------------------------------------------------------------------------------------------ Tic Tac Toe

/** Tic Tac Toe: reads the board from the item frames' maps and boxes the best button(s) with minimax. */
internal object TicTacToeSolver : PuzzleModule("Tic Tac Toe Solver", "Tic Tac Toe puzzle: boxes the best button to press.", "Tic Tac Toe") {
    private val preventMiss = BooleanSetting("Prevent Miss Click", true, "Cancels a click on a button that is not a best move.")
    private val color = ColorSetting("Highlight Color", Color(0, 255, 0), "The best move.")
    private val prediction = BooleanSetting("Prediction", false, "Also shows the AI's reply and your move after it.")
    private val predictionColor = ColorSetting("Prediction Color", Color(255, 165, 0), "Your move after the AI's reply.").withDependency { prediction.value }

    private val best = ArrayList<BlockPos>()
    private val aiMoves = ArrayList<BlockPos>()
    private val prefire = ArrayList<BlockPos>()
    private var wait = 0

    init { addSettings(preventMiss, color, prediction, predictionColor) }

    override fun enter(level: ClientLevel, room: PuzzleRoomInfo) { wait = 0; solve(level, room) }
    override fun leave() { best.clear(); aiMoves.clear(); prefire.clear() }
    override fun reset() = leave()

    // NoammAddons re-solves when an item frame spawns; checking a few times a second is enough here.
    override fun tick(level: ClientLevel, room: PuzzleRoomInfo) { if (++wait % 4 == 0) solve(level, room) }

    override fun interact(level: ClientLevel, pos: BlockPos, room: PuzzleRoomInfo): Boolean =
        preventMiss.value && level.getBlockState(pos).block == Blocks.STONE_BUTTON && best.isNotEmpty() && pos !in best

    private fun solve(level: ClientLevel, room: PuzzleRoomInfo) {
        val c = room.center
        val frames = level.getEntitiesOfClass(ItemFrame::class.java, AABB(c.x - 9.0, 65.0, c.z - 9.0, c.x + 9.0, 73.0, c.z + 9.0))
            .filter { it.item.item is MapItem && it.item.has(DataComponents.MAP_ID) }
        if (frames.size == 8) return leave()
        if (frames.size % 2 == 0) return

        val board = CharArray(9) { UNPLAYED }
        var leftmost: BlockPos? = null
        var facingX = true
        var sign = 1
        for (frame in frames) {
            val data = level.getMapData(frame.item.get(DataComponents.MAP_ID) ?: continue) ?: continue
            sign = if (frame.direction == Direction.SOUTH || frame.direction == Direction.WEST) -1 else 1
            val framePos = frame.blockPosition()
            var row = 0
            for (i in 2 downTo 0) {
                val step = i * sign
                val pos: BlockPos
                if (frame.x % 0.5 == 0.0) { pos = framePos.offset(step, 0, 0); facingX = true }
                else { pos = framePos.offset(0, 0, step); facingX = false }
                val b = level.getBlockState(pos).block
                if (b == Blocks.STONE_BUTTON || b == Blocks.AIR) { leftmost = pos; row = i; break }
            }
            val column = (72 - frame.y.toInt()).takeIf { it in 0..2 } ?: continue
            when (data.colors[8256].toInt() and 0xFF) {
                114 -> board[column * 3 + row] = 'X' // the AI
                33 -> board[column * 3 + row] = 'O' // you
            }
        }
        val origin = leftmost ?: return

        fun pos(index: Int): BlockPos {
            val row = index % 3
            val col = index / 3
            return BlockPos(if (facingX) origin.x - sign * row else origin.x, 72 - col, if (!facingX) origin.z - sign * row else origin.z)
        }

        best.clear(); aiMoves.clear(); prefire.clear()
        val mine = findBest(board, 'O', 'X')
        mine.forEach { best += pos(it) }
        if (prediction.value && mine.isNotEmpty()) {
            val after = board.copyOf().also { it[mine.first()] = 'O' }
            for (ai in findBest(after, 'X', 'O')) {
                aiMoves += pos(ai)
                val next = after.copyOf().also { it[ai] = 'X' }
                if (!isWon(next)) findBest(next, 'O', 'X').map(::pos).filterTo(prefire) { it !in prefire }
            }
        }
        if (prefire.size == 7) prefire.clear()
    }

    override fun render(context: LevelRenderContext, room: PuzzleRoomInfo) {
        val level = mc.level ?: return
        val boxes = ArrayList<Pair<AABB, Color>>()
        fun add(pos: BlockPos, c: Color) {
            if (level.getBlockState(pos).block != Blocks.STONE_BUTTON) return
            val hw = 0.2
            val hh = 0.13
            val t = 0.2
            val x = pos.x.toDouble(); val y = pos.y + 0.5; val z = pos.z.toDouble()
            boxes += when (room.rotation) {
                0 -> AABB(x, y - hh, z + 0.5 - hw, x + t, y + hh, z + 0.5 + hw)
                90 -> AABB(x + 0.5 - hw, y - hh, z, x + 0.5 + hw, y + hh, z + t)
                180 -> AABB(x + 1.0 - t, y - hh, z + 0.5 - hw, x + 1.0, y + hh, z + 0.5 + hw)
                else -> AABB(x + 0.5 - hw, y - hh, z + 1.0 - t, x + 0.5 + hw, y + hh, z + 1.0)
            } to c
        }
        best.forEach { add(it, color.value) }
        if (prediction.value) {
            aiMoves.forEach { add(it, Color(255, 0, 0)) }
            prefire.forEach { add(it, predictionColor.value) }
        }
        Box3D.draw(context, boxes)
    }

    // Minimax, after https://gnoht.com/til/ttt-with-minimax/ : you are 'O' (maximizer), the AI 'X'.
    private const val UNPLAYED = '\u0000'
    private val WIN_SETS = arrayOf(
        intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8), intArrayOf(0, 3, 6),
        intArrayOf(1, 4, 7), intArrayOf(2, 5, 8), intArrayOf(0, 4, 8), intArrayOf(6, 4, 2)
    )

    private fun isWon(p: CharArray) = WIN_SETS.any { p[it[0]] != UNPLAYED && p[it[0]] == p[it[1]] && p[it[0]] == p[it[2]] }

    private fun findBest(board: CharArray, player: Char, opponent: Char): List<Int> {
        val moves = board.indices.filter { board[it] == UNPLAYED }
        if (moves.isEmpty()) return emptyList()
        val scores = moves.associateWith { m -> minimax(board.copyOf().also { it[m] = player }, player, opponent, false, 1) }
        val top = scores.values.max()
        return scores.filter { it.value >= top - 0.0001 }.keys.toList()
    }

    private fun minimax(p: CharArray, ai: Char, opp: Char, maximizing: Boolean, depth: Int): Double {
        val won = isWon(p)
        val moves = p.indices.filter { p[it] == UNPLAYED }
        if (won) return if (!maximizing) 1.0 / depth else -1.0 / depth
        if (moves.isEmpty()) return 0.0
        return if (maximizing) moves.maxOf { minimax(p.copyOf().also { c -> c[it] = ai }, ai, opp, false, depth + 1) }
        else moves.minOf { minimax(p.copyOf().also { c -> c[it] = opp }, ai, opp, true, depth + 1) }
    }
}

// ------------------------------------------------------------------------------------------------ Water Board

/** Water Board: the gates and the wool pattern pick a stored solution; shows which lever to pull next and when. */
internal object WaterBoardSolver : PuzzleModule("Water Board Solver", "Water Board puzzle: the next lever to pull, with timers.", "Water Board") {
    private val clickColor = ColorSetting("Water Click Color", Color(0, 255, 0), "Line to the next lever.")
    private val nextColor = ColorSetting("Next Click Color", Color(255, 255, 0), "Line from the next lever to the one after.")

    private val solutions by lazy { puzzleData("waterSolutions.json", object : TypeToken<Map<String, Map<String, Map<String, List<Double>>>>>() {}) }

    private enum class Gate(val y: Int, val z: Int) { PURPLE(56, 4), ORANGE(56, 3), BLUE(56, 2), GREEN(56, 1), RED(56, 0) }

    private enum class Lever(val x: Int, val y: Int, val z: Int, val key: String, var clicks: Int = 0) {
        QUARTZ(5, 61, 5, "quartz_block"), GOLD(5, 61, 0, "gold_block"), COAL(5, 61, -5, "coal_block"),
        DIAMOND(-5, 61, 5, "diamond_block"), EMERALD(-5, 61, 0, "emerald_block"), CLAY(-5, 61, -5, "hardened_clay"),
        WATER(0, 60, -10, "water")
    }

    private val plan = HashMap<Lever, List<Double>>()
    private var pattern = -1
    private var waterTick = -1L
    private var wait = 0

    init { addSettings(clickColor, nextColor) }

    private fun leverPos(room: PuzzleRoomInfo, l: Lever) = room.real(l.x, l.y, l.z)
    private fun closed(level: ClientLevel, room: PuzzleRoomInfo, g: Gate) = level.getBlockState(room.real(0, g.y, g.z)).`is`(BlockTags.WOOL)

    override fun enter(level: ClientLevel, room: PuzzleRoomInfo) { leave(); wait = 0 }
    override fun leave() { Lever.entries.forEach { it.clicks = 0 }; plan.clear(); pattern = -1; waterTick = -1 }
    override fun reset() = leave()

    // The room fills in as chunks load, so look for the pattern twice a second until it is found.
    override fun tick(level: ClientLevel, room: PuzzleRoomInfo) {
        if (pattern != -1 || ++wait % 10 != 0) return
        val gates = Gate.entries.filter { closed(level, room, it) }.joinToString("") { it.ordinal.toString() }
        if (gates.length != 3) return
        fun at(x: Int, y: Int, z: Int, b: Block) = level.getBlockState(room.real(x, y, z)).block == b
        pattern = when {
            at(-1, 77, 12, Blocks.TERRACOTTA) -> 0
            at(1, 78, 12, Blocks.EMERALD_BLOCK) -> 1
            at(-1, 78, 12, Blocks.DIAMOND_BLOCK) -> 2
            at(-1, 78, 12, Blocks.QUARTZ_BLOCK) -> 3
            else -> return
        }
        plan.clear()
        solutions["$pattern"]?.get(gates)?.forEach { (key, times) -> Lever.entries.find { it.key == key }?.let { plan[it] = times } }
        ced.cedclient.utils.Debug.line("Water Board Solver", "pattern $pattern gates $gates: ${plan.size} levers")
    }

    override fun interact(level: ClientLevel, pos: BlockPos, room: PuzzleRoomInfo): Boolean {
        if (pattern == -1 || plan.isEmpty()) return false
        Lever.entries.find { leverPos(room, it) == pos }?.let {
            if (it == Lever.WATER && waterTick == -1L) waterTick = level.gameTime
            it.clicks++
        }
        if (level.getBlockState(pos).block is ChestBlock && Gate.entries.none { closed(level, room, it) }) leave()
        return false
    }

    private fun shade(s: Double) = if (s < 2) "§c" else if (s < 6) "§e" else "§a"

    override fun render(context: LevelRenderContext, room: PuzzleRoomInfo) {
        val level = mc.level ?: return
        if (pattern == -1 || plan.isEmpty()) return
        val clicks = plan.flatMap { (lever, times) -> times.drop(lever.clicks).map { lever to it } }
            .sortedBy { (lever, time) -> time + if (lever == Lever.WATER) 0.01 else 0.0 }
        val next = clicks.firstOrNull()?.first ?: return
        PuzzleDraw.tracer(context, Vec3.atCenterOf(leverPos(room, next)), clickColor.value)
        val second = clicks.getOrNull(1)?.first
        if (second != null && second != next) PuzzleDraw.line(context, listOf(Vec3.atCenterOf(leverPos(room, next)), Vec3.atCenterOf(leverPos(room, second))), nextColor.value)

        for ((lever, times) in plan) times.drop(lever.clicks).forEachIndexed { i, seconds ->
            val ticks = (seconds * 20).toInt()
            val label = when {
                waterTick == -1L && ticks == 0 -> "§a§lCLICK"
                waterTick == -1L -> "${shade(seconds)}${seconds}s"
                else -> {
                    val left = waterTick + ticks - level.gameTime
                    if (left > 0) "${shade(left / 20.0)}${"%.1f".format(left / 20.0)}s" else "§a§lCLICK"
                }
            }
            PuzzleDraw.text(context, Vec3.atCenterOf(leverPos(room, lever)).add(0.0, (i + lever.clicks) * 0.5 + 1.0, 0.0), label)
        }
    }
}

// ------------------------------------------------------------------------------------------------ Ice Fill

/** Ice Fill: each of the three ice fields is a Hamiltonian path problem (visit every ice block once); solved by DFS off-thread. */
internal object IceFillSolver : PuzzleModule("Ice Fill Solver", "Ice Fill puzzle: draws the path that crosses every ice block once.", "Ice Fill") {
    private val color = ColorSetting("Ice Fill Path Color", Color(0, 255, 0), "The path.")

    @Volatile private var paths: List<List<BlockPos>> = emptyList()
    private var attempts = 0
    private var wait = 0
    @Volatile private var solving = false

    init { addSettings(color) }

    override fun enter(level: ClientLevel, room: PuzzleRoomInfo) { paths = emptyList(); attempts = 0; wait = 0 }
    override fun leave() { paths = emptyList(); attempts = 99 }
    override fun reset() = leave()

    // The ice may not be loaded yet on entering: try again a few times.
    override fun tick(level: ClientLevel, room: PuzzleRoomInfo) {
        if (paths.isNotEmpty() || solving || attempts >= 10 || ++wait % 20 != 0) return
        attempts++
        val ice = HashSet<BlockPos>()
        for (dx in -22..22) for (dz in -22..22) for (y in 68..73) {
            val pos = BlockPos(room.center.x + dx, y, room.center.z + dz)
            val state = level.getBlockState(pos)
            if (!state.`is`(Blocks.ICE) && !state.`is`(Blocks.PACKED_ICE)) continue
            if (level.getBlockState(pos.above()).isAir) ice += pos.above()
        }
        if (ice.isEmpty()) return
        val checkpoints = listOf(room.real(0, 69, -8), room.real(0, 70, -3), room.real(0, 71, 4), room.real(0, 71, 11))
        solving = true
        Thread({
            try {
                val t = System.currentTimeMillis()
                paths = solveAll(ice, checkpoints)
                ced.cedclient.utils.Debug.line("Ice Fill Solver", "solved ${paths.size} fields in ${System.currentTimeMillis() - t}ms")
            } finally { solving = false }
        }, "CedClient-IceFill").apply { isDaemon = true }.start()
    }

    private fun solveAll(ice: Set<BlockPos>, checkpoints: List<BlockPos>): List<List<BlockPos>> {
        val clusters = ArrayList<Set<BlockPos>>()
        val seen = HashSet<BlockPos>()
        for (start in ice) {
            if (!seen.add(start)) continue
            val cluster = HashSet<BlockPos>()
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                cluster += cur
                for (d in Direction.Plane.HORIZONTAL) cur.relative(d).let { if (it in ice && seen.add(it)) queue.addLast(it) }
            }
            clusters += cluster
        }
        clusters.sortBy { c -> c.minOf { it.distSqr(checkpoints[0]) } }
        return clusters.take(3).mapIndexedNotNull { i, spaces ->
            val path = Field(spaces, spaces.minBy { it.distSqr(checkpoints[i]) }, spaces.minBy { it.distSqr(checkpoints[i + 1]) }).solve()
            path.takeIf { it.isNotEmpty() }
        }
    }

    override fun render(context: LevelRenderContext, room: PuzzleRoomInfo) {
        for (path in paths) PuzzleDraw.line(context, path.map { Vec3(it.x + 0.5, it.y + 0.01, it.z + 0.5) }, color.value)
    }

    private class Field(val spaces: Set<BlockPos>, val start: BlockPos, val end: BlockPos) {
        private val graph = spaces.associateWith { pos -> Direction.Plane.HORIZONTAL.filter { pos.relative(it) in spaces } }

        private val noWallPenalty = 5
        private val doubleTurnPenalty = 2
        private val turnPenalty = 1

        private fun dir(a: BlockPos, b: BlockPos) = when {
            b.x > a.x -> Direction.EAST
            b.x < a.x -> Direction.WEST
            b.z > a.z -> Direction.SOUTH
            else -> Direction.NORTH
        }

        private fun backedByWall(pos: BlockPos, d: Direction) = pos.relative(d) !in spaces

        private fun remainingConnected(current: BlockPos, visited: Set<BlockPos>): Boolean {
            val remaining = spaces.size - visited.size + 1
            if (remaining <= 1) return true
            val seen = HashSet<BlockPos>(remaining)
            val stack = ArrayDeque<BlockPos>()
            stack.add(current); seen.add(current)
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                for (d in graph[cur] ?: emptyList()) {
                    val n = cur.relative(d)
                    if ((n == current || n !in visited) && seen.add(n)) stack.add(n)
                }
            }
            return seen.size == remaining
        }

        private fun cost(path: List<BlockPos>): Int {
            if (path.size < 3) return 0
            var total = 0
            var prev: Direction? = null
            var prevTurn = false
            for (i in 1 until path.size) {
                val d = dir(path[i - 1], path[i])
                if (prev != null && d != prev) {
                    total += turnPenalty
                    if (!backedByWall(path[i - 1], prev)) total += noWallPenalty
                    if (prevTurn) total += doubleTurnPenalty
                    prevTurn = true
                } else prevTurn = false
                prev = d
            }
            return total
        }

        private fun search(stopAtFirst: Boolean, bound: Int): List<BlockPos>? {
            var bestCost = bound
            var bestPath: List<BlockPos>? = null
            val visited = hashSetOf(start)
            val temp = ArrayList<BlockPos>(spaces.size).apply { add(start) }

            fun dfs(current: BlockPos, last: Direction?, lastTurn: Boolean, cost: Int): Boolean {
                if (cost >= bestCost) return false
                if (visited.size == spaces.size) {
                    if (current == end) { bestCost = cost; bestPath = temp.toList(); return stopAtFirst }
                    return false
                }
                if (current == end) return false
                val candidates = (graph[current] ?: emptyList()).map { it to current.relative(it) }.filter { (_, p) -> p !in visited }
                    .sortedWith(compareBy({ (_, p) -> (graph[p] ?: emptyList()).count { p.relative(it) !in visited } }, { (d, _) -> if (d == last) 0 else 1 }))
                for ((d, next) in candidates) {
                    var step = cost
                    var turned = false
                    if (last != null && d != last) {
                        turned = true
                        step += turnPenalty
                        if (!backedByWall(current, last)) step += noWallPenalty
                        if (lastTurn) step += doubleTurnPenalty
                    }
                    if (step >= bestCost) continue
                    visited += next; temp += next
                    val stop = remainingConnected(next, visited) && dfs(next, d, turned, step)
                    temp.removeAt(temp.size - 1); visited -= next
                    if (stop) return true
                }
                return false
            }
            dfs(start, null, false, 0)
            return bestPath
        }

        fun solve(): List<BlockPos> {
            if (start !in spaces || end !in spaces) return emptyList()
            val first = search(stopAtFirst = true, bound = Int.MAX_VALUE) ?: return emptyList()
            return search(stopAtFirst = false, bound = cost(first)) ?: first
        }
    }
}

// ------------------------------------------------------------------------------------------------ Ice Path

/** Ice Path: the silverfish slides until it hits a wall, so a BFS over slides finds the way to the exit; redone when it leaves the line. */
internal object IcePathSolver : PuzzleModule("Ice Path Solver", "Ice Path puzzle: draws the route the silverfish has to slide.", "Ice Path") {
    private val firstColor = ColorSetting("Next Segment Color", Color(0, 255, 0), "The slide to do now.")
    private val color = ColorSetting("Segments Color", Color(255, 0, 0), "The slides after it.")

    private class Grid(val x: Int, val z: Int)
    private class Segment(val start: Vec3, val end: Vec3)

    private const val SIZE = 19
    private const val MIN = -8

    private var grid: Array<IntArray>? = null
    private val segments = ArrayDeque<Segment>()
    private var fish: Silverfish? = null
    private var lastGrid: Grid? = null

    init { addSettings(firstColor, color) }

    override fun enter(level: ClientLevel, room: PuzzleRoomInfo) { leave(); grid = buildGrid(level, room) }
    override fun leave() { grid = null; fish = null; lastGrid = null; segments.clear() }
    override fun reset() = leave()

    private fun buildGrid(level: ClientLevel, room: PuzzleRoomInfo) =
        Array(SIZE) { z -> IntArray(SIZE) { x -> if (level.getBlockState(room.real(MIN + x, 67, MIN + z)).isAir) 0 else 1 } }

    private fun toGrid(pos: BlockPos, room: PuzzleRoomInfo): Grid? {
        val rel = BlockPos(pos.x - room.center.x, 0, pos.z - room.center.z).rotated(-(360 - room.rotation))
        val gx = rel.x - MIN
        val gz = rel.z - MIN
        return if (gx in 0 until SIZE && gz in 0 until SIZE) Grid(gx, gz) else null
    }

    private fun toWorld(p: Grid, room: PuzzleRoomInfo): Vec3 = room.real(MIN + p.x, 66, MIN + p.z).let { Vec3(it.x + 0.5, 67.5, it.z + 0.5) }

    override fun tick(level: ClientLevel, room: PuzzleRoomInfo) {
        var f = fish
        if (f == null || f.isRemoved) {
            val c = room.center
            f = level.getEntitiesOfClass(Silverfish::class.java, AABB(c.x - 10.0, 67.0, c.z - 10.0, c.x + 10.0, 68.0, c.z + 10.0)).firstOrNull() ?: return
            fish = f
        }
        val at = toGrid(f.blockPosition(), room) ?: return
        if (lastGrid?.let { it.x == at.x && it.z == at.z } == true) return
        lastGrid = at

        segments.firstOrNull()?.let { seg ->
            if (abs(f.x - seg.end.x) + abs(f.z - seg.end.z) <= 0.8) { segments.removeFirst(); return }
            val pad = 0.8
            if (f.x in minOf(seg.start.x, seg.end.x) - pad..maxOf(seg.start.x, seg.end.x) + pad &&
                f.z in minOf(seg.start.z, seg.end.z) - pad..maxOf(seg.start.z, seg.end.z) + pad) return
        }
        val g = grid ?: buildGrid(level, room).also { grid = it }
        val path = solve(g, at).takeIf { it.size >= 2 } ?: return
        segments.clear()
        path.zipWithNext { a, b -> segments += Segment(toWorld(a, room), toWorld(b, room)) }
    }

    override fun render(context: LevelRenderContext, room: PuzzleRoomInfo) {
        if (segments.isEmpty() || fish?.isRemoved == true) return
        PuzzleDraw.line(context, listOf(segments.first().start, segments.first().end), firstColor.value)
        if (segments.size > 1) PuzzleDraw.line(context, listOf(segments[1].start) + segments.drop(1).map { it.end }, color.value)
    }

    private fun solve(grid: Array<IntArray>, start: Grid): List<Grid> {
        val parent = Array(SIZE) { arrayOfNulls<Grid>(SIZE) }
        val queue = ArrayDeque(listOf(start))
        parent[start.z][start.x] = start
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (d in Direction.Plane.HORIZONTAL) {
                val next = slide(grid, parent, cur, d) ?: continue
                queue.addLast(next)
                parent[next.z][next.x] = cur
                if (next.x == 7 && next.z == 18) {
                    val path = ArrayDeque<Grid>()
                    var t: Grid? = next
                    while (t != null && t !== start) { path.addFirst(t); t = parent[t.z][t.x] }
                    path.addFirst(start)
                    return path.toList()
                }
            }
        }
        return emptyList()
    }

    private fun slide(grid: Array<IntArray>, parent: Array<Array<Grid?>>, from: Grid, d: Direction): Grid? {
        var steps = 1
        while (true) {
            val nx = from.x + steps * d.stepX
            val nz = from.z + steps * d.stepZ
            if (nx !in 0 until SIZE || nz !in 0 until SIZE || grid[nz][nx] == 1) break
            steps++
        }
        steps--
        if (steps == 0) return null
        val fx = from.x + steps * d.stepX
        val fz = from.z + steps * d.stepZ
        return if (parent[fz][fx] != null) null else Grid(fx, fz)
    }
}
//#endif
