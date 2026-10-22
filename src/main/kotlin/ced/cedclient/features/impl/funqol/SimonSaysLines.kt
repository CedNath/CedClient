package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.render.LineBox
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.Color
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

/**
 * F7 phase 3, Simon Says: remembers the order the sea lanterns light up in and draws a line through the buttons
 * still left to press. The device is a 4x4 grid: lanterns at x=111, buttons at x=110, y 120-123, z 92-95.
 */
object SimonSaysLines : Module(
    "Simon Says Lines",
    Category.Skyblock,
    "Draws lines between the next Simon Says buttons."
) {
    private val color = ColorSetting("Line Color", Color(80, 255, 120), "Color of the lines.")
    private val onlyNext = BooleanSetting("Only Next Button", false, "Only the line to the next button, not the whole rest of the sequence.")

    private const val LANTERN_X = 111
    private const val BUTTON_X = 110
    private val Y_RANGE = 120..123
    private val Z_RANGE = 92..95
    private const val ROUND_GAP_MS = 1500L

    private val mc get() = Minecraft.getInstance()

    /** Lantern positions (as button positions) in the order they lit up. */
    private val order = ArrayList<BlockPos>()
    private var showIdx = 0
    private var progress = 0
    private var lastLit = 0L
    private val wasLit = HashSet<BlockPos>()

    private fun active() = isEnabled && DungeonState.inDungeon && DungeonState.f7Phase == 3

    private fun reset() { order.clear(); wasLit.clear(); showIdx = 0; progress = 0; lastLit = 0L }

    init {
        addSettings(color, onlyNext)

        ClientTickEvents.END_CLIENT_TICK.register {
            val level = mc.level
            val player = mc.player
            if (level == null || player == null || !active() || player.distanceToSqr(LANTERN_X.toDouble(), 121.0, 93.0) > 50 * 50) {
                if (order.isNotEmpty()) reset()
                return@register
            }
            val now = System.currentTimeMillis()
            val lit = HashSet<BlockPos>()
            for (y in Y_RANGE) for (z in Z_RANGE) if (level.getBlockState(BlockPos(LANTERN_X, y, z)).`is`(Blocks.SEA_LANTERN)) lit += BlockPos(BUTTON_X, y, z)
            for (p in lit) if (p !in wasLit) {
                if (now - lastLit > ROUND_GAP_MS) { showIdx = 0; progress = 0 }
                lastLit = now
                when {
                    showIdx < order.size && order[showIdx] == p -> showIdx++
                    showIdx == order.size -> { order += p; showIdx++ }
                    else -> { order.clear(); order += p; showIdx = 1 }
                }
            }
            wasLit.clear(); wasLit.addAll(lit)
        }

        UseBlockCallback.EVENT.register(UseBlockCallback { _, level, _, hit ->
            if (level.isClientSide && active() && progress < order.size && hit.blockPos == order[progress]) progress++
            InteractionResult.PASS
        })

        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            if (!active()) return@AfterSolidFeatures
            val rest = order.drop(progress).let { if (onlyNext.value) it.take(2) else it }
            if (rest.size < 2) return@AfterSolidFeatures
            val cam = context.gameRenderer().mainCamera.position()
            val points = rest.map { Vec3(it.x + 0.2, it.y + 0.5, it.z + 0.5).subtract(cam) }
            val buffer = context.bufferSource().getBuffer(CustomRenderType.LINES_DEPTH)
            val c = color.value
            LineBox.polyline(context.poseStack(), buffer, points, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat)
            context.bufferSource().endBatch()
        })
    }

    override fun onDisable() = reset()
}
