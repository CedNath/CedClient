//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
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
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
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
    private val prediction = NumberSetting("Prediction Ticks", 2.0, 0.0, 6.0, 0.5, "Aim this many ticks ahead of the skeleton's movement.")
    private val range = NumberSetting("Control Range", 12.0, 4.0, 30.0, 1.0, "Only aim at Wither Skeletons this close, so it stays off things outside the arena.")
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

    init {
        addSettings(discipline, control, swiftness, range, smoothnessMs, prediction)

        ClientTickEvents.END_CLIENT_TICK.register { if (isEnabled) tick() }
        // Aim and sword checks every rendered frame, so they don't step at 20 Hz.
        LevelRenderEvents.START_MAIN.register { if (isEnabled) frame() }
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
        if (swiftness.value && challenge("Swiftness")) walkToWool(player) else stopWalking()
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
     * Nearest green wool other than the block underfoot. Looks down and turns only as far as needed so the block lies
     * straight along one of the 8 WASD directions, holds those keys, jumps at a gap's edge and brakes on arrival.
     */
    private fun walkToWool(player: LocalPlayer) {
        val level = mc.level ?: return
        val feet = player.blockPosition()
        val under = BlockPos.containing(player.x, player.y - 0.2, player.z)
        val target = BlockPos.betweenClosed(feet.offset(-6, -3, -6), feet.offset(6, 1, 6))
            .filter { it != under && level.getBlockState(it).block.let { b -> b == Blocks.LIME_WOOL || b == Blocks.GREEN_WOOL } }
            .minByOrNull { it.distToCenterSqr(player.position()) }?.immutable()
        val dx = (target?.x ?: 0) + 0.5 - player.x
        val dz = (target?.z ?: 0) + 0.5 - player.z
        val dist = sqrt(dx * dx + dz * dz)
        if (target == null || dist < 0.05) {
            stopWalking()
            return
        }
        val nx = dx / dist
        val nz = dz / dist
        val supportY = if (player.onGround()) under.y else target.y
        val gap = gapLength(player, nx, nz, dist, supportY)

        val yawTo = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        // Long gaps need sprint, which only works with W; otherwise use whichever key direction is closest.
        val keyDir = if (gap >= 2) 0f else Math.round(Mth.wrapDegrees(yawTo - player.yRot) / 45f) * 45f
        val wantYaw = yawTo - keyDir
        walkLook = wantYaw to 80f
        val lined = abs(Mth.wrapDegrees(wantYaw - player.yRot)) < 12f

        val v = player.deltaMovement
        val speed = sqrt(v.x * v.x + v.z * v.z)
        val closing = v.x * nx + v.z * nz
        // Ground friction leaves roughly 2.2x the current speed of slide after letting go.
        val go = lined && dist > 0.25 + speed * 2.2
        val brake = !go && closing > 0.08 && dist < 1.2
        val atEdge = player.onGround() && !solid(player.x + nx * 0.45, supportY, player.z + nz * 0.45)
        val up = target.y > supportY && dist < 1.6

        val dir = when {
            go -> keyDir
            brake -> keyDir + 180f
            else -> null
        }
        pressDirection(dir?.let { Mth.wrapDegrees(it) })
        mc.options.keySprint.setDown(go && gap >= 2)
        mc.options.keyJump.setDown(lined && (atEdge && gap > 0 || up) && player.onGround())
        walking = true
    }

    /** Hold the WASD keys for [dir] degrees relative to where the player faces (0 = W, 90 = D), or none for null. */
    private fun pressDirection(dir: Float?) {
        mc.options.keyUp.setDown(dir != null && abs(dir) <= 45f)
        mc.options.keyDown.setDown(dir != null && abs(dir) >= 135f)
        mc.options.keyRight.setDown(dir != null && dir in 45f..135f)
        mc.options.keyLeft.setDown(dir != null && dir in -135f..-45f)
    }

    /** Blocks of missing floor at [y] along the straight line from the player to [dist] ahead. */
    private fun gapLength(player: LocalPlayer, nx: Double, nz: Double, dist: Double, y: Int): Int {
        val missing = HashSet<Long>()
        var t = 0.3
        while (t < dist - 0.3) {
            val x = player.x + nx * t
            val z = player.z + nz * t
            if (!solid(x, y, z)) missing.add(BlockPos.asLong(floor(x).toInt(), y, floor(z).toInt()))
            t += 0.2
        }
        return missing.size
    }

    private fun solid(x: Double, y: Int, z: Double): Boolean {
        val level = mc.level ?: return false
        val pos = BlockPos(floor(x).toInt(), y, floor(z).toInt())
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty
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
