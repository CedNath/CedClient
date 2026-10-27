package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.render.bufferSource
import ced.cedclient.render.draw
import ced.cedclient.render.polyline
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.state.DungeonRunInfo
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.Color
import ced.cedclient.utils.skyblockId
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

/**
 * Chat and title alerts for the dungeon score: 270 (S) and 300 (S+) messages to the party, plus one-time messages when the
 * mimic, the prince or the bat is killed. Unique: a boss-start summary (the score you walk in with and how far off 300 it
 * is), a configurable "almost" warning a few points before each target, and the sound pitch rises with the target.
 */
object ScoreCalculator : Module(
    "Score Calculator",
    Category.Dungeons,
    "Party messages and titles at 270 / 300 score, and when the mimic, prince or bat is killed."
) {
    private val msg270 = BooleanSetting("270 Message", true, "A party message at 270 score.")
    private val text270 = TextSetting("270 Text", "270", "What to send at 270.", 100).withDependency { msg270.value }
    private val title270 = BooleanSetting("270 Title", false, "A title at 270 score.")
    private val msg300 = BooleanSetting("300 Message", true, "A party message at 300 score.")
    private val text300 = TextSetting("300 Text", "300", "What to send at 300.", 100).withDependency { msg300.value }
    private val title300 = BooleanSetting("300 Title", true, "A title at 300 score.")
    private val almost = NumberSetting("Almost Warning (points)", 0.0, 0.0, 20.0, 1.0, "A quiet title this many points before a target (0 = off).")
    private val mimicMsg = BooleanSetting("Mimic Message", true, "A party message when the mimic is killed.")
    private val princeMsg = BooleanSetting("Prince Message", true, "A party message when the prince is killed.")
    private val batMsg = BooleanSetting("Bat Message", true, "A party message when a bat is killed.")
    private val bossSummary = BooleanSetting("Boss Start Summary", true, "When the boss room starts: your score and how far it is from 300.")
    private val color = ColorSetting("Title Color", Color(255, 215, 0), "Colour of the titles.")

    private var sent270 = false
    private var sent300 = false
    private var warned270 = false
    private var warned300 = false
    private var sentMimic = false
    private var sentPrince = false
    private var sentBat = false
    private var summarised = false
    private var world: Any? = null

    private fun say(text: String) {
        if (text.isBlank()) return
        Minecraft.getInstance().player?.connection?.sendCommand("pc $text")
    }

    private fun reset() {
        sent270 = false; sent300 = false; warned270 = false; warned300 = false
        sentMimic = false; sentPrince = false; sentBat = false; summarised = false
    }

    init {
        addSettings(msg270, text270, title270, msg300, text300, title300, almost, mimicMsg, princeMsg, batMsg, bossSummary, color)
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (mc.level !== world) { world = mc.level; reset() }
            if (!isEnabled || !DungeonState.inDungeon) return@register
            val score = DungeonRunInfo.score
            val tint = color.value.rgba and 0xFFFFFF
            val warn = almost.value.toInt()
            if (warn > 0 && !warned270 && score in (270 - warn) until 270) { warned270 = true; Alerts.title("Score", "Almost 270", "$score", tint, false, 0.9f) }
            if (warn > 0 && !warned300 && score in (300 - warn) until 300) { warned300 = true; Alerts.title("Score", "Almost 300", "$score", tint, false, 1.1f) }
            if (!sent270 && score >= 270) {
                sent270 = true
                if (msg270.value) say(text270.value)
                if (title270.value) Alerts.title("Score", "270 Score", "S", tint, true, 1.0f)
            }
            if (!sent300 && score >= 300) {
                sent300 = true
                if (msg300.value) say(text300.value)
                if (title300.value) Alerts.title("Score", "300 Score", "S+", tint, true, 1.4f)
            }
            if (!sentMimic && DungeonRunInfo.mimic) { sentMimic = true; if (mimicMsg.value) say("Mimic dead!") }
            if (!sentPrince && DungeonRunInfo.prince) { sentPrince = true; if (princeMsg.value) say("Prince dead!") }
            if (!sentBat && DungeonRunInfo.bat) { sentBat = true; if (batMsg.value) say("Bat dead!") }
            if (bossSummary.value && !summarised && DungeonState.inBoss) {
                summarised = true
                val missing = (300 - score).coerceAtLeast(0)
                Alerts.title("Score", "Score $score", if (missing == 0) "S+ reached" else "$missing to 300", tint, false, 1.0f)
            }
        }
    }
}

