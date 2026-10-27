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
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.render.FilledBox
import ced.cedclient.render.LineBox
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.DungeonState
import ced.cedclient.state.DungeonTeam
import ced.cedclient.utils.Color
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import kotlin.math.sin

/**
 * The shared look of the boxes drawn by the dungeon ESPs below (Wither ESP, Terminal ESP, Teammate ESP, F4 Features):
 * outline, filled or both, with an opacity and a see-through switch. Unique: a Pulse option that breathes the fill, and
 * a Distance Fade that dims far boxes so a crowded room stays readable.
 */
object Box3D : Module(
    "Box3D",
    Category.Visuals,
    "Outline / fill style shared by the dungeon ESP modules (Wither, Terminal, Teammate, F4)."
) {
    private val mode = DropdownSetting("Mode", listOf("Outline", "Filled", "Both"), "Both", "What a box looks like.")
    private val fillOpacity = NumberSetting("Fill Opacity %", 35.0, 5.0, 100.0, 5.0, "How solid the fill is.").withDependency { mode.value != "Outline" }
    private val phase = BooleanSetting("See Through Walls", true, "Draw the boxes through walls.")
    private val pulse = BooleanSetting("Pulse", false, "The fill breathes slowly.")
    private val fade = BooleanSetting("Distance Fade", true, "Boxes further than 40 blocks fade out.")

    init { addSettings(mode, fillOpacity, phase, pulse, fade) }

    /** The look is applied even while this module is off (its settings are still read), so the ESPs never depend on it being toggled. */
    fun draw(context: LevelRenderContext, boxes: List<Pair<AABB, Color>>) {
        if (boxes.isEmpty()) return
        val cam = context.gameRenderer().mainCamera().position()
        val poseStack = context.poseStack()
        val bufferSource = context.bufferSource()
        val breathe = if (pulse.value) 0.65f + 0.35f * sin(System.currentTimeMillis() / 400.0).toFloat() else 1f
        val shown = boxes.map { (box, c) ->
            val dist = box.center.distanceTo(cam)
            val fadeK = if (fade.value) (1.0 - ((dist - 40.0) / 40.0).coerceIn(0.0, 1.0)).toFloat() else 1f
            Triple(box.move(-cam.x, -cam.y, -cam.z), c, fadeK)
        }.filter { it.third > 0.02f }
        if (mode.value != "Outline") {
            val fill = bufferSource.getBuffer(if (phase.value) CustomRenderType.FILLED_ESP else CustomRenderType.FILLED)
            for ((box, c, k) in shown) FilledBox.draw(poseStack, fill, box, c.redFloat, c.greenFloat, c.blueFloat, fillOpacity.value.toFloat() / 100f * breathe * k)
            bufferSource.endBatch()
        }
        if (mode.value != "Filled") {
            val buffer = bufferSource.getBuffer(if (phase.value) CustomRenderType.LINES_ESP else CustomRenderType.LINES_DEPTH)
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
        Box3D.draw(context, collect())
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
 * Floor 4 extras: boxes Thorn and the Spirit Bear (found by their name tags). Unique: one box colour per target and a
 * one-time chat line when the Spirit Bear shows up.
 */
object F4Features : Module("F4 Features", Category.Dungeons, "Boxes Thorn and the Spirit Bear on floor 4.") {
    private val thorn = BooleanSetting("Box Thorn", true, "Box Thorn's name tag position.")
    private val bear = BooleanSetting("Box Spirit Bear", true, "Box the Spirit Bear.")
    private val thornColor = ColorSetting("Thorn Color", Color(255, 170, 0), "Thorn's box.").withDependency { thorn.value }
    private val bearColor = ColorSetting("Bear Color", Color(85, 255, 255), "Spirit Bear's box.").withDependency { bear.value }
    private val announce = BooleanSetting("Announce Bear", true, "A chat line the first time the bear shows up.")
    private var announced = false

    init {
        addSettings(thorn, bear, thornColor, bearColor, announce)
        espFrame(this) {
            val mc = Minecraft.getInstance()
            val level = mc.level ?: return@espFrame emptyList()
            if (!DungeonState.inDungeon) { announced = false; return@espFrame emptyList() }
            val out = ArrayList<Pair<AABB, Color>>()
            for (s in level.entitiesForRendering()) {
                val name = (s as? ArmorStand)?.customName?.string ?: continue
                if (thorn.value && name.contains("Thorn", true)) out += AABB(s.x - 0.5, s.y - 1.8, s.z - 0.5, s.x + 0.5, s.y + 0.2, s.z + 0.5) to thornColor.value
                if (bear.value && name.contains("Spirit Bear", true)) {
                    out += AABB(s.x - 0.8, s.y - 1.6, s.z - 0.8, s.x + 0.8, s.y + 0.4, s.z + 0.8) to bearColor.value
                    if (announce.value && !announced) {
                        announced = true
                        mc.player?.sendSystemMessage(net.minecraft.network.chat.Component.literal("§b[CC] §fSpirit Bear is up"))
                    }
                }
            }
            out
        }
    }
}
//#endif
