//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.achievements.Achievements
import ced.cedclient.render.bufferSource
import ced.cedclient.render.draw
import ced.cedclient.render.polyline
import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.render.FilledBox
import ced.cedclient.render.LineBox
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.Color
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * F7 Necron: after Goldor, Necron drags every player (about 57 blocks, 50 ticks) down towards a point on the platform
 * row at z = 112. The first tick that moves you (more than 0.9 blocks sideways in one tick) gives the start and the
 * direction; the line from the start through where you are now, cut off at the drag length, is the landing spot. The
 * guess is refined every tick until you have moved 6 blocks, then it is locked.
 *
 * Unique: the marker is green over a platform and red over lava (with a title and a different sound), a dashed path
 * line shows the whole drag from start to landing, and when the drag is over you get a chat line with how far off
 * the prediction was (also counted for the Gravity Check achievement).
 */
object NecronDrop : Module(
    "Necron Drop",
    Category.Dungeons,
    "Predicts where Necron's drag in F7 puts you: a marker on the landing spot, green on a platform, red over lava."
) {
    private val style = DropdownSetting("Style", listOf("Marker", "Beacon"), "Marker", "A small box at the landing spot, or a tall thin beam.")
    private val safeColor = ColorSetting("Safe Color", Color(85, 255, 85), "Landing on a platform.")
    private val lavaColor = ColorSetting("Lava Color", Color(255, 60, 60), "Landing over lava.")
    private val unknownColor = ColorSetting("Unknown Color", Color(255, 255, 85), "No block found below the landing spot.")
    private val path = BooleanSetting("Path Line", true, "A line from where the drag started to the landing spot.")
    private val popup = BooleanSetting("Landing Title", true, "A title with a sound when the guess locks: Above Lava or Above Platform.")
    private val accuracy = BooleanSetting("Accuracy Message", true, "A chat line after the drag with how far off the prediction was.")

    private const val DRAG_LEN = 57.5
    private const val DRAG_BASE_Y = 64.4
    private const val DRAG_LEN_PER_Y = 0.064
    private const val START_DZ = 0.9
    private const val TARGET_X_MIN = 47.5
    private const val TARGET_X_MAX = 63.5
    private const val TARGET_Z = 112.0
    private const val TARGET_Y = 70.0
    private const val LOCK_DIST = 6.0
    private const val STOP_TICKS = 10
    private const val MAX_WAIT_TICKS = 200

    private val mc get() = Minecraft.getInstance()

    private var windowOpen = false
    private var phaseTicks = 0
    private var prev: Vec3? = null
    private var start: Vec3? = null
    private var landing: Vec3? = null
    private var locked = false
    private var dragOver = false
    private var lastMoveTick = 0
    private var reported = false
    private var world: Any? = null

    private fun reset() {
        windowOpen = false; phaseTicks = 0; prev = null; start = null; landing = null
        locked = false; dragOver = false; lastMoveTick = 0; reported = false
    }

    private fun active() = isEnabled && DungeonState.inDungeon && DungeonState.floor?.endsWith("7") == true && windowOpen

    /** True over lava, false over something solid, null when nothing is found below. */
    private fun overLava(at: Vec3): Boolean? {
        val level = mc.level ?: return null
        val pos = BlockPos.MutableBlockPos(Math.floor(at.x).toInt(), Math.floor(at.y).toInt(), Math.floor(at.z).toInt())
        while (pos.y > level.minY) {
            val state = level.getBlockState(pos)
            if (state.fluidState.`is`(FluidTags.LAVA)) return true
            if (!state.isAir && !state.getCollisionShape(level, pos).isEmpty) return false
            pos.move(0, -1, 0)
        }
        return null
    }

    private fun colorFor(at: Vec3): Color = when (overLava(at)) { true -> lavaColor.value; false -> safeColor.value; null -> unknownColor.value }

    init {
        addSettings(style, safeColor, lavaColor, unknownColor, path, popup, accuracy)

        on<ChatMessageEvent> {
            if (isEnabled && DungeonState.inDungeon && !windowOpen && it.unformattedText.trim().startsWith("[BOSS] Necron:")) {
                reset()
                windowOpen = true
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (client.level !== world) { world = client.level; reset() }
            if (!active()) return@register
            val player = client.player ?: return@register
            val pos = Vec3(player.x, player.y, player.z)
            phaseTicks++
            val before = prev
            prev = pos

            val begin = start
            if (begin == null) {
                if (before != null && phaseTicks < MAX_WAIT_TICKS && pos.z - before.z > START_DZ) start = before
                return@register
            }
            if (locked) {
                if (before != null && hypot(pos.x - before.x, pos.z - before.z) > 0.3) lastMoveTick = phaseTicks
                if (!dragOver && phaseTicks - lastMoveTick > STOP_TICKS) { dragOver = true; finish(pos) }
                return@register
            }

            val dx = pos.x - begin.x
            val dz = pos.z - begin.z
            if (dz < 0.5) { start = null; landing = null; return@register }
            val targetX = (begin.x + dx / dz * (TARGET_Z - begin.z)).coerceIn(TARGET_X_MIN, TARGET_X_MAX)
            val tx = targetX - begin.x
            val tz = TARGET_Z - begin.z
            val dist = hypot(tx, tz)
            val len = min(DRAG_LEN - max(begin.y - DRAG_BASE_Y, 0.0) * DRAG_LEN_PER_Y, dist)
            val spot = Vec3(begin.x + tx / dist * len, begin.y + (TARGET_Y - begin.y) * len / dist, begin.z + tz / dist * len)
            landing = spot
            lastMoveTick = phaseTicks
            if (hypot(dx, dz) >= LOCK_DIST) {
                locked = true
                Achievements.bump("necron_drops")
                if (popup.value) when (overLava(spot)) {
                    true -> Alerts.title("Necron Drop", "Above Lava!", "", 0xFF5555, true, 0.6f)
                    false -> Alerts.title("Necron Drop", "Above Platform", "", 0x55FF55, true, 1.4f)
                    null -> {}
                }
            }
        }

        LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { context ->
            if (!active() || dragOver) return@CollectSubmits
            val l = landing ?: return@CollectSubmits
            val cam = context.gameRenderer().mainCamera().position()
            val c = colorFor(l)
            val box = if (style.value == "Beacon") AABB(l.x - 0.15, l.y - 40.0, l.z - 0.15, l.x + 0.15, l.y + 300.0, l.z + 0.15)
            else AABB(l.x - 0.3, l.y, l.z - 0.3, l.x + 0.3, l.y + 1.8, l.z + 0.3)
            val rel = box.move(-cam.x, -cam.y, -cam.z)
            val poseStack = context.poseStack()
            val buffers = context.bufferSource()
            val fill = buffers.getBuffer(CustomRenderType.FILLED_ESP)
            FilledBox.draw(poseStack, fill, rel, c.redFloat, c.greenFloat, c.blueFloat, 0.35f)
            buffers.endBatch()
            val lines = buffers.getBuffer(CustomRenderType.LINES_ESP)
            LineBox.draw(poseStack, lines, rel, c.redFloat, c.greenFloat, c.blueFloat, 1f)
            val from = start
            if (path.value && from != null) {
                // Dashed: every second segment of the straight line from the start to the landing spot.
                val steps = 24
                for (i in 0 until steps step 2) {
                    val a = from.lerp(l, i / steps.toDouble()).subtract(cam)
                    val b = from.lerp(l, (i + 1) / steps.toDouble()).subtract(cam)
                    LineBox.polyline(poseStack, lines, listOf(a, b), 1f, 1f, 1f, 0.8f)
                }
            }
            buffers.endBatch()
        })
    }

    private fun finish(end: Vec3) {
        if (reported) return
        reported = true
        val l = landing ?: return
        if (!accuracy.value) return
        val off = hypot(end.x - l.x, end.z - l.z)
        val where = when (overLava(end)) { true -> "over lava"; false -> "on a platform"; null -> "unknown" }
        mc.player?.sendSystemMessage(
            net.minecraft.network.chat.Component.literal("§b[CC] §7Necron drag ended $where, prediction was off by §f%.1f §7blocks".format(java.util.Locale.ROOT, off))
        )
    }
}
//#endif