//#if CHEAT
/**
 * Shows where an etherwarp would land while you sneak with an etherwarp item: a box on the target block, green when the
 * teleport is allowed (solid block with two free blocks above) and red when it fails. Unique: the box colour fades from
 * green to yellow as the target gets close to the maximum range, so you can tell at a glance if a long warp is safe;
 * "Always Show" also draws it while standing, not only while sneaking.
 */
object EtherwarpOverlay : Module(
    "Etherwarp",
    Category.Dungeons,
    "Box on the block an etherwarp would land on while sneaking with an etherwarp item."
) {
    private val range = NumberSetting("Range (blocks)", 61.0, 20.0, 100.0, 1.0, "How far the warp reaches (61 for a fully upgraded item).")
    private val showFail = BooleanSetting("Show Fail", true, "Draw a red box where the warp would fail.")
    private val alwaysShow = BooleanSetting("Always Show", false, "Also draw while standing (not only sneaking).")
    private val phase = BooleanSetting("See Through Walls", true, "Draw the box through walls.")
    private val okColor = ColorSetting("OK Color", Color(85, 255, 85), "A warp that works.")
    private val style = ced.cedclient.features.settings.DropdownSetting("Style", listOf("Outline", "Filled", "Filled Outline"), "Outline", "How the box looks.")
    private val fillOpacity = NumberSetting("Fill Opacity %", 30.0, 5.0, 100.0, 5.0, "How solid the fill is.").withDependency { style.value != "Outline" }
    private val useFar = BooleanSetting("Use Far Color", true, "Fade the box towards the far colour as the target nears the maximum range. Off: always the OK colour.")
    private val farColor = ColorSetting("Far Color", Color(255, 255, 85), "A warp that works but is near the maximum range.").withDependency { useFar.value }
    private val failColor = ColorSetting("Fail Color", Color(255, 85, 85), "A warp that fails.").withDependency { showFail.value }

    private val ITEMS = setOf("ASPECT_OF_THE_VOID", "ASPECT_OF_THE_END", "ETHERWARP_CONDUIT", "ETHERWARP_MERGER")

    private data class Target(val pos: net.minecraft.core.BlockPos, val ok: Boolean, val distance: Double)

    private fun target(): Target? {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val id = player.mainHandItem.skyblockId ?: return null
        if (id !in ITEMS) return null
        if (!player.isShiftKeyDown && !alwaysShow.value) return null
        val from = player.getEyePosition(1f)
        val to = from.add(player.getViewVector(1f).scale(range.value))
        val hit = level.clip(net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player))
        if (hit.type != net.minecraft.world.phys.HitResult.Type.BLOCK) return null
        val pos = hit.blockPos
        val free = level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty &&
            level.getBlockState(pos.above(2)).getCollisionShape(level, pos.above(2)).isEmpty
        return Target(pos, free, from.distanceTo(hit.location))
    }

    init {
        addSettings(range, showFail, alwaysShow, phase, style, fillOpacity, okColor, useFar, farColor, failColor)
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(
            net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.CollectSubmits { context ->
                if (!isEnabled) return@CollectSubmits
                val t = target() ?: return@CollectSubmits
                if (!t.ok && !showFail.value) return@CollectSubmits
                val cam = context.gameRenderer().mainCamera().position()
                val k = (t.distance / range.value).coerceIn(0.0, 1.0)
                val base = if (!t.ok) failColor.value else okColor.value
                val far = farColor.value
                val c = if (!t.ok || !useFar.value) base else Color(
                    (base.red + (far.red - base.red) * k).toInt(), (base.green + (far.green - base.green) * k).toInt(),
                    (base.blue + (far.blue - base.blue) * k).toInt()
                )
                val box = net.minecraft.world.phys.AABB(t.pos).inflate(0.002).move(-cam.x, -cam.y, -cam.z)
                val buffers = context.bufferSource()
                if (style.value != "Outline") {
                    val fill = buffers.getBuffer(if (phase.value) ced.cedclient.render.pipeline.CustomRenderType.FILLED_ESP else ced.cedclient.render.pipeline.CustomRenderType.FILLED)
                    ced.cedclient.render.FilledBox.draw(context.poseStack(), fill, box, c.redFloat, c.greenFloat, c.blueFloat, fillOpacity.value.toFloat() / 100f)
                    buffers.endBatch()
                }
                if (style.value != "Filled") {
                    val buffer = buffers.getBuffer(if (phase.value) ced.cedclient.render.pipeline.CustomRenderType.LINES_ESP else ced.cedclient.render.pipeline.CustomRenderType.LINES_DEPTH)
                    ced.cedclient.render.LineBox.draw(context.poseStack(), buffer, box, c.redFloat, c.greenFloat, c.blueFloat, 1f)
                    buffers.endBatch()
                }
            }
        )
    }
}
//#endif
