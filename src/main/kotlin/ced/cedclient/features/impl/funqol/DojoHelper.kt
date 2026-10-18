//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.state.Sidebar
import ced.cedclient.utils.HumanLook
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Crimson Isle Dojo (sidebar "Challenge: ...").
 *  - Test of Discipline: the zombie under (or nearest) your crosshair decides the sword -- helmet Leather / Iron /
 *    Gold / Diamond -> Wooden / Iron / Golden / Diamond Sword from your hotbar. Checked every frame; you still hit.
 *  - Test of Control: keeps your aim on the Wither Skeleton that keeps moving (the dummies stand still), leading its
 *    movement a few ticks.
 *  - Test of Swiftness: looks down and WASD-walks to each new green wool block (the old ones turn orange then red),
 *    jumping at the edge of a gap and braking so it doesn't slide off into the lava.
 */
object DojoHelper : Module(
    "Dojo Helper",
    Category.Cheats,
    "Discipline: switches to the right sword for the zombie you look at. Control: aims at the Wither Skeleton. Swiftness: walks to the green wool."
) {
    private val mc get() = Minecraft.getInstance()

    private val discipline = BooleanSetting("Discipline Sword Swap", true, "Pick the sword that matches the helmet of the zombie you look at.")
    private val control = BooleanSetting("Control Aim", true, "Keep your aim on the moving Wither Skeleton, not the dummies.")
    private val swiftness = BooleanSetting("Swiftness Auto Walk", true, "Walk and jump onto each new green wool block by itself.")
    private val prediction = NumberSetting("Prediction Ticks", 9.5, 0.0, 20.0, 0.5, "Aim this many ticks ahead of the skeleton's movement.")
    private val swiftDebug = BooleanSetting("Swiftness Debug", false, "Draw what Swiftness sees (wool, path, gaps) and say each decision in chat.")
    private val range = NumberSetting("Control Range", 22.0, 4.0, 30.0, 1.0, "Only aim at Wither Skeletons this close, so it stays off things outside the arena.")
    // New name so configs saved with the old 60 ms snappy default pick up the smoother one.
    private val smoothnessMs = NumberSetting("Aim Smoothness", 220.0, 40.0, 600.0, 10.0, "Milliseconds to settle on the target; higher is smoother.")

    private val swordFor = mapOf<Item, Item>(
        Items.LEATHER_HELMET to Items.WOODEN_SWORD,
        Items.IRON_HELMET to Items.IRON_SWORD,
        Items.GOLDEN_HELMET to Items.GOLDEN_SWORD,
        Items.DIAMOND_HELMET to Items.DIAMOND_SWORD,
    )

    private var look: HumanLook? = null
    private var lastFrameNanos = 0L
    private var aimTarget: WitherSkeleton? = null
    /** Swiftness look: (yaw, pitch) to turn to, or null. */
    private var walkLook: Pair<Float, Float>? = null
    private var walking = false
    /** Wither Skeleton id -> how much it has moved lately (decaying sum of per-tick distance). */
    private val movement = HashMap<Int, Double>()

    // Swiftness debug: what the last tick saw, drawn in the world; the last chat line so only changes get sent.
    private var dbgWool = emptyList<BlockPos>()
    private var dbgTarget: BlockPos? = null
    private var dbgUnder: BlockPos? = null
    private val dbgSamples = ArrayList<Pair<Vec3, Boolean>>()
    private var dbgLast = ""
    /**
     * Swiftness blocks stood on while still green: they stay lime a moment after the next one spawns, so don't walk
     * back. Forgotten once they turn orange, since the snake later reuses the same spots for new blocks.
     */
    private val visited = HashSet<BlockPos>()
    /** Swiftness view yaw, fixed for the whole challenge. */
    private var swiftYaw: Float? = null

    init {
        addSettings(discipline, control, swiftness, swiftDebug, range, smoothnessMs, prediction)

        ClientTickEvents.END_CLIENT_TICK.register { if (isEnabled) tick() }
        // Aim and sword checks every rendered frame, so they don't step at 20 Hz.
        LevelRenderEvents.START_MAIN.register { if (isEnabled) frame() }
        // 26.2: lines go through the SubmitNodeCollector in COLLECT_SUBMITS (see BlockESPRenderer).
        LevelRenderEvents.COLLECT_SUBMITS.register { ctx ->
            if (isEnabled && swiftDebug.value && challenge("Swiftness")) {
                val cam = ctx.gameRenderer().mainCamera().position()
                ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), CustomRenderType.LINES_ESP) { pose, buf -> drawDebug(pose, buf, cam) }
            }
        }
    }

    override fun onDisable() {
        aimTarget = null
        walkLook = null
        look = null
        movement.clear()
        stopWalking()
    }

    private fun challenge(name: String) = Sidebar.lines.any { it.contains("Challenge: $name") }

    private fun tick() {
        val player = mc.player ?: return
        aimTarget = if (control.value && challenge("Control")) pickSkeleton(player) else null
        if (aimTarget == null) movement.clear()
        walkLook = null
        if (swiftness.value && challenge("Swiftness")) walkToWool(player) else {
            visited.clear()
            swiftYaw = null
            stopWalking()
        }
        if (aimTarget == null && walkLook == null) look = null
    }

    /** The Wither Skeleton that has moved the most lately; sticks with the current one unless another clearly moves more. */
    private fun pickSkeleton(player: LocalPlayer): WitherSkeleton? {
        val skeletons = mc.level?.entitiesForRendering()?.filterIsInstance<WitherSkeleton>()?.filter { it.isAlive && player.distanceTo(it) <= range.value } ?: return null
        movement.keys.retainAll(skeletons.map { it.id }.toSet())
        for (s in skeletons) {
            val moved = s.position().distanceTo(Vec3(s.xOld, s.yOld, s.zOld))
            // A teleport counts as movement too, but capped so one jump doesn't outweigh steady walking.
            movement[s.id] = (movement[s.id] ?: 0.0) * 0.92 + moved.coerceAtMost(1.0)
        }
        val best = skeletons.maxWithOrNull(compareBy<WitherSkeleton> { movement[it.id] ?: 0.0 }.thenBy { -player.distanceTo(it) })
            ?: return null
        val current = aimTarget?.takeIf { it.isAlive && !it.isRemoved && it in skeletons } ?: return best
        val cur = movement[current.id] ?: 0.0
        return if ((movement[best.id] ?: 0.0) > cur * 1.5 + 0.3) best else current
    }

    /**
     * Nearest green wool not yet stood on. Holds one straight-on view looking down and steers with WASD per axis, aiming
     * where the slide / jump will end so it stops on the block's middle; jumps when the arc lands there.
     */
    private fun walkToWool(player: LocalPlayer) {
        val level = mc.level ?: return
        val feet = player.blockPosition()
        val under = BlockPos.containing(player.x, player.y - 0.2, player.z)
        // betweenClosed hands out one reused mutable pos: copy each before keeping it.
        val wool = BlockPos.betweenClosed(feet.offset(-6, -3, -6), feet.offset(6, 1, 6)).map { it.immutable() }
            .filter { level.getBlockState(it).block.let { b -> b == Blocks.WOOL.lime() || b == Blocks.WOOL.green() } }
        visited.retainAll(wool.toSet())
        if (player.onGround() && under in wool) visited.add(under.immutable())
        val target = wool.filter { it != under && it !in visited }.minByOrNull { it.distToCenterSqr(player.position()) }
        if (swiftDebug.value) {
            dbgWool = wool
            dbgTarget = target
            dbgUnder = under
            dbgSamples.clear()
        }
        val dx = (target?.x ?: 0) + 0.5 - player.x
        val dz = (target?.z ?: 0) + 0.5 - player.z
        val dist = sqrt(dx * dx + dz * dz)
        if (target == null || dist < 0.05) {
            if (swiftDebug.value) {
                val nearby = BlockPos.betweenClosed(under.offset(-3, -1, -3), under.offset(3, 0, 3))
                    .map { level.getBlockState(it).block.name.string }.toSet()
                debug(if (target == null) "§cno green wool within 6 §7under: ${level.getBlockState(under).block.name.string} §8| near: ${nearby.joinToString()}"
                      else "§7standing on target ${target.toShortString()}")
            }
            stopWalking()
            return
        }
        val nx = dx / dist
        val nz = dz / dist
        val air = !player.onGround()
        val supportY = if (air) target.y else under.y
        val gap = gapLength(player, nx, nz, dist, supportY)

        // View: turned once to the nearest straight-on direction, looking down, then held; steering is WASD only.
        val yaw = swiftYaw ?: (Math.round(player.yRot / 90f) * 90f).also { swiftYaw = it }
        walkLook = yaw to 80f
        val rad = Math.toRadians(yaw.toDouble())
        val fx = -kotlin.math.sin(rad)
        val fz = kotlin.math.cos(rad)
        // Offset and velocity along W (forward) and D (right).
        val dF = dx * fx + dz * fz
        val dR = dx * -fz + dz * fx
        val v = player.deltaMovement
        val vF = v.x * fx + v.z * fz
        val vR = v.x * -fz + v.z * fx
        val speed = sqrt(v.x * v.x + v.z * v.z)

        // How far each axis still drifts if its keys are let go now: until landing in the air, or the ground slide.
        val ticks = if (air) airTicks(player.y, v.y, target.y + 1.0) else 0
        fun drift(vel: Double) = if (air) vel * (1 - 0.91.pow(ticks)) / 0.09 else vel * 1.2
        // +1 / -1: press toward / away on that axis so the drift ends on the block's middle; 0: leave it.
        fun axis(d: Double, vel: Double): Int {
            val rest = d - drift(vel)
            return if (rest > 0.15) 1 else if (rest < -0.15) -1 else 0
        }
        val f = axis(dF, vF)
        val r = axis(dR, vR)

        // A walking jump clears 2 blocks of lava; 3 needs sprint, which only works holding W.
        val sprinting = gap >= 3 && !air && f > 0 && dF > dist * 0.7
        val tooFar = gap >= 3 && !air && !sprinting
        val go = !tooFar
        mc.options.keyUp.setDown(go && f > 0)
        mc.options.keyDown.setDown(go && f < 0)
        mc.options.keyRight.setDown(go && r > 0)
        mc.options.keyLeft.setDown(go && r < 0)
        mc.options.keySprint.setDown(sprinting)

        // Fallback when the timing never lines up: jump right before the floor runs out (hitbox half-width is 0.3).
        val atEdge = !air && !solid(player.x + nx * 0.25, supportY, player.z + nz * 0.25)
        val reach = landingDistance(speed, sprinting, (target.y - supportY).toDouble())
        // Jump when this speed's arc lands within 0.3 of the block's middle.
        val timed = abs(reach - dist) < 0.3
        val up = target.y > supportY && dist < 1.6
        val jump = go && !air && (gap > 0 && (timed || atEdge) || up)
        mc.options.keyJump.setDown(jump)
        walking = true
        if (swiftDebug.value) {
            val keys = listOf("W" to mc.options.keyUp, "A" to mc.options.keyLeft, "S" to mc.options.keyDown, "D" to mc.options.keyRight)
                .filter { it.second.isDown }.joinToString("") { it.first }.ifEmpty { "-" }
            debug("§a${target.toShortString()} §7${level.getBlockState(target).block.name.string} gap=$gap keys=$keys" +
                (if (sprinting) " §bsprint" else "") + (if (jump) " §ejump" else "") +
                (if (tooFar) " §ctoo far" else "") + (if (atEdge) " §dedge" else "") + (if (timed) " §dtimed" else "") +
                " §7d=%.2f reach=%.2f F=%.2f R=%.2f".format(dist, reach, dF, dR) + (if (up) " §dup" else "") +
                " §8ground=${player.onGround()} under=${level.getBlockState(under).block.name.string}@${under.y}")
        }
    }

    /** Ticks until a fall from [y] at vertical speed [vy] comes down to [landY]. */
    private fun airTicks(y: Double, vy: Double, landY: Double): Int {
        var yy = y
        var v = vy
        for (t in 1..40) {
            yy += v
            v = (v - 0.08) * 0.98
            if (v < 0 && yy <= landY) return t
        }
        return 40
    }

    /**
     * Horizontal distance a jump started now travels before coming back down to [dy] above the takeoff height, at
     * [speed] blocks/tick, holding the move key (air control) the whole way. Sprint-jumping adds the 0.2 boost.
     */
    private fun landingDistance(speed: Double, sprint: Boolean, dy: Double): Double {
        var v = speed + if (sprint) 0.2 else 0.0
        val accel = if (sprint) 0.026 else 0.02
        var vy = 0.42
        var x = 0.0
        var y = 0.0
        repeat(40) {
            v += accel
            x += v
            y += vy
            v *= 0.91
            vy = (vy - 0.08) * 0.98
            if (vy < 0 && y <= dy) return x
        }
        return x
    }

    /** Blocks of missing floor at [y] along the straight line from the player to [dist] ahead. */
    private fun gapLength(player: LocalPlayer, nx: Double, nz: Double, dist: Double, y: Int): Int {
        val missing = HashSet<Long>()
        var t = 0.3
        while (t < dist - 0.3) {
            val x = player.x + nx * t
            val z = player.z + nz * t
            val ok = solid(x, y, z)
            if (!ok) missing.add(BlockPos.asLong(floor(x).toInt(), y, floor(z).toInt()))
            if (swiftDebug.value) dbgSamples.add(Vec3(x, y + 1.0, z) to ok)
            t += 0.2
        }
        return missing.size
    }

    private fun solid(x: Double, y: Int, z: Double): Boolean {
        val level = mc.level ?: return false
        val pos = BlockPos(floor(x).toInt(), y, floor(z).toInt())
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty
    }

    /** Chat line for the debug, only when it says something new. */
    private fun debug(msg: String) {
        if (msg == dbgLast) return
        dbgLast = msg
        mc.player?.sendSystemMessage(ChatPrefix.msg("§8[swift] $msg"))
    }

    private fun drawDebug(pose: com.mojang.blaze3d.vertex.PoseStack.Pose, buf: com.mojang.blaze3d.vertex.VertexConsumer, cam: Vec3) {
        fun line(a: Vec3, b: Vec3, rgb: Int) {
            val m = pose.pose()
            val n = b.subtract(a).normalize()
            val r = (rgb shr 16 and 255) / 255f
            val g = (rgb shr 8 and 255) / 255f
            val bl = (rgb and 255) / 255f
            for (p in listOf(a.subtract(cam), b.subtract(cam)))
                buf.addVertex(m, p.x.toFloat(), p.y.toFloat(), p.z.toFloat()).setColor(r, g, bl, 1f)
                    .setNormal(n.x.toFloat(), n.y.toFloat(), n.z.toFloat()).setLineWidth(2f)
        }
        fun box(bb: AABB, rgb: Int) {
            val xs = listOf(bb.minX, bb.maxX)
            val ys = listOf(bb.minY, bb.maxY)
            val zs = listOf(bb.minZ, bb.maxZ)
            for (y in ys) for (z in zs) line(Vec3(bb.minX, y, z), Vec3(bb.maxX, y, z), rgb)
            for (x in xs) for (z in zs) line(Vec3(x, bb.minY, z), Vec3(x, bb.maxY, z), rgb)
            for (x in xs) for (y in ys) line(Vec3(x, y, bb.minZ), Vec3(x, y, bb.maxZ), rgb)
        }
        // Yellow: every green wool seen. White: block underfoot. Cyan: target, with a line from your feet.
        // Path samples on the floor: green = floor there, red = no floor (lava / air).
        for (w in dbgWool) box(AABB(w).inflate(0.01), 0xFFFF55)
        dbgUnder?.let { box(AABB(it).inflate(0.03), 0xFFFFFF) }
        dbgTarget?.let { t ->
            box(AABB(t).inflate(0.05), 0x55FFFF)
            mc.player?.let { p -> line(p.position().add(0.0, 0.05, 0.0), Vec3(t.x + 0.5, t.y + 1.0, t.z + 0.5), 0x55FFFF) }
        }
        for ((p, ok) in dbgSamples.toList()) box(AABB(p, p).inflate(0.06), if (ok) 0x55FF55 else 0xFF5555)
    }

    private fun stopWalking() {
        if (!walking) return
        walking = false
        for (key in listOf(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight, mc.options.keySprint, mc.options.keyJump)) key.setDown(false)
    }

    private fun frame() {
        val player = mc.player ?: return
        val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(false)
        if (discipline.value && challenge("Discipline")) swapSword(player, pt)

        val (yaw, pitch) = aimTarget?.takeIf { it.isAlive }?.let { anglesTo(player.getEyePosition(pt), predicted(it, pt)) } ?: walkLook ?: return
        val l = look ?: HumanLook((smoothnessMs.value / 1000.0).toFloat(), 540f, 0f).also { look = it; lastFrameNanos = 0L }
        val now = System.nanoTime()
        var dt = if (lastFrameNanos == 0L) 1f / 60f else (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now
        if (dt <= 0f || dt > 0.1f) dt = 1f / 60f
        val (newYaw, newPitch) = l.step(player.yRot, player.xRot, yaw, pitch, dt)
        player.yRot = newYaw
        player.xRot = newPitch
    }

    /** Zombie whose (slightly enlarged) box the crosshair ray hits, else the one within 15 degrees of it. */
    private fun swapSword(player: LocalPlayer, pt: Float) {
        val zombies = mc.level?.entitiesForRendering()?.filterIsInstance<Zombie>()
            ?.filter { it.isAlive && player.distanceTo(it) <= 32f } ?: return
        val eye = player.getEyePosition(pt)
        val end = eye.add(player.getViewVector(pt).scale(32.0))
        val zombie = zombies.mapNotNull { z ->
            z.boundingBox.move(z.getPosition(pt).subtract(z.position())).inflate(0.6).clip(eye, end).orElse(null)?.let { z to it.distanceToSqr(eye) }
        }.minByOrNull { it.second }?.first
            ?: zombies.map { it to angleFromCrosshair(player, eye, it.getPosition(pt).add(0.0, it.bbHeight / 2.0, 0.0)) }
                .filter { it.second <= 15f }.minByOrNull { it.second }?.first
            ?: return
        val sword = swordFor[zombie.getItemBySlot(EquipmentSlot.HEAD).item] ?: return
        val slot = (0..8).firstOrNull { player.inventory.getItem(it).item == sword } ?: return
        if (player.inventory.selectedSlot != slot) player.inventory.setSelectedSlot(slot)
    }

    /** Middle of [e] at this frame, [prediction] ticks ahead along its last tick's movement. */
    private fun predicted(e: Entity, pt: Float): Vec3 {
        // Ignore teleports: leading by a whole jump would swing the aim past the skeleton.
        val moved = e.position().subtract(e.xOld, e.yOld, e.zOld).let { if (it.length() > 1.0) Vec3.ZERO else it }
        return e.getPosition(pt).add(moved.scale(prediction.value)).add(0.0, e.bbHeight / 2.0, 0.0)
    }

    private fun anglesTo(eye: Vec3, p: Vec3): Pair<Float, Float> {
        val dx = p.x - eye.x
        val dy = p.y - eye.y
        val dz = p.z - eye.z
        val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        val pitch = -Math.toDegrees(atan2(dy, sqrt(dx * dx + dz * dz))).toFloat()
        return yaw to pitch
    }

    /** How far (degrees, yaw + pitch combined) [p] is from where the player looks. */
    private fun angleFromCrosshair(player: LocalPlayer, eye: Vec3, p: Vec3): Float {
        val (yaw, pitch) = anglesTo(eye, p)
        return abs(Mth.wrapDegrees(yaw - player.yRot)) + abs(pitch - player.xRot)
    }
}
//#endif
