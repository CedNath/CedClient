//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.render.bufferSource
import ced.cedclient.render.draw
import ced.cedclient.render.polyline
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.Box3D
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.render.LineBox
import ced.cedclient.render.WorldText
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.Color
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * F7 phase 3 Arrow Align device: the grid of item frames at x = -2 (y 120-124, z 75-79). It picks the one of nine layouts that
 * fits the frames' arrows, then writes how many clicks each frame still needs above it. Unique: it also shows the total clicks
 * left over the middle of the grid and the layout number, and wrong clicks (a frame that needs none) can be blocked while
 * you stand still, so a stray click cannot undo a finished frame.
 */
object ArrowAlign : Module(
    "Arrow Align",
    Category.Dungeons,
    "Shows how many clicks each Arrow Align frame needs; can block wrong clicks."
) {
    private val colorStyle = DropdownSetting("Color Style", listOf("Dynamic", "Custom"), "Dynamic", "Dynamic: colours the number by the clicks needed.")
    private val textColor = ColorSetting("Text Color", Color(255, 255, 255), "Number colour for the Custom style.")
    private val blockWrong = BooleanSetting("Block Wrong Clicks", true, "Cancel clicks on a frame that needs none (sneak to override).")
    private val showTotal = BooleanSetting("Show Total", true, "The clicks left over the middle of the grid, with the layout number.")

    private val corner = BlockPos(-2, 120, 75)
    private val grid = AABB(-2.0, 120.0, 75.0, -1.0, 125.0, 80.0)

    private val rotations = IntArray(25) { -1 }
    private val remaining = HashMap<Int, Int>()
    private val clickedAt = HashMap<Int, Long>()
    private var layout = -1

    private val layouts = listOf(
        listOf(7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, -1, -1, 7, 1),
        listOf(-1, -1, 7, 7, 5, -1, 7, 1, -1, 5, -1, -1, -1, -1, -1, -1, 7, 5, -1, 1, -1, -1, 7, 7, 1),
        listOf(7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, -1, 7, 5, -1, -1, -1, -1, 5, -1, -1, -1, 3, 3),
        listOf(5, 3, 3, 3, -1, 5, -1, -1, -1, -1, 7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, -1),
        listOf(5, 3, 3, 3, 3, 5, -1, -1, -1, 1, 7, 7, -1, -1, 1, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1),
        listOf(7, 7, 7, 7, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1),
        listOf(-1, -1, -1, -1, -1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1),
        listOf(-1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, 7, 7, 7, 7, 1, -1, -1, -1, -1, -1),
        listOf(-1, -1, -1, -1, -1, -1, 1, -1, 1, -1, 7, 1, 7, 1, 3, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1),
    )

    private fun active() = isEnabled && DungeonState.inDungeon && DungeonState.f7Phase == 3

    /** Clicks [frame] still needs (0 = none, or unknown yet). Read by the Arrow Align Triggerbot. */
    fun clicksNeeded(frame: ItemFrame): Int {
        if (!active() || frame.item.item != Items.ARROW) return 0
        val pos = frame.blockPosition()
        if (pos.x != corner.x) return 0
        return remaining[(pos.y - corner.y) + (pos.z - corner.z) * 5] ?: 0
    }

    private fun clicks(current: Int, target: Int) = if (target == -1) 0 else (8 - current + target) % 8
    private fun reset() { rotations.fill(-1); remaining.clear(); clickedAt.clear(); layout = -1 }

    init {
        addSettings(colorStyle, textColor, blockWrong, showTotal)

        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            val level = mc.level
            val player = mc.player
            if (!active() || level == null || player == null || player.distanceToSqr(Vec3.atCenterOf(corner)) > 200) { reset(); return@register }
            val now = System.currentTimeMillis()
            for (frame in level.getEntitiesOfClass(ItemFrame::class.java, grid) { it.item.item == Items.ARROW }) {
                val pos = frame.blockPosition()
                val index = (pos.y - corner.y) + (pos.z - corner.z) * 5
                if (index in 0..24 && now - (clickedAt[index] ?: 0L) > 1000) rotations[index] = frame.rotation
            }
            remaining.clear()
            layout = -1
            for ((n, arr) in layouts.withIndex()) {
                // A layout fits when every frame it expects an arrow on has one, and the other way round.
                if (arr.indices.any { (arr[it] == -1 || rotations[it] == -1) && arr[it] != rotations[it] }) continue
                layout = n + 1
                for (i in arr.indices) {
                    if (rotations[i] == -1) continue
                    val need = clicks(rotations[i], arr[i])
                    if (need != 0) remaining[i] = need
                }
                break
            }
        }

        UseEntityCallback.EVENT.register(UseEntityCallback { player, level, _, entity, _ ->
            if (!level.isClientSide || !active() || entity !is ItemFrame || entity.item.item != Items.ARROW) return@UseEntityCallback InteractionResult.PASS
            val pos = entity.blockPosition()
            if (pos.x != corner.x) return@UseEntityCallback InteractionResult.PASS
            val index = (pos.y - corner.y) + (pos.z - corner.z) * 5
            if (index !in 0..24 || rotations[index] == -1) return@UseEntityCallback InteractionResult.PASS
            if (blockWrong.value && !player.isCrouching && layout != -1 && !remaining.containsKey(index)) return@UseEntityCallback InteractionResult.FAIL
            clickedAt[index] = System.currentTimeMillis()
            rotations[index] = (rotations[index] + 1) % 8
            val target = layouts.getOrNull(layout - 1)?.get(index)
            if (target != null && clicks(rotations[index], target) == 0) remaining.remove(index)
            InteractionResult.PASS
        })

        LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { context ->
            if (!active() || remaining.isEmpty()) return@CollectSubmits
            for ((index, count) in remaining) {
                val at = Vec3(corner.x + 0.5, corner.y + (index % 5) + 0.5, corner.z + (index / 5) + 0.5)
                val rgb = if (colorStyle.value == "Dynamic") when { count < 3 -> 0x55FF55; count < 5 -> 0xFFAA00; else -> 0xFF5555 }
                else textColor.value.rgba and 0xFFFFFF
                WorldText.draw(context, at.add(0.0, 0.55, 0.5), Component.literal("$count").withColor(rgb))
            }
            if (showTotal.value) WorldText.draw(
                context, Vec3(corner.x + 0.5, corner.y + 5.4, corner.z + 2.5),
                Component.literal("${remaining.values.sum()} clicks (layout $layout)").withColor(0xFFFF55)
            )
        })
    }
}

