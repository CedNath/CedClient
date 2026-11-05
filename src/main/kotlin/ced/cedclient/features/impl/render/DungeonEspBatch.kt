//#if CHEAT
package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.render.bufferSource
import ced.cedclient.render.draw
import ced.cedclient.render.polyline
import ced.cedclient.features.Module
import ced.cedclient.features.impl.funqol.ClassColors
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.render.FilledBox
import ced.cedclient.render.LineBox
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.DungeonState
import ced.cedclient.state.DungeonTeam
import ced.cedclient.utils.Color
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import ced.cedclient.events.core.TickEvent
import ced.cedclient.events.core.on
import ced.cedclient.render.EntityGlow
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Ghast
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import kotlin.math.sin

/**
 * Draws the boxes of the dungeon ESPs and solvers. Every module that draws boxes has its own box style settings (outline /
 * filled / both, opacity, see through walls, pulse, distance fade): [attach] adds them when the module registers, and [draw]
 * finds the calling module from the stack (or takes it as [owner]) to read that module's style.
 */
object Box3D {
    private const val PKG = "ced.cedclient.features.impl.funqol."
    private val OWNERS = setOf(
        "BlazeSolver", "BloodCamp", "SpringBoots", "I4Helper", "M7Relics", "LividSolver", "GateHighlight", "TrevorHelper",
        "WitherDragons", "WitherESP", "TerminalESP", "TeammateESP", "F4Features", "PuzzleSolvers"
    )
    /** The puzzle solvers aren't modules: their boxes use the Puzzle Solvers module's style. */
    private val SOLVERS = listOf("BoulderSolver", "CreeperBeamSolver", "QuizSolver", "TeleportMazeSolver", "ThreeWeirdosSolver", "TicTacToeSolver")

    class Style {
        val mode = DropdownSetting("Box Mode", listOf("Outline", "Filled", "Both"), "Both", "What a box looks like.")
        val fillOpacity = NumberSetting("Box Fill Opacity %", 35.0, 5.0, 100.0, 5.0, "How solid the fill is.").withDependency { mode.value != "Outline" }
        val phase = BooleanSetting("Boxes Through Walls", true, "Draw the boxes through walls.")
        val pulse = BooleanSetting("Box Pulse", false, "The fill breathes slowly.")
        val fade = BooleanSetting("Box Distance Fade", true, "Boxes further than 40 blocks fade out.")
        val all = arrayOf<Setting<*>>(mode, fillOpacity, phase, pulse, fade)
    }

    private val styles = HashMap<String, Style>()
    private val fallback = Style()

    /** ModuleManager.register: gives a module that draws boxes its box style settings. */
    @JvmStatic
    fun attach(module: Module) {
        if (module.javaClass.simpleName !in OWNERS) return
        val style = Style()
        styles[module.javaClass.name] = style
        module.addSettings(*style.all)
    }

    private fun styleOf(owner: Module?): Style {
        if (owner != null) styles[owner.javaClass.name]?.let { return it }
        val frames = StackWalker.getInstance().walk { f -> f.map { it.className }.toList() }
        for (cls in frames) {
            if (SOLVERS.any { cls == PKG + it || cls.startsWith(PKG + it + "$") }) return styles[PKG + "PuzzleSolvers"] ?: fallback
            for ((name, style) in styles) if (cls == name || cls.startsWith("$name$")) return style
        }
        return fallback
    }

    fun draw(context: LevelRenderContext, boxes: List<Pair<AABB, Color>>, owner: Module? = null) {
        if (boxes.isEmpty()) return
        val st = styleOf(owner)
        val cam = context.gameRenderer().mainCamera().position()
        val poseStack = context.poseStack()
        val bufferSource = context.bufferSource()
        val breathe = if (st.pulse.value) 0.65f + 0.35f * sin(System.currentTimeMillis() / 400.0).toFloat() else 1f
        val shown = boxes.map { (box, c) ->
            val dist = box.center.distanceTo(cam)
            val fadeK = if (st.fade.value) (1.0 - ((dist - 40.0) / 40.0).coerceIn(0.0, 1.0)).toFloat() else 1f
            Triple(box.move(-cam.x, -cam.y, -cam.z), c, fadeK)
        }.filter { it.third > 0.02f }
        if (st.mode.value != "Outline") {
            val fill = bufferSource.getBuffer(if (st.phase.value) CustomRenderType.FILLED_ESP else CustomRenderType.FILLED)
            for ((box, c, k) in shown) FilledBox.draw(poseStack, fill, box, c.redFloat, c.greenFloat, c.blueFloat, st.fillOpacity.value.toFloat() / 100f * breathe * k)
            bufferSource.endBatch()
        }
        if (st.mode.value != "Filled") {
            val buffer = bufferSource.getBuffer(if (st.phase.value) CustomRenderType.LINES_ESP else CustomRenderType.LINES_DEPTH)
            for ((box, c, k) in shown) LineBox.draw(poseStack, buffer, box, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat * k)
            bufferSource.endBatch()
        }
    }

