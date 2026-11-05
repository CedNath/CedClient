package ced.cedclient.features.impl.funqol

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.render.FilledBox
import ced.cedclient.render.LineBox
import ced.cedclient.render.WorldText
import ced.cedclient.render.geometry
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.Color
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * F7 phase 3, Simon Says. The device is a 4x4 grid: lanterns at x=111, buttons at x=110, y 120-123, z 92-95. Every lantern that
 * lights is added to the sequence (once), and a button you press correctly is taken off the front. Each round shows the
 * sequence from the start again, so the buttons you already pressed light up again in order and come back on the list.
 * The boxes have the shape of the real button (2/16 thick, 1/16 once pressed), not the block.
 */
object SimonSaysSolver : Module(
    "Simon Says Solver",
    Category.Skyblock,
    "Highlights the Simon Says buttons in order, numbered above each one."
) {
    private val color1 = ColorSetting("First Color", Color(80, 255, 120), "Color of the next button.")
    private val color2 = ColorSetting("Second Color", Color(255, 224, 102), "Color of the button after it.")
    private val color3 = ColorSetting("Other Color", Color(240, 80, 80), "Color of the rest of the buttons.")
    private val boxMode = DropdownSetting("Box Mode", listOf("Outline", "Filled", "Both"), "Both", "What the button boxes look like.")
    private val numbers = BooleanSetting("Step Numbers", true, "Number each button still to press (1 = next); the numbers count down as you press.")
    private val showLines = BooleanSetting("Show Lines", false, "Also draw a line through the buttons in order.")
    private val onlyNext = BooleanSetting("Only Next Line", false, "Only the line to the next button, not the whole rest of the sequence.")
    private val ssSkip = BooleanSetting("SS Skip Compatibility", true, "Assume you skipped the first button of the sequence (the usual SS skip), so it is not drawn.")
    private val blockWrong = BooleanSetting("Block Wrong Clicks", true, "Cancel clicks on a button that is not the next one (sneak to override).")
    private val summary = BooleanSetting("Run Summary", true, "When you finish the device: time taken and how many wrong clicks it cost, or \"perfect\".")

    private val START_BUTTON = BlockPos(110, 121, 91)
    private val DONE = Regex("""^(\w+) completed a device! """)
    private const val LANTERN_X = 111
    private const val BUTTON_X = 110
    private val Y_RANGE = 120..123
    private val Z_RANGE = 92..95
    private val BUTTONS = Y_RANGE.flatMap { y -> Z_RANGE.map { z -> BlockPos(BUTTON_X, y, z) } }

    private val mc get() = Minecraft.getInstance()

    /** Buttons still to press, in order. */
    private val order = ArrayList<BlockPos>()
    private val wasLit = HashSet<BlockPos>()
    private var pressedAny = false
    private var wrongClicks = 0
    private var startedAt = 0L
    private var lastLitAt = 0L

    private fun active() = isEnabled && DungeonState.inDungeon && DungeonState.f7Phase == 3

    private fun reset() { order.clear(); wasLit.clear(); pressedAny = false; wrongClicks = 0; startedAt = 0L; lastLitAt = 0L }

    /** The button as it really is: on the lantern wall's west face, 2/16 thick, 1/16 while pressed. */
    private fun shape(pos: BlockPos): AABB {
        val state = mc.level?.getBlockState(pos)
        val depth = (if (state != null && state.`is`(Blocks.STONE_BUTTON) && state.getValue(ButtonBlock.POWERED)) 1.0 else 2.0) / 16.0
        return AABB(pos.x + 1 - depth, pos.y + 0.375, pos.z + 0.3125, pos.x + 1.0, pos.y + 0.625, pos.z + 0.6875)
    }

    init {
        addSettings(color1, color2, color3, boxMode, numbers, showLines, onlyNext, ssSkip, blockWrong, summary)

        on<ChatMessageEvent> {
            if (!active() || startedAt == 0L) return@on
            val m = DONE.find(it.unformattedText.trim()) ?: return@on
            if (m.groupValues[1] != mc.user.name) return@on
            if (summary.value) {
                val secs = "%.2f".format(java.util.Locale.ROOT, (System.currentTimeMillis() - startedAt) / 1000.0)
                val misses = if (wrongClicks == 0) "§aperfect" else "§c$wrongClicks wrong click${if (wrongClicks == 1) "" else "s"}"
                mc.player?.sendSystemMessage(ChatPrefix.msgFor("Simon Says Solver", "§bSimon Says §e${secs}s§b, $misses"))
            }
            reset()
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            val level = mc.level
            val player = mc.player
            if (level == null || player == null || !active() || player.distanceToSqr(LANTERN_X.toDouble(), 121.0, 93.0) > 50 * 50) {
                if (order.isNotEmpty() || startedAt != 0L) reset()
                return@register
            }
            val lit = HashSet<BlockPos>()
            for (y in Y_RANGE) for (z in Z_RANGE) if (level.getBlockState(BlockPos(LANTERN_X, y, z)).`is`(Blocks.SEA_LANTERN)) lit += BlockPos(BUTTON_X, y, z)
            val nowMs = System.currentTimeMillis()
            if (lit.isNotEmpty()) lastLitAt = nowMs
            // The buttons are gone while the sequence shows, so only a long dark spell (no lantern for 700 ms, as Noamm waits 12 server
            // ticks) with the buttons gone means the device broke and starts over.
            if (order.isNotEmpty() && lit.isEmpty() && nowMs - lastLitAt > 700 && BUTTONS.all { level.getBlockState(it).isAir }) { reset(); return@register }
            for (p in lit) if (p !in wasLit && p !in order) {
                if (startedAt == 0L) startedAt = System.currentTimeMillis()
                // Third lantern and nothing pressed yet: the first button was skipped already.
                if (ssSkip.value && order.size == 2 && !pressedAny) order.removeAt(0)
                order += p
            }
            wasLit.clear(); wasLit.addAll(lit)
        }

        UseBlockCallback.EVENT.register(UseBlockCallback { player, level, _, hit ->
            if (!level.isClientSide || !active()) return@UseBlockCallback InteractionResult.PASS
            val pos = hit.blockPos
            if (pos == START_BUTTON) { reset(); return@UseBlockCallback InteractionResult.PASS }
            if (order.isEmpty() || pos !in BUTTONS || !level.getBlockState(pos).`is`(Blocks.STONE_BUTTON)) return@UseBlockCallback InteractionResult.PASS
            when {
                pos == order[0] -> { order.removeAt(0); pressedAny = true }
                // Three left and you hit the second: the first was pressed without registering, count both.
                order.size == 3 && pos == order[1] -> { order.removeAt(1); order.removeAt(0); pressedAny = true }
                blockWrong.value && !player.isCrouching -> return@UseBlockCallback InteractionResult.FAIL
                else -> wrongClicks++
            }
            InteractionResult.PASS
        })

        LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { context ->
            if (!active() || order.isEmpty()) return@CollectSubmits
            val left = order.toList()
            val cam = context.gameRenderer().mainCamera().position()
            val shapes = left.map { shape(it) }
            val boxes = shapes.mapIndexed { i, box -> box.inflate(0.01).move(-cam.x, -cam.y, -cam.z) to (if (i == 0) color1 else if (i == 1) color2 else color3).value }
            if (boxMode.value != "Outline") context.geometry(CustomRenderType.FILLED) { pose, buffer ->
                for ((box, c) in boxes) FilledBox.draw(pose, buffer, box, c.redFloat, c.greenFloat, c.blueFloat, 0.35f)
            }
            if (boxMode.value != "Filled") context.geometry(CustomRenderType.LINES_DEPTH) { pose, buffer ->
                for ((box, c) in boxes) LineBox.draw(pose, buffer, box, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat)
            }
            if (numbers.value) shapes.forEachIndexed { i, box ->
                WorldText.draw(context, Vec3(box.center.x, box.maxY + 0.3, box.center.z), Component.literal("${i + 1}").withColor(0xFFFFFF))
            }
            if (!showLines.value) return@CollectSubmits
            val rest = if (onlyNext.value) shapes.take(2) else shapes
            if (rest.size < 2) return@CollectSubmits
            val points = rest.map { it.center.subtract(cam) }
            context.geometry(CustomRenderType.LINES_DEPTH) { pose, buffer ->
                val c = color1.value
                LineBox.polyline(pose, buffer, points, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat)
            }
        })
    }

    override fun onDisable() = reset()
}