/**
 * F7 phase 3 section 4 device (I4): the nine blocks at z = 50. The emerald block is the one to shoot; one you hit turns to blue
 * terracotta. Unique: the target is boxed, finished blocks turn red, the aim point (the middle of the block's side) is marked
 * and a title says how many are done when you finish; it only shows while you stand on the device platform.
 */
object I4Helper : Module(
    "I4 Helper",
    Category.Dungeons,
    "Boxes the I4 target block and the aim point; finished blocks turn red."
) {
    private val targetColor = ColorSetting("Target Color", Color(85, 255, 85), "The block to shoot.")
    private val doneColor = ColorSetting("Done Color", Color(255, 85, 85), "A block already hit.")
    private val aimColor = ColorSetting("Aim Color", Color(255, 255, 85), "The point to aim at.")
    private val title = BooleanSetting("Finish Title", true, "A title with how many blocks were hit when the device completes.")

    private val devBlocks = listOf(
        BlockPos(68, 130, 50), BlockPos(66, 130, 50), BlockPos(64, 130, 50),
        BlockPos(68, 128, 50), BlockPos(66, 128, 50), BlockPos(64, 128, 50),
        BlockPos(68, 126, 50), BlockPos(66, 126, 50), BlockPos(64, 126, 50),
    )
    private val done = HashSet<BlockPos>()
    private var target: BlockPos? = null
    private var aim: Vec3? = null
    private val last = HashMap<BlockPos, Block>()
    private var alerted = false
    private var world: Any? = null
    private val DEVICE_DONE = Regex("""^(\w{3,16}) completed a device! \(\d/\d\)$""")

    private fun onPlatform(): Boolean {
        val p = Minecraft.getInstance().player ?: return false
        return abs(p.y - 127.0) < 0.5 && p.x in 62.0..65.0 && p.z in 34.0..37.0
    }

    private fun aimFor(pos: BlockPos): Vec3 {
        val i = devBlocks.indexOf(pos).coerceAtLeast(0)
        val col = i % 3
        val left = col < 2 && devBlocks[i + 1] in done
        val right = col > 0 && devBlocks[i - 1] in done
        val x = when (col) { 0 -> 67.5; 2 -> 65.5; else -> if (right && !left) 65.5 else 67.5 }
        return Vec3(x, 131.0 - 2 * (i / 3), 50.0)
    }

    init {
        addSettings(targetColor, doneColor, aimColor, title)
        on<ChatMessageEvent> {
            if (!isEnabled || !title.value || alerted || DungeonState.f7P3Section != 4) return@on
            val who = DEVICE_DONE.find(it.unformattedText.trim())?.groupValues?.get(1) ?: return@on
            if (who == Minecraft.getInstance().user.name) {
                alerted = true
                Alerts.title("I4 Helper", "Completed Device!", "${done.size}/9 hit", 0x55FF55, true, 1.2f)
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (mc.level !== world) { world = mc.level; done.clear(); target = null; aim = null; last.clear(); alerted = false }
            val level = mc.level ?: return@register
            if (!isEnabled || DungeonState.f7P3Section != 4) return@register
            for (pos in devBlocks) {
                val now = level.getBlockState(pos).block
                val before = last.put(pos, now)
                if (before == Blocks.EMERALD_BLOCK && now == Blocks.DYED_TERRACOTTA.blue()) done += pos
                if (now == Blocks.EMERALD_BLOCK && before != Blocks.EMERALD_BLOCK) { target = pos; aim = aimFor(pos) }
            }
        }
        LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { context ->
            if (!isEnabled || DungeonState.f7P3Section != 4 || !onPlatform()) return@CollectSubmits
            val boxes = ArrayList<Pair<AABB, Color>>()
            target?.let { boxes += AABB(it) to targetColor.value }
            for (d in done) boxes += AABB(d) to doneColor.value
            Box3D.draw(context, boxes)
            aim?.let { a ->
                val cam = context.gameRenderer().mainCamera().position()
                val bufferSource = context.bufferSource()
                val c = aimColor.value
                LineBox.draw(
                    context.poseStack(), bufferSource.getBuffer(CustomRenderType.LINES_ESP),
                    AABB(a.x - 0.1, a.y - 0.1, a.z - 0.1, a.x + 0.1, a.y + 0.1, a.z + 0.1).move(-cam.x, -cam.y, -cam.z),
                    c.redFloat, c.greenFloat, c.blueFloat, 1f
                )
                bufferSource.endBatch()
            }
        })
    }
}