    /** Where an entity is drawn this frame, so boxes glide instead of stepping at 20 Hz. */
    fun smooth(e: Entity): AABB {
        val partial = Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(false)
        return e.boundingBox.move(e.getPosition(partial).subtract(e.position()))
    }
}

/** Runs [collect] once per frame for an ESP module and hands the result to [Box3D]. */
private fun espFrame(module: Module, collect: () -> List<Pair<AABB, Color>>) {
    LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { context ->
        if (!module.isEnabled || Minecraft.getInstance().level == null) return@CollectSubmits
        Box3D.draw(context, collect(), module)
    })
}

/**
 * Boxes the Withers in the boss room (Goldor's and the dragons' phase). Unique: colours by health (green, yellow, red)
 * so you can see which one is nearly dead.
 */
object WitherESP : Module("Wither ESP", Category.Dungeons, "Boxes the Wither bosses in the dungeon boss room, coloured by health.") {
    private val healthColors = BooleanSetting("Colour By Health", true, "Green when healthy, red when nearly dead.")
    private val color = ColorSetting("Color", Color(170, 85, 255), "Box colour when Colour By Health is off.")
    private val bossRoomOnly = BooleanSetting("Boss Room Only", true, "Only inside a dungeon boss room.")

    init {
        addSettings(healthColors, color, bossRoomOnly)
        espFrame(this) {
            val level = Minecraft.getInstance().level ?: return@espFrame emptyList()
            if (bossRoomOnly.value && !(DungeonState.inDungeon && DungeonState.inBoss)) return@espFrame emptyList()
            level.entitiesForRendering().filterIsInstance<WitherBoss>().filter { it.isAlive }.map { w ->
                val c = if (healthColors.value) {
                    val f = (w.health / w.maxHealth).coerceIn(0f, 1f)
                    Color((255 * (1 - f)).toInt().coerceIn(0, 255), (255 * f).toInt().coerceIn(0, 255), 60)
                } else color.value
                Box3D.smooth(w).inflate(0.05) to c
            }
        }
    }
}

/**
 * Boxes the terminals of Goldor's phase (the "Inactive Terminal" armor stands). Unique: finished terminals turn
 * grey for a while instead of vanishing, and the closest unfinished one is drawn in white.
 */
object TerminalESP : Module("Terminal ESP", Category.Dungeons, "Boxes the terminals of F7 phase 3: open ones bright, finished ones grey.") {
    private val open = ColorSetting("Open Color", Color(85, 255, 85), "An inactive (still to do) terminal.")
    private val done = ColorSetting("Done Color", Color(130, 130, 130), "A finished terminal.")
    private val showDone = BooleanSetting("Show Finished", true, "Keep finished terminals as grey boxes.")
    private val nearestBoost = BooleanSetting("Highlight Nearest", true, "The nearest open terminal is drawn in white.")

