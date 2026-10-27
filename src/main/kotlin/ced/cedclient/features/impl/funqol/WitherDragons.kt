//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.events.ParticleEvent
import ced.cedclient.render.bufferSource
import ced.cedclient.render.draw
import ced.cedclient.render.polyline
import ced.cedclient.events.PlaySoundEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.impl.render.Box3D
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.render.LineBox
import ced.cedclient.render.WorldText
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.DungeonState
import ced.cedclient.state.DungeonState.DungeonClass
import ced.cedclient.utils.Color
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * M7 phase 5 (the five Wither dragons): the spawn flames in the pits start a 100 tick countdown per dragon, shown over the pit,
 * with a priority dragon on the HUD, a box around each live dragon's arena, its health, and a stats chat line when it dies.
 * Unique: the countdowns carry their spawn order (#1, #2...), the arrow stack marker is drawn as a box at the lead point that
 * accounts for arrow drop, and the stats line also shows how many ticks the dragon lived.
 */
object WitherDragons : RowHud("Wither Dragons", "M7 dragons: spawn timers, priority, boxes, health, arrow stack and stats.", "wither_dragons", 3, 100) {
    private enum class State { DEAD, SPAWNING, ALIVE }

    private class Dragon(
        val name: String, val code: String, val spawn: Vec3, val arena: AABB, val color: Color,
        val x: ClosedFloatingPointRange<Double>, val z: ClosedFloatingPointRange<Double>, val skipTicks: Int
    ) {
        var state = State.DEAD
        var ticksToSpawn = 100
        var order = 0
        var entityId: Int? = null
        var spawnedAt = 0L
        var arrows = 0
        var health = 1_000_000_000f
        var missingTicks = 0
    }

    private val dragons = listOf(
        Dragon("Power", "§c", Vec3(27.0, 14.0, 59.0), AABB(14.5, 13.0, 45.5, 39.5, 28.0, 70.5), Color(255, 85, 85), 24.0..30.0, 56.0..62.0, 50),
        Dragon("Flame", "§6", Vec3(85.0, 14.0, 56.0), AABB(72.0, 8.0, 47.0, 102.0, 28.0, 77.0), Color(255, 170, 0), 82.0..88.0, 53.0..59.0, 62),
        Dragon("Apex", "§a", Vec3(27.0, 14.0, 94.0), AABB(7.0, 8.0, 80.0, 37.0, 28.0, 110.0), Color(85, 255, 85), 23.0..29.0, 91.0..97.0, 52),
        Dragon("Ice", "§b", Vec3(84.0, 14.0, 94.0), AABB(71.5, 16.0, 82.5, 96.5, 26.0, 107.5), Color(85, 255, 255), 82.0..88.0, 91.0..97.0, 47),
        Dragon("Soul", "§5", Vec3(56.0, 14.0, 125.0), AABB(45.5, 13.0, 113.5, 68.5, 23.0, 136.5), Color(170, 0, 170), 53.0..59.0, 122.0..128.0, 38),
    )

    private val timerStyle = DropdownSetting("Timer Style", listOf("Milliseconds", "Seconds", "Ticks"), "Milliseconds", "How the spawn countdown is shown.")
    private val symbol = BooleanSetting("Timer Symbol", true, "The unit after the number.")
    private val timers = BooleanSetting("Dragon Timer", true, "A countdown over each spawning dragon.")
    private val stack = BooleanSetting("Arrow Stack Indicator", true, "A marker where to aim to stack arrows on a spawning dragon.")
    private val stackColor = ColorSetting("Indicator Color", Color(0, 255, 255), "The marker colour.").withDependency { stack.value }
    private val boxes = BooleanSetting("Dragon Skip Box", true, "A box around the arena of a spawning or live dragon.")
    private val health = BooleanSetting("Dragon Health", true, "Health above a live dragon.")
    private val prio = BooleanSetting("Dragon Priority", false, "Pick which dragon to take first.")
    private val soloPrio = DropdownSetting("Solo Priority", listOf("Healer", "Tank"), "Healer", "Which class takes the slower dragon.").withDependency { prio.value }
    private val firstOnly = BooleanSetting("First Dragon Only", true, "Only the first two spawns use the class rule.").withDependency { prio.value }
    private val sendTime = BooleanSetting("Send Dragon Time Alive", true, "A chat line with how long it lived when it dies.")
    private val sendArrows = BooleanSetting("Send Arrows Hit", true, "Arrows that hit the priority dragon during its skip window.")
    private val tracer = BooleanSetting("Dragon Tracer", false, "A line to the priority dragon's pit.")

    private var priority: Dragon? = null
    private var spawnCount = 0
    private var tick = 0L
    private var world: Any? = null

    private fun active() = isEnabled && DungeonState.inDungeon && DungeonState.f7Phase == 5

    private fun timerText(ticks: Int): String = when (timerStyle.value) {
        "Milliseconds" -> "${ticks * 50}${if (symbol.value) "ms" else ""}"
        "Seconds" -> "%.1f%s".format(java.util.Locale.ROOT, ticks / 20f, if (symbol.value) "s" else "")
        else -> "$ticks${if (symbol.value) "t" else ""}"
    }

    private fun healthText(h: Float): String {
        val color = when { h >= 750e6f -> "§a"; h >= 500e6f -> "§e"; h >= 250e6f -> "§6"; else -> "§c" }
        val s = when {
            h >= 1e9f -> "%.1fb".format(java.util.Locale.ROOT, h / 1e9f)
            h >= 1e6f -> "${(h / 1e6f).toInt()}m"
            h >= 1e3f -> "${(h / 1e3f).toInt()}k"
            else -> "${h.toInt()}"
        }
        return color + s
    }

    private fun reset() { dragons.forEach { it.state = State.DEAD; it.ticksToSpawn = 100; it.order = 0; it.entityId = null; it.arrows = 0; it.health = 1e9f; it.missingTicks = 0 }; priority = null; spawnCount = 0 }

    private fun die(d: Dragon) {
        if (d.state == State.DEAD) return
        val lived = tick - d.spawnedAt
        d.state = State.DEAD; d.entityId = null; d.ticksToSpawn = 100
        val parts = ArrayList<String>()
        if (sendTime.value) parts += "§7Time: §6${"%.2f".format(java.util.Locale.ROOT, lived / 20.0)}s §8(${lived}t)"
        if (sendArrows.value && d === priority) parts += "§fArrows: §6${d.arrows}"
        if (parts.isNotEmpty()) Minecraft.getInstance().player?.sendSystemMessage(Component.literal("§b[CC] ${d.code}${d.name}: §f${parts.joinToString(" §7| ")}"))
        if (priority === d) priority = null
    }

    override fun rows(): List<Triple<String, String, Int>>? {
        if (!active() || !timers.value) return null
        val p = priority?.takeIf { it.state == State.SPAWNING && it.ticksToSpawn > 0 } ?: return null
        return listOf(Triple(p.name, timerText(p.ticksToSpawn), p.color.rgba))
    }

    override fun preview() = listOf(Triple("Soul", "5000ms", 0xFFAA00AA.toInt()))

    private fun lead(target: Vec3, from: Vec3): Vec3? {
        val distSq = target.distanceToSqr(from)
        var d = 0.0; var speed = 3.0; var vy = 0.0; var drop = 0.0
        repeat(160) {
            d += speed; speed *= 0.99; drop += vy; vy -= 0.05; vy *= 0.99
            if (d * d >= distSq) return target.subtract(0.0, drop, 0.0)
        }
        return null
    }

    init {
        addSettings(timerStyle, symbol, timers, stack, stackColor, boxes, health, prio, soloPrio, firstOnly, sendTime, sendArrows, tracer)
        loadPosition()

        on<ParticleEvent> {
            if (!active()) return@on
            val p = it.packet
            if (p.particle.type != ParticleTypes.FLAME || p.x % 1 != 0.0 || p.z % 1 != 0.0 || p.y != 19.0) return@on
            if (p.count != 20 || p.maxSpeed != 0f || !p.isOverrideLimiter || p.xDist != 2f || p.yDist != 3f || p.zDist != 2f) return@on
            var best: Dragon? = null
            var worst: Dragon? = null
            for (d in dragons) {
                if (d.state != State.SPAWNING) {
                    if (p.x !in d.x || p.z !in d.z) continue
                    d.state = State.SPAWNING
                    d.order = ++spawnCount
                }
                if (best == null || d.ticksToSpawn < best.ticksToSpawn) best = d
                if (worst == null || d.ticksToSpawn > worst.ticksToSpawn) worst = d
            }
            if (prio.value) {
                if (firstOnly.value && spawnCount >= 2) { if (best != null) priority = best; return@on }
                val cls = if (soloPrio.value == "Healer") DungeonClass.HEALER else DungeonClass.TANK
                priority = if (DungeonState.playerClass == cls && worst != null) worst else best ?: priority
            } else if (best != null) priority = best
        }

        on<PlaySoundEvent> {
            if (!active() || !it.soundName.endsWith("entity.arrow.hit_player")) return@on
            val d = priority ?: return@on
            if (d.state == State.ALIVE && tick - d.spawnedAt <= d.skipTicks) d.arrows++
        }

        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            tick++
            if (mc.level !== world) { world = mc.level; reset() }
            val level = mc.level ?: return@register
            if (!active()) return@register
            val live = level.entitiesForRendering().filterIsInstance<EnderDragon>()
            for (d in dragons) {
                when (d.state) {
                    State.SPAWNING -> {
                        d.ticksToSpawn--
                        val e = live.firstOrNull { e -> e.x in d.arena.minX..d.arena.maxX && e.z in d.arena.minZ..d.arena.maxZ }
                        if (e != null) { d.state = State.ALIVE; d.entityId = e.id; d.spawnedAt = tick; d.arrows = 0; d.missingTicks = 0 }
                        else if (d.ticksToSpawn <= -20) { d.state = State.DEAD; d.ticksToSpawn = 100 }
                    }
                    State.ALIVE -> {
                        val e = d.entityId?.let { level.getEntity(it) } as? EnderDragon
                        if (e == null || e.isRemoved) { if (++d.missingTicks > 40) die(d) }
                        else { d.missingTicks = 0; d.health = e.health; if (e.health <= 0f || e.isDeadOrDying) die(d) }
                    }
                    State.DEAD -> {}
                }
            }
        }

        LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { context ->
            if (!active()) return@CollectSubmits
            val mc = Minecraft.getInstance()
            val level = mc.level ?: return@CollectSubmits
            val camera = context.gameRenderer().mainCamera()
            val cam = camera.position()
            val shown = ArrayList<Pair<AABB, Color>>()
            for (d in dragons) {
                if (boxes.value && d.state != State.DEAD) shown += d.arena to d.color
                if (health.value && d.state == State.ALIVE) {
                    val e = d.entityId?.let { level.getEntity(it) }
                    if (e != null) WorldText.draw(context, e.position().add(0.0, -1.0, 0.0), Component.literal(healthText(d.health)))
                }
                if (timers.value && d.state == State.SPAWNING && d.ticksToSpawn > 0) {
                    val tag = if (d.order > 0) " #${d.order}" else ""
                    WorldText.draw(context, d.spawn.add(0.0, 2.0, 0.0), Component.literal("${d.code}${d.name}$tag: ${timerText(d.ticksToSpawn)}"))
                }
            }
            Box3D.draw(context, shown)
            val bufferSource = context.bufferSource()
            val buf = bufferSource.getBuffer(CustomRenderType.LINES_ESP)
            if (stack.value) for (d in dragons) {
                if (d.state != State.SPAWNING) continue
                val target = d.spawn.add(0.5, 3.5, 0.5)
                val lead = lead(target, mc.player?.eyePosition ?: continue) ?: continue
                val size = 0.5 * sqrt((target.distanceTo(mc.player!!.eyePosition) / 50.0).coerceAtLeast(0.25))
                val c = stackColor.value
                LineBox.draw(context.poseStack(), buf, AABB(lead.x - size, lead.y - size, lead.z - size, lead.x + size, lead.y + size, lead.z + size).move(-cam.x, -cam.y, -cam.z), c.redFloat, c.greenFloat, c.blueFloat, 1f)
            }
            val pd = priority
            if (tracer.value && pd != null && pd.state == State.SPAWNING) {
                val look = Vec3.directionFromRotation(camera.xRot(), camera.yRot())
                val c = pd.color
                LineBox.polyline(context.poseStack(), buf, listOf(look, pd.spawn.add(0.5, 3.5, 0.5).subtract(cam)), c.redFloat, c.greenFloat, c.blueFloat, 1f)
            }
            bufferSource.endBatch()
        })
    }
}
//#endif