/**
 * M7 phase 5 relics: a box and a tracer to the cauldron for the relic in your hotbar slot 9, and a chat line with the time each
 * relic took from pick-up to placement. Unique: the line also lists your session best for that relic colour and marks a new
 * best, and the five times are summed into a total when the last one is placed.
 */
object M7Relics : Module(
    "M7 Relics",
    Category.Dungeons,
    "Cauldron box for your relic and a place time per relic."
) {
    private class Relic(val colorName: String, val code: String, val cauldron: Vec3, val color: Color, val coords: Pair<Int, Int>)

    private val relics = listOf(
        Relic("Red", "§c", Vec3(51.0, 7.0, 42.0), Color(255, 0, 0), 52 to 43),
        Relic("Orange", "§6", Vec3(57.0, 7.0, 42.0), Color(255, 114, 0), 58 to 43),
        Relic("Green", "§a", Vec3(49.0, 7.0, 44.0), Color(0, 255, 0), 50 to 45),
        Relic("Blue", "§b", Vec3(59.0, 7.0, 44.0), Color(0, 138, 255), 60 to 45),
        Relic("Purple", "§5", Vec3(54.0, 7.0, 41.0), Color(129, 0, 111), 55 to 42),
    )

    private val box = BooleanSetting("Box Relics", true, "A box on the cauldron for the relic you carry, with a tracer.")
    private val timer = BooleanSetting("Place Timer", true, "A chat line with the time each relic took to place.")
    private val sumTotal = BooleanSetting("Show Total", true, "A total line when all five are placed.")

    private class Entry(val relic: Relic, val player: String, val pickedAt: Long) { var placedAt = 0L }

    private val PICKED = Regex("""^(\w{3,16}) picked the Corrupted (\w{3,6}) Relic!$""")
    private val entries = ArrayList<Entry>()
    private val best = HashMap<String, Long>()
    private var world: Any? = null

    private fun carried(): Relic? {
        val name = Minecraft.getInstance().player?.inventory?.getItem(8)?.hoverName?.string ?: return null
        return relics.firstOrNull { name.equals("Corrupted ${it.colorName} Relic", ignoreCase = true) }
    }

    private fun secs(ms: Long) = "%.2f".format(java.util.Locale.ROOT, ms / 1000.0)

    init {
        addSettings(box, timer, sumTotal)
        on<ChatMessageEvent> {
            if (!isEnabled || !timer.value || DungeonState.floor != "M7" || !DungeonState.inBoss) return@on
            val m = PICKED.find(it.unformattedText.trim()) ?: return@on
            val relic = relics.firstOrNull { r -> r.colorName.equals(m.groupValues[2], ignoreCase = true) } ?: return@on
            entries += Entry(relic, m.groupValues[1], System.currentTimeMillis())
        }
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (mc.level !== world) { world = mc.level; entries.clear() }
            val level = mc.level ?: return@register
            if (!isEnabled || !timer.value || DungeonState.f7Phase != 5) return@register
            val open = entries.filter { it.placedAt == 0L }
            if (open.isEmpty()) return@register
            for (stand in level.entitiesForRendering().filterIsInstance<ArmorStand>()) {
                if (!stand.getItemBySlot(EquipmentSlot.HEAD).hoverName.string.contains("Relic")) continue
                for (e in open) if (e.placedAt == 0L && Math.hypot(stand.x - e.relic.coords.first, stand.z - e.relic.coords.second) < 1.5) e.placedAt = System.currentTimeMillis()
            }
            if (entries.size == 5 && entries.all { it.placedAt != 0L }) {
                val self = mc.user.name
                var total = 0L
                for (e in entries.sortedBy { it.placedAt - it.pickedAt }) {
                    val ms = e.placedAt - e.pickedAt
                    total += ms
                    val mine = e.player.equals(self, true)
                    val prev = best[e.relic.colorName]
                    val isBest = mine && (prev == null || ms < prev)
                    if (isBest) best[e.relic.colorName] = ms
                    val note = if (isBest) " §d§l(best)" else if (mine && prev != null) " §7(best ${secs(prev)}s)" else ""
                    mc.player?.sendSystemMessage(Component.literal("§b[CC] ${e.relic.code}${e.relic.colorName} §aRelic placed in §e${secs(ms)}s§a by ${e.player}.$note"))
                }
                if (sumTotal.value) mc.player?.sendSystemMessage(Component.literal("§b[CC] §7All relics: §f${secs(total)}s §7combined"))
                entries.clear()
            }
        }
        LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { context ->
            if (!isEnabled || !box.value || DungeonState.f7Phase != 5) return@CollectSubmits
            val relic = carried() ?: return@CollectSubmits
            val camera = context.gameRenderer().mainCamera()
            val cam = camera.position()
            Box3D.draw(context, listOf(AABB(BlockPos.containing(relic.cauldron)) to relic.color))
            val bufferSource = context.bufferSource()
            val look = Vec3.directionFromRotation(camera.xRot(), camera.yRot())
            LineBox.polyline(
                context.poseStack(), bufferSource.getBuffer(CustomRenderType.LINES_ESP),
                listOf(look, relic.cauldron.add(0.5, 0.5, 0.5).subtract(cam)), relic.color.redFloat, relic.color.greenFloat, relic.color.blueFloat, 1f
            )
            bufferSource.endBatch()
        })
    }
}