    init {
        addSettings(open, done, showDone, nearestBoost)
        espFrame(this) {
            val mc = Minecraft.getInstance()
            val level = mc.level ?: return@espFrame emptyList()
            val player = mc.player ?: return@espFrame emptyList()
            if (!DungeonState.inDungeon) return@espFrame emptyList()
            val stands = level.entitiesForRendering().filterIsInstance<ArmorStand>().mapNotNull { s ->
                val name = s.customName?.string ?: return@mapNotNull null
                when {
                    name.contains("Inactive Terminal", true) -> s to true
                    name.contains("Terminal Active", true) || name.contains("Terminal Done", true) -> s to false
                    else -> null
                }
            }
            val nearest = stands.filter { it.second }.minByOrNull { it.first.distanceToSqr(player) }?.first
            stands.mapNotNull { (s, isOpen) ->
                if (!isOpen && !showDone.value) return@mapNotNull null
                val c = if (!isOpen) done.value else if (nearestBoost.value && s === nearest) Color(255, 255, 255) else open.value
                AABB(s.x - 0.5, s.y - 1.0, s.z - 0.5, s.x + 0.5, s.y + 1.0, s.z + 0.5) to c
            }
        }
    }
}

/**
 * Boxes your dungeon teammates in their class colour. Unique: uses the Class Colors module (one colour setting for
 * everything), skips dead teammates unless asked, and can leave out the teammate you are aiming at so the
 * crosshair stays clear.
 */
object TeammateESP : Module("Teammate ESP", Category.Dungeons, "Boxes your dungeon teammates in their class colour.") {
    private val dead = BooleanSetting("Show Dead", false, "Also box teammates who are dead (grey).")
    private val hideCrosshair = BooleanSetting("Hide Under Crosshair", true, "Do not box the teammate you are aiming at.")

    init {
        addSettings(dead, hideCrosshair)
        espFrame(this) {
            val mc = Minecraft.getInstance()
            val level = mc.level ?: return@espFrame emptyList()
            if (!DungeonState.inDungeon) return@espFrame emptyList()
            val members = DungeonTeam.members().associateBy { it.name.lowercase() }
            val aimed = (mc.crosshairPickEntity as? Player)?.gameProfile?.name?.lowercase()
            level.players().mapNotNull { p ->
                if (p === mc.player) return@mapNotNull null
                val m = members[p.gameProfile.name.lowercase()] ?: return@mapNotNull null
                if (m.dead && !dead.value) return@mapNotNull null
                if (hideCrosshair.value && p.gameProfile.name.lowercase() == aimed) return@mapNotNull null
                val c = if (m.dead) 0xFF888888.toInt() else ClassColors.color(m.cls)
                Box3D.smooth(p).inflate(0.05) to Color(c shr 16 and 255, c shr 8 and 255, c and 255)
            }
        }
    }
}

/**
 * Floor 4 extras, like NoammAddons' F4 Features: outline glow on Thorn (the Ghast), the Spirit Bear and the Spirit Bows
 * lying around, plus a Spirit Bear spawn tracker HUD (how many of the arena's blocks are lit, and the 3.4 s countdown
 * once the last one is). Unique: "Style" can also draw the old boxes, and a chat line when the bear shows up.
 */
object F4Features : Module("F4 Features", Category.Dungeons, "Glow on Thorn, the Spirit Bear and Spirit Bows, plus a Spirit Bear spawn tracker."), HudElement {
    private val style = DropdownSetting("Style", listOf("Glow", "Box", "Both"), "Glow", "How Thorn and the Spirit Bear are marked (Spirit Bows always glow).")
    private val thorn = BooleanSetting("ESP Thorn", true, "Highlight Thorn.")
    private val bear = BooleanSetting("ESP Spirit Bear", true, "Highlight the Spirit Bear.")
    private val bow = BooleanSetting("ESP Spirit Bow", true, "Highlight the Spirit Bow.")
    private val bearHud = BooleanSetting("Spirit Bear HUD", true, "Shows the lit spawn blocks and the spawn countdown.")
    private val thornColor = ColorSetting("Thorn Color", Color(255, 0, 0), "Thorn's highlight.").withDependency { thorn.value }
    private val bearColor = ColorSetting("Bear Color", Color(255, 0, 255), "Spirit Bear's highlight.").withDependency { bear.value }
    private val bowColor = ColorSetting("Bow Color", Color(0, 255, 255), "Spirit Bow's highlight.").withDependency { bow.value }
    private val announce = BooleanSetting("Announce Bear", true, "A chat line the first time the bear shows up.")
    private var announced = false

    // The Spirit Bear spawns once the blocks of a ring around the arena turn from coal to sea lanterns; the last one
    // (7, 77, 34) starts a 68 tick countdown. Positions as NoammAddons has them.
    private const val SPAWN_TICKS = 68
    private val LAST = BlockPos(7, 77, 34)
    private val F4_BLOCKS = listOf(
        BlockPos(-3, 77, 33), BlockPos(-9, 77, 31), BlockPos(-16, 77, 26), BlockPos(-20, 77, 20), BlockPos(-23, 77, 13),
        BlockPos(-24, 77, 6), BlockPos(-24, 77, 0), BlockPos(-22, 77, -7), BlockPos(-18, 77, -13), BlockPos(-12, 77, -19),
        BlockPos(-5, 77, -22), BlockPos(1, 77, -24), BlockPos(8, 77, -24), BlockPos(14, 77, -23), BlockPos(21, 77, -19),
        BlockPos(27, 77, -14), BlockPos(31, 77, -8), BlockPos(33, 77, -1), BlockPos(34, 77, 5), BlockPos(33, 77, 12),
        BlockPos(31, 77, 19), BlockPos(27, 77, 25), BlockPos(20, 77, 30), BlockPos(14, 77, 33), LAST
    )
    private val M4_BLOCKS = listOf(
        BlockPos(-2, 77, 33), BlockPos(-7, 77, 32), BlockPos(-13, 77, 28), BlockPos(-17, 77, 24), BlockPos(-21, 77, 18),
        BlockPos(-23, 77, 13), BlockPos(-24, 77, 7), BlockPos(-24, 77, 2), BlockPos(-23, 77, -4), BlockPos(-21, 77, -9),
        BlockPos(-17, 77, -14), BlockPos(-12, 77, -19), BlockPos(-6, 77, -22), BlockPos(-1, 77, -23), BlockPos(5, 77, -24),
        BlockPos(10, 77, -24), BlockPos(16, 77, -22), BlockPos(21, 77, -19), BlockPos(27, 77, -15), BlockPos(30, 77, -10),
        BlockPos(32, 77, -5), BlockPos(34, 77, 1), BlockPos(34, 77, 7), BlockPos(33, 77, 12), BlockPos(31, 77, 18),
        BlockPos(28, 77, 23), BlockPos(23, 77, 28), BlockPos(18, 77, 31), BlockPos(12, 77, 33), LAST
    )

    private val lit = HashMap<BlockPos, Boolean>()
    private var count = 0
    private var ticks = 0L
    private var spawnAt = 0L

    private fun inBossRoom() = DungeonState.inDungeon && DungeonState.inBoss && DungeonState.floor in setOf("F4", "M4")
    private fun rgb(c: Color) = (c.red shl 16) or (c.green shl 8) or c.blue
    private val useGlow get() = style.value != "Box"
    private val useBox get() = style.value != "Glow"

    private fun glowColor(e: Entity): Int? {
        if (!isEnabled || !inBossRoom()) return null
        return when {
            useGlow && thorn.value && e is Ghast -> rgb(thornColor.value)
            useGlow && bear.value && e is Player && e.gameProfile.name().startsWith("spirit bear", true) -> rgb(bearColor.value)
            bow.value && e is ArmorStand && e.getItemBySlot(EquipmentSlot.MAINHAND).hoverName.string == "Bow" -> rgb(bowColor.value)
            else -> null
        }
    }

    private fun resetTracker() { lit.clear(); count = 0; spawnAt = 0L }

    /** Polls the ring blocks once a tick (no block-update hook needed): coal -> sea lantern counts one up, the reverse one down. */
    private fun track() {
        ticks++
        val level = Minecraft.getInstance().level
        if (level == null || !isEnabled || !bearHud.value || !inBossRoom()) { resetTracker(); return }
        val blocks = if (DungeonState.floor == "M4") M4_BLOCKS else F4_BLOCKS
        for (pos in blocks) {
            val block = level.getBlockState(pos).block
            val now = block == Blocks.SEA_LANTERN
            val before = lit.put(pos, now)
            if (before == null || before == now || (block != Blocks.SEA_LANTERN && block != Blocks.COAL_BLOCK)) continue
            if (now) {
                if (pos == LAST) spawnAt = ticks + SPAWN_TICKS
                count = (count + 1).coerceAtMost(blocks.size)
            } else {
                if (pos == LAST) spawnAt = 0L
                count = (count - 1).coerceAtLeast(0)
            }
            ced.cedclient.utils.Debug.line("F4 Features", "spirit bear block ${pos.x},${pos.y},${pos.z} -> $count/${blocks.size}")
        }
    }