/**
 * F5 Livid boss: the wool block in the ceiling at (5, 108, 40) names the real Livid; it is boxed with its health and a tracer,
 * and the 340 tick invulnerability after the boss line is counted down with an ice spray warning. Unique: the health text
 * colour follows its percentage and the tracer fades out once you are close.
 */
object LividSolver : Module(
    "Livid Solver",
    Category.Dungeons,
    "Finds the real Livid on F5: box, health and tracer, plus the invulnerability timer."
) {
    private val showHp = BooleanSetting("Show HP", true, "The Livid's health above it.")
    private val tracer = BooleanSetting("Tracer", true, "A line from your crosshair to the Livid.")
    private val timer = BooleanSetting("Invulnerability Timer", true, "Counts the first 17 seconds, then shows an ice spray warning.")
    private val iceSpray = BooleanSetting("Ice Spray Title", true, "A title when the invulnerability ends.")
    private val color = ColorSetting("Highlight Color", Color(0, 170, 255), "Box and tracer colour.")

    private val ceiling = BlockPos(5, 108, 40)
    private val names = mapOf(
        Blocks.WOOL.green() to "Frog Livid", Blocks.WOOL.purple() to "Purple Livid", Blocks.WOOL.gray() to "Doctor Livid",
        Blocks.WOOL.blue() to "Scream Livid", Blocks.WOOL.lime() to "Smile Livid", Blocks.WOOL.red() to "Hockey Livid",
        Blocks.WOOL.magenta() to "Crossed Livid", Blocks.WOOL.yellow() to "Arcade Livid", Blocks.WOOL.white() to "Vendetta Livid",
    )
    private const val TICKS = 340

    private var livid: Int? = null
    private var ticksLeft = -1
    private var world: Any? = null

    private fun active() = isEnabled && DungeonState.inDungeon && DungeonState.floor?.endsWith("5") == true && DungeonState.inBoss

    init {
        addSettings(showHp, tracer, timer, iceSpray, color)
        on<ChatMessageEvent> {
            if (isEnabled && timer.value && it.unformattedText.trim() == "[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.") ticksLeft = TICKS
        }
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (mc.level !== world) { world = mc.level; livid = null; ticksLeft = -1 }
            val level = mc.level ?: return@register
            if (ticksLeft > 0) ticksLeft--
            if (ticksLeft == 0) {
                if (iceSpray.value) Alerts.title("Livid Solver", "Ice Spray Livid!", "", 0x55FFFF, true, 1.0f)
                ticksLeft = -1
            }
            if (!active()) return@register
            val target = names[level.getBlockState(ceiling).block] ?: return@register
            val current = livid?.let { level.getEntity(it) } as? net.minecraft.client.player.AbstractClientPlayer
            if (current != null && !current.isRemoved && current.gameProfile.name == target) return@register
            livid = level.players().firstOrNull { it.gameProfile.name == target }?.id
        }
        LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { context ->
            if (!active()) return@CollectSubmits
            val mc = Minecraft.getInstance()
            val e = livid?.let { mc.level?.getEntity(it) } as? LivingEntity ?: return@CollectSubmits
            if (e.isDeadOrDying) return@CollectSubmits
            val camera = context.gameRenderer().mainCamera()
            val cam = camera.position()
            val c = color.value
            Box3D.draw(context, listOf(Box3D.smooth(e).inflate(0.05) to c))
            if (tracer.value) {
                val fade = (e.position().distanceTo(cam) / 12.0).coerceIn(0.0, 1.0).toFloat()
                val bufferSource = context.bufferSource()
                val look = Vec3.directionFromRotation(camera.xRot(), camera.yRot())
                LineBox.polyline(
                    context.poseStack(), bufferSource.getBuffer(CustomRenderType.LINES_ESP),
                    listOf(look, e.position().add(0.0, 0.9, 0.0).subtract(cam)), c.redFloat, c.greenFloat, c.blueFloat, fade
                )
                bufferSource.endBatch()
            }
            if (showHp.value) {
                val pct = (e.health / e.maxHealth).coerceIn(0f, 1f)
                val rgb = when { pct > 0.66f -> 0x55FF55; pct > 0.33f -> 0xFFAA00; else -> 0xFF5555 }
                WorldText.draw(context, e.position().add(0.0, 3.0, 0.0), Component.literal("${e.health.toInt()}").withColor(rgb))
            }
        })
    }
}
//#endif