    init {
        addSettings(style, thorn, bear, bow, bearHud, thornColor, bearColor, bowColor, announce)
        EntityGlow.register(::glowColor)
        on<TickEvent.End> { track() }
        espFrame(this) {
            val mc = Minecraft.getInstance()
            val level = mc.level ?: return@espFrame emptyList()
            if (!DungeonState.inDungeon) { announced = false; return@espFrame emptyList() }
            val out = ArrayList<Pair<AABB, Color>>()
            for (s in level.entitiesForRendering()) {
                val name = (s as? ArmorStand)?.customName?.string ?: continue
                if (useBox && thorn.value && name.contains("Thorn", true)) out += AABB(s.x - 0.5, s.y - 1.8, s.z - 0.5, s.x + 0.5, s.y + 0.2, s.z + 0.5) to thornColor.value
                if (name.contains("Spirit Bear", true)) {
                    if (useBox && bear.value) out += AABB(s.x - 0.8, s.y - 1.6, s.z - 0.8, s.x + 0.8, s.y + 0.4, s.z + 0.8) to bearColor.value
                    if (announce.value && !announced) {
                        announced = true
                        mc.player?.sendSystemMessage(net.minecraft.network.chat.Component.literal("§b[CC] §fSpirit Bear is up"))
                    }
                }
            }
            out
        }
    }

    // ---------------------------------------------------------------- HUD

    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") tickCounter: DeltaTracker) {
        if (!isEnabled || !bearHud.value || Minecraft.getInstance().gui.hud.isHidden || !inBossRoom()) return
        renderInternal(g)
    }

    override fun renderInternal(g: GuiGraphicsExtractor) {
        val total = if (DungeonState.floor == "M4") M4_BLOCKS.size else F4_BLOCKS.size
        val left = ((spawnAt - ticks) / 20.0).takeIf { spawnAt > ticks }
        val rows = listOf("Blocks" to "$count/$total", "Spawn" to (left?.let { "%.1fs".format(java.util.Locale.ROOT, it) } ?: "-"))
        val s = panelScale
        val size = 10f * s
        val lineH = 12f * s
        val pad = 6f * s
        val gap = 10f * s
        val leftW = rows.maxOf { HudCard.width(it.first, size) }
        val rightW = rows.maxOf { HudCard.width(it.second, size) }
        val width = HudCard.steady("Spirit Bear", leftW + gap + rightW + pad * 2, s)
        val height = rows.size * lineH + pad * 2 - (lineH - size)
        lastWidth = (width / s).toInt()
        lastHeight = (height / s).toInt()
        HudCard.draw(g, panelX, panelY) {
            HudCard.card(width, height, s)
            rows.forEachIndexed { i, (name, value) ->
                val y = pad + i * lineH
                HudCard.text(name, pad, y, size, 0xFFBBBBBB.toInt())
                HudCard.text(value, width - pad - HudCard.width(value, size), y, size, if (i == 1 && left != null) 0xFFFFAA00.toInt() else -1)
            }
        }
    }

    override val label = "Spirit Bear"
    private const val DEFAULT_X = 3
    private const val DEFAULT_Y = 160
    override var panelX = DEFAULT_X
    override var panelY = DEFAULT_Y
    override var panelScale = 1f
    override var lastWidth = 0
        private set
    override var lastHeight = 0
        private set

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val gson = com.google.gson.Gson()
    private val hudFile by lazy { ced.cedclient.config.CedPaths.hud("spirit_bear") }

    override fun save() {
        runCatching { hudFile.parentFile?.mkdirs(); hudFile.writeText(gson.toJson(HudPosition(panelX, panelY, panelScale))) }
    }

    override fun resetPosition() { panelX = DEFAULT_X; panelY = DEFAULT_Y; panelScale = 1f }
    override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.4f, 3f) }

    init {
        runCatching { gson.fromJson(hudFile.readText(), HudPosition::class.java) }.getOrNull()?.let {
            panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.4f, 3f)
        }
    }
}
//#endif
